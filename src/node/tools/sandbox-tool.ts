import { execFile } from "node:child_process";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import type { Tool } from "../../core/tools.ts";
import type { WorkspaceGuard } from "../fs-guard.ts";

export interface SandboxRunner {
  /** Runs `docker <args>`; must never invoke a shell. */
  run(args: string[], opts: { timeoutMs: number; signal?: AbortSignal }): Promise<{ code: number; stdout: string; stderr: string }>;
}

export const dockerRunner: SandboxRunner = {
  run(args, opts) {
    return new Promise((resolve) => {
      execFile(
        "docker",
        args,
        { timeout: opts.timeoutMs, maxBuffer: 2_000_000, windowsHide: true, ...(opts.signal ? { signal: opts.signal } : {}) },
        (err, stdout, stderr) => {
          const code = err ? (typeof (err as NodeJS.ErrnoException & { code?: unknown }).code === "number" ? ((err as unknown as { code: number }).code) : 1) : 0;
          resolve({ code, stdout: String(stdout), stderr: err && !stderr ? String(err.message) : String(stderr) });
        },
      );
    });
  },
};

export interface SandboxConfig {
  /** Only these images may run. Pulling an image is network access done by Docker, so keep it short. */
  allowedImages: string[];
  memory?: string; // e.g. "256m"
  cpus?: string; // e.g. "1"
  pidsLimit?: number;
  timeoutMs?: number;
}

export const DEFAULT_SANDBOX: SandboxConfig = {
  allowedImages: ["alpine:3.20", "python:3.12-alpine", "node:24-alpine"],
  memory: "256m",
  cpus: "1",
  pidsLimit: 128,
  timeoutMs: 60_000,
};

/** Builds `docker run` args: no network, read-only root, dropped caps, only a scratch dir mounted. */
export function buildDockerArgs(cfg: SandboxConfig, image: string, command: string[], scratchDir: string): string[] {
  return [
    "run",
    "--rm",
    "--network", "none",
    "--read-only",
    "--cap-drop", "ALL",
    "--security-opt", "no-new-privileges",
    "--memory", cfg.memory ?? "256m",
    "--cpus", cfg.cpus ?? "1",
    "--pids-limit", String(cfg.pidsLimit ?? 128),
    "--tmpfs", "/tmp:rw,size=64m",
    "--mount", `type=bind,source=${scratchDir},target=/work`,
    "--workdir", "/work",
    image,
    ...command,
  ];
}

/** D7: command execution exists only inside the Docker sandbox. There is no host-exec tool. */
export function sandboxTool(guard: WorkspaceGuard, runner: SandboxRunner = dockerRunner, cfg: SandboxConfig = DEFAULT_SANDBOX): Tool {
  const parse = (args: unknown): { image: string; command: string[] } | string => {
    const o = args as { image?: unknown; command?: unknown } | null;
    if (typeof o?.image !== "string") return "image must be a string";
    if (!cfg.allowedImages.includes(o.image)) return `image not in allow-list: ${o.image}`;
    if (!Array.isArray(o.command) || o.command.length === 0 || !o.command.every((c) => typeof c === "string")) {
      return "command must be a non-empty array of strings (no shell string)";
    }
    return { image: o.image, command: o.command as string[] };
  };
  return {
    name: "sandbox.exec",
    description: "Run a command inside a network-less Docker container with only a scratch directory mounted at /work.",
    argsHint: '{"image":"alpine:3.20","command":["echo","hi"]}',
    assess(a) {
      const p = parse(a);
      if (typeof p === "string") return { risk: "exec", summary: "sandbox exec (blocked)", resources: [], violation: p };
      return { risk: "exec", summary: `docker run ${p.image} ${p.command.join(" ")}`.slice(0, 300), resources: [p.image] };
    },
    async run(args, ctx) {
      const p = parse(args);
      if (typeof p === "string") return { ok: false, output: p };
      const scratch = guard.resolve(join(".aeon", "sandbox", ctx.taskId), { allowProtected: true });
      if (!scratch.ok) return { ok: false, output: scratch.violation };
      mkdirSync(scratch.abs, { recursive: true });
      const r = await runner.run(buildDockerArgs(cfg, p.image, p.command, scratch.abs), {
        timeoutMs: cfg.timeoutMs ?? 60_000,
        ...(ctx.signal ? { signal: ctx.signal } : {}),
      });
      return { ok: r.code === 0, output: `exit=${r.code}\nstdout:\n${r.stdout}\nstderr:\n${r.stderr}` };
    },
  };
}
