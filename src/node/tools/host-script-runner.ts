import { spawn } from "node:child_process";
import { existsSync, statSync } from "node:fs";
import { delimiter, join } from "node:path";

/** How a script is started on this computer and how much is held back (decision D21). */
export type ScriptKind = "node" | "python" | "shell";

export interface HostCommand {
  file: string;
  args: string[];
  /** What limits the script besides the scrubbed environment and the time limit. */
  confinement: "node-permission" | "none";
}

export interface HostRunSpec {
  command: HostCommand;
  cwd: string;
  env: Record<string, string>;
  timeoutMs: number;
  maxOutputBytes: number;
  signal?: AbortSignal;
}

export interface HostRunResult {
  code: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
  /** The output was cut at the limit and the program stopped. */
  truncated: boolean;
}

export interface HostRunner {
  run(spec: HostRunSpec): Promise<HostRunResult>;
  /** Looks for the program that runs this kind of script on this computer. */
  resolve(kind: ScriptKind, script: string, args: string[], dirs: { skill: string; scratch: string }): HostCommand | { problem: string };
}

export const SCRIPT_KINDS: Record<string, ScriptKind> = { js: "node", mjs: "node", cjs: "node", py: "python", sh: "shell" };

/** Finds an executable on PATH without starting anything (no shell, no `where`). */
export function findProgram(names: string[], env: NodeJS.ProcessEnv = process.env): string | null {
  const dirs = (env.PATH ?? env.Path ?? "").split(delimiter).filter(Boolean);
  const exts = process.platform === "win32" ? (env.PATHEXT ?? ".EXE;.CMD;.BAT").split(";").filter(Boolean) : [""];
  for (const d of dirs) {
    for (const n of names) {
      for (const e of exts) {
        const f = join(d, process.platform === "win32" && /\.[A-Za-z0-9]+$/.test(n) ? n : n + e);
        try {
          if (existsSync(f) && statSync(f).isFile()) return f;
        } catch {
          /* not usable */
        }
      }
    }
  }
  return null;
}

/** Node's permission model flag, which was `--experimental-permission` before Node 23.5. Null when this Node has neither (Node 18, the phone). */
export function permissionFlag(): string | null {
  const flags = process.allowedNodeEnvironmentFlags;
  if (flags.has("--permission")) return "--permission";
  if (flags.has("--experimental-permission")) return "--experimental-permission";
  return null;
}

/**
 * The environment a script gets: nothing of the owner's (no keys, no tokens, no profile), only what a program needs to
 * start, and a scratch folder for temporary files and "home".
 */
export function scrubbedEnv(scratch: string, env: NodeJS.ProcessEnv = process.env): Record<string, string> {
  const out: Record<string, string> = { PATH: env.PATH ?? env.Path ?? "", HOME: scratch, TMPDIR: scratch, LANG: "C.UTF-8" };
  if (process.platform === "win32") {
    for (const k of ["SystemRoot", "SYSTEMROOT", "windir", "PATHEXT"]) if (env[k]) out[k] = env[k]!;
    out.TEMP = scratch;
    out.TMP = scratch;
    out.USERPROFILE = scratch;
  }
  return out;
}

export const nodeHostRunner: HostRunner = {
  resolve(kind, script, args, dirs) {
    if (process.platform === "android") return { problem: "scripts cannot run on this device" };
    if (kind === "node") {
      const flag = permissionFlag();
      if (!flag) return { problem: "this Node.js has no permission model (Node 20 or later is needed to run JavaScript scripts on the computer)" };
      // Reads: the skill's own copy and the scratch folder. Writes: the scratch folder. No programs, no workers, no native add-ons.
      return { file: process.execPath, args: [flag, `--allow-fs-read=${dirs.skill}`, `--allow-fs-read=${dirs.scratch}`, `--allow-fs-write=${dirs.scratch}`, script, ...args], confinement: "node-permission" };
    }
    if (kind === "python") {
      const py = findProgram(["python3", "python"]);
      return py ? { file: py, args: [script, ...args], confinement: "none" } : { problem: "python is not installed on this computer" };
    }
    const sh = findProgram(["sh", "bash"]);
    return sh ? { file: sh, args: [script, ...args], confinement: "none" } : { problem: "no shell (sh or bash) was found on this computer" };
  },

  run(spec) {
    return new Promise((resolve) => {
      const child = spawn(spec.command.file, spec.command.args, { cwd: spec.cwd, env: spec.env, shell: false, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
      let out = "";
      let err = "";
      let size = 0;
      let timedOut = false;
      let truncated = false;
      const stop = () => {
        try {
          child.kill("SIGKILL");
        } catch {
          /* already gone */
        }
      };
      const timer = setTimeout(() => {
        timedOut = true;
        stop();
      }, spec.timeoutMs);
      const onAbort = () => stop();
      spec.signal?.addEventListener("abort", onAbort, { once: true });
      const take = (which: "out" | "err") => (b: Buffer) => {
        size += b.length;
        if (size > spec.maxOutputBytes) {
          if (!truncated) {
            truncated = true;
            stop();
          }
          return;
        }
        if (which === "out") out += b.toString("utf8");
        else err += b.toString("utf8");
      };
      child.stdout.on("data", take("out"));
      child.stderr.on("data", take("err"));
      const done = (code: number, extra = "") => {
        clearTimeout(timer);
        spec.signal?.removeEventListener("abort", onAbort);
        resolve({ code, stdout: out, stderr: err + extra, timedOut, truncated });
      };
      child.on("error", (e) => done(127, `could not start: ${e.message}`));
      child.on("close", (code) => done(code ?? 1));
    });
  },
};
