import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ManualClock } from "../src/core/clock.ts";
import { MemoryStore } from "../src/core/memory-store.ts";
import type { MockProvider } from "../src/core/models/mock.ts";
import type { PolicyRule } from "../src/core/policy.ts";
import type { Store } from "../src/core/store.ts";
import type { SandboxRunner } from "../src/node/tools/sandbox-tool.ts";
import { createRuntime } from "../src/node/runtime.ts";

export function tempDir(prefix = "aeon-test-"): { dir: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  return { dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

export const fakeSandbox = (calls: string[][] = []): SandboxRunner => ({
  async run(args) {
    calls.push(args);
    return { code: 0, stdout: "fake-sandbox-output", stderr: "" };
  },
});

export interface HarnessOptions {
  providers: MockProvider[];
  fallback?: boolean;
  store?: Store;
  clock?: ManualClock;
  rules?: PolicyRule[];
  holderId?: string;
  workspace?: string;
  consent?: string[];
  /** Folder of skills; the agent can then read and propose them. */
  skillsDir?: string;
  /** File of what the owner wants remembered; the agent then has the memory tools and sees what fits its goal. */
  memoryFile?: string;
}

/** Full runtime wired to mock models, a temp workspace and an in-memory (or supplied) store. */
export function harness(opts: HarnessOptions) {
  const own = opts.workspace ? null : tempDir();
  const workspace = opts.workspace ?? own!.dir;
  const clock = opts.clock ?? new ManualClock();
  const sandboxCalls: string[][] = [];
  const rt = createRuntime({
    workspace,
    providers: opts.providers.map((p) => p.id),
    providerOverrides: opts.providers,
    fallbackAllowed: opts.fallback ?? false,
    dataConsent: opts.consent ?? [],
    clock,
    store: opts.store ?? new MemoryStore(),
    sandboxRunner: fakeSandbox(sandboxCalls),
    ...(opts.holderId ? { holderId: opts.holderId } : {}),
    ...(opts.rules ? { policyRules: opts.rules } : {}),
    ...(opts.skillsDir ? { skillsDir: opts.skillsDir } : {}),
    ...(opts.memoryFile ? { memoryFile: opts.memoryFile } : {}),
  });
  return { ...rt, clock, workspace, sandboxCalls, cleanup: () => own?.cleanup() };
}

export const json = (o: unknown): string => JSON.stringify(o);

/** Typical model reply helpers. */
export const planReply = (plan: string[] = ["do it"], criteria: unknown[] = []): string => json({ plan, criteria });
export const toolReply = (tool: string, args: unknown): string => json({ action: "tool", tool, args });
export const finishReply = (summary = "done"): string => json({ action: "finish", summary });
