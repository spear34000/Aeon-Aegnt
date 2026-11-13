import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
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
import { FileStore } from "./file-store.ts";
import { fsTools } from "./tools/fs-tools.ts";
import { httpTools } from "./tools/http-tools.ts";
import { DEFAULT_SANDBOX, dockerRunner, sandboxTool, type SandboxRunner } from "./tools/sandbox-tool.ts";
import { skillTools } from "./tools/skill-tools.ts";
import { skillRunTool } from "./tools/skill-run-tool.ts";
import { nodeHostRunner, type HostRunner } from "./tools/host-script-runner.ts";
import { SkillLibrary } from "./skill-library.ts";
import type { FetchLike } from "./skill-sources.ts";
import { nodeVerifierEnv } from "./verifier-env.ts";

/**
 * The SQLite store needs `node:sqlite` (Node 22 and later). Where it is missing, such as Node 18 inside the Android
 * app, the file-backed store is used instead; both pass the same conformance tests.
 */
const SqliteStoreClass = await import("./sqlite-store.ts").then((m) => m.SqliteStore).catch(() => null);

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
  /** Skills of this project; defaults to `<workspace>/.agents/skills`. */
  projectSkillsDir?: string;
  /** Which skills the owner trusts; defaults to `skills-lock.json` next to skillsDir. Keep it outside the workspace. */
  skillsLockFile?: string;
  /** Injected transport for fetching skills from GitHub (tests). */
  skillFetch?: FetchLike;
  /**
   * Runs skill scripts on this computer when a task asks for isolation "host" (D21). Default: the real runner, except inside the
   * phone app. Pass null to switch host runs off, or a fake in tests.
   */
  hostRunner?: HostRunner | null;
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
  const store: Store = opts.store ?? (SqliteStoreClass ? new SqliteStoreClass(join(guard.root, ".aeon", "aeon.db")) : new FileStore(join(guard.root, ".aeon", "aeon.json")));

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
  const skills = opts.skillsDir
    ? new SkillLibrary({
        userDir: opts.skillsDir, workspace: guard.root, projectDir: opts.projectSkillsDir ?? join(guard.root, ".agents", "skills"),
        lockFile: opts.skillsLockFile ?? join(dirname(opts.skillsDir), "skills-lock.json"), ...(opts.skillFetch ? { fetchImpl: opts.skillFetch } : {}), ...(opts.clock ? { now: () => opts.clock!.now() } : {}),
      })
    : undefined;
  if (skills) {
    for (const t of skillTools(skills)) tools.register(t);
    tools.register(skillRunTool(skills, guard, opts.sandboxRunner ?? dockerRunner, DEFAULT_SANDBOX, opts.hostRunner === undefined ? (process.platform === "android" ? null : nodeHostRunner) : opts.hostRunner));
  }

  const policy = new PolicyEngine(opts.policyRules ?? DEFAULT_RULES);
  const agent = new Agent({
    router, tools, policy, store, clock, redactor,
    ...(opts.profile ? { profile: opts.profile } : {}),
    // Only skills the owner trusts, that are switched on and follow the format, are offered to the model.
    ...(skills ? { skills: () => skills.catalog() } : {}),
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
