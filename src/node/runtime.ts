import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { Agent } from "../core/agent.ts";
import { systemClock, type Clock } from "../core/clock.ts";
import { isLoopbackUrl, type EndpointConfig, type Profile } from "../core/config.ts";
import { ModelRouter } from "../core/models/router.ts";
import type { ModelProvider } from "../core/models/types.ts";
import { exaoneProvider, llamaCppProvider, ollamaProvider, openAiCompatible } from "../core/models/providers.ts";
import { DEFAULT_RULES, PolicyEngine, type PolicyRule } from "../core/policy.ts";
import { Redactor } from "../core/redact.ts";
import type { Store } from "../core/store.ts";
import { ToolRegistry } from "../core/tools.ts";
import { WorkflowEngine } from "../core/workflow.ts";
import { WorkspaceGuard } from "./fs-guard.ts";
import { SqliteStore } from "./sqlite-store.ts";
import { fsTools } from "./tools/fs-tools.ts";
import { httpTools } from "./tools/http-tools.ts";
import { sandboxTool, type SandboxRunner } from "./tools/sandbox-tool.ts";
import { skillTools } from "./tools/skill-tools.ts";
import { SkillStore } from "./skills.ts";
import { nodeVerifierEnv } from "./verifier-env.ts";

export interface RuntimeOptions {
  workspace: string;
  /** Provider ids in priority order, e.g. ["exaone","ollama"]. */
  providers: string[];
  /** Owner-approved automatic fallback to later providers. */
  fallbackAllowed: boolean;
  /** External provider ids the owner consents to send prompts to. */
  dataConsent: string[];
  env?: Record<string, string | undefined>;
  holderId?: string;
  leaseTtlMs?: number;
  clock?: Clock;
  store?: Store;
  providerOverrides?: ModelProvider[];
  policyRules?: PolicyRule[];
  sandboxRunner?: SandboxRunner;
  /** Injected transport for provider HTTP calls (tests). Defaults to global fetch. */
  fetchImpl?: typeof fetch;
  /** Release builds set this: refuse to wire providers marked `unreleased` . */
  releaseMode?: boolean;
  /** OpenAI-compatible services from the settings; `providers` may name them by id. */
  endpoints?: EndpointConfig[];
  profile?: Profile;
  /** Folder of skills (Agent Skills format). When set the agent can read skills and propose new ones. */
  skillsDir?: string;
}

/**
 * Secrets come ONLY from environment variables in Phase 1 (nothing is written to disk).
 * EXAONE_API_KEY, OPENAI_COMPAT_API_KEY, ... see buildProviders().
 */
export function buildProviders(opts: RuntimeOptions, redactor: Redactor): ModelProvider[] {
  const env = opts.env ?? process.env;
  const out: ModelProvider[] = [];
  for (const id of opts.providers) {
    switch (id) {
      case "exaone": {
        const key = env.EXAONE_API_KEY;
        if (!key) throw new Error("Provider 'exaone' needs EXAONE_API_KEY in the environment.");
        const effort = env.EXAONE_REASONING_EFFORT;
        out.push(
          exaoneProvider({
            apiKey: key,
            redactor,
            ...(opts.fetchImpl ? { fetch: opts.fetchImpl } : {}),
            ...(effort === "low" || effort === "high" || effort === "max" ? { reasoningEffort: effort } : {}),
          }),
        );
        break;
      }
      case "ollama": {
        const model = env.OLLAMA_MODEL;
        if (!model) throw new Error("Provider 'ollama' needs OLLAMA_MODEL (e.g. exaone3.5:7.8b).");
        out.push(ollamaProvider({ model, redactor, ...(env.OLLAMA_BASE_URL ? { baseUrl: env.OLLAMA_BASE_URL } : {}) }));
        break;
      }
      case "llamacpp":
        out.push(llamaCppProvider({ redactor, ...(env.LLAMACPP_BASE_URL ? { baseUrl: env.LLAMACPP_BASE_URL } : {}) }));
        break;
      case "openai-compatible": {
        const baseUrl = env.OPENAI_COMPAT_BASE_URL;
        const model = env.OPENAI_COMPAT_MODEL;
        if (!baseUrl || !model) throw new Error("Provider 'openai-compatible' needs OPENAI_COMPAT_BASE_URL and OPENAI_COMPAT_MODEL.");
        out.push(
          openAiCompatible({
            baseUrl,
            model,
            redactor,
            external: true,
            ...(env.OPENAI_COMPAT_API_KEY ? { apiKey: env.OPENAI_COMPAT_API_KEY } : {}),
          }),
        );
        break;
      }
      default: {
        const ep = (opts.endpoints ?? []).find((e) => e.id === id);
        if (!ep) throw new Error(`Unknown provider: ${id}`);
        const local = isLoopbackUrl(ep.baseUrl);
        const key = ep.keyName ? env[ep.keyName] : undefined;
        if (ep.keyName && !key && !local) throw new Error(`Provider '${ep.name ?? id}' needs ${ep.keyName}.`);
        out.push(
          openAiCompatible({
            id,
            baseUrl: ep.baseUrl,
            model: ep.model,
            redactor,
            external: !local,
            ...(key ? { apiKey: key } : {}),
            ...(ep.reasoningEffort ? { reasoningEffort: ep.reasoningEffort } : {}),
            ...(opts.fetchImpl ? { fetch: opts.fetchImpl } : {}),
          }),
        );
      }
    }
  }
  return out;
}

export function createRuntime(opts: RuntimeOptions) {
  const clock = opts.clock ?? systemClock;
  const redactor = new Redactor();
  const guard = new WorkspaceGuard(opts.workspace);
  mkdirSync(join(guard.root, ".aeon"), { recursive: true });
  const store: Store = opts.store ?? new SqliteStore(join(guard.root, ".aeon", "aeon.db"));

  const providers = opts.providerOverrides ?? buildProviders(opts, redactor);
  if (opts.releaseMode) {
    const blocked = providers.filter((p) => p.unreleased).map((p) => p.id);
    if (blocked.length > 0) throw new Error(`Release build refuses unreleased providers: ${blocked.join(", ")}`);
  }
  const router = new ModelRouter({
    providers,
    fallbackAllowed: opts.fallbackAllowed,
    dataConsent: new Set(opts.dataConsent),
    store,
    clock,
  });

  const tools = new ToolRegistry();
  for (const t of fsTools(guard)) tools.register(t);
  for (const t of httpTools()) tools.register(t);
  tools.register(sandboxTool(guard, ...(opts.sandboxRunner ? [opts.sandboxRunner] : [])));
  const skills = opts.skillsDir ? new SkillStore(opts.skillsDir) : undefined;
  if (skills) for (const t of skillTools(skills)) tools.register(t);

  const policy = new PolicyEngine(opts.policyRules ?? DEFAULT_RULES);
  const agent = new Agent({
    router, tools, policy, store, clock, redactor,
    ...(opts.profile ? { profile: opts.profile } : {}),
    // Only skills that follow the format are offered to the model.
    ...(skills ? { skills: () => skills.list().filter((s) => s.problems.length === 0).map((s) => ({ name: s.name, description: s.description })) } : {}),
  });
  const engine = new WorkflowEngine({
    store,
    clock,
    runner: agent,
    verifierEnv: nodeVerifierEnv(guard),
    redactor,
    holderId: opts.holderId ?? `local-${process.pid}`,
    ...(opts.leaseTtlMs ? { leaseTtlMs: opts.leaseTtlMs } : {}),
  });
  return { engine, store, router, tools, policy, guard, redactor, clock, skills };
}
