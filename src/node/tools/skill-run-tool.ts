import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { dirname, join } from "node:path";
import type { Tool, ToolAssessment } from "../../core/tools.ts";
import { skillFilePathOk, skillNameProblem } from "../../core/skills.ts";
import type { WorkspaceGuard } from "../fs-guard.ts";
import type { SkillLibrary } from "../skill-library.ts";
import { SCRIPT_KINDS, scrubbedEnv, type HostRunner, type ScriptKind } from "./host-script-runner.ts";
import { buildDockerArgs, DEFAULT_SANDBOX, dockerRunner, type SandboxConfig, type SandboxRunner } from "./sandbox-tool.ts";

/** Which image and program run a script in Docker, by script kind. Only images in the sandbox allow-list are used (D7). */
const DOCKER: Record<ScriptKind, { image: string; command: string[] }> = {
  python: { image: "python:3.12-alpine", command: ["python"] },
  node: { image: "node:24-alpine", command: ["node"] },
  shell: { image: "alpine:3.20", command: ["sh"] },
};

/** Control characters and line separators: an argument with one could fake lines of the approval card. */
const CONTROL = new RegExp("[\u0000-\u001f\u007f\u2028\u2029]");
const SCRIPT_REVIEW_CHARS = 12_000;
const OUT_MAX = 20_000;
const HOST_TIMEOUT_MS = 60_000;
const HOST_OUTPUT_BYTES = 200_000;

type Isolation = "docker" | "host";

interface Parsed {
  skill: string;
  script: string;
  kind: ScriptKind;
  isolation: Isolation;
  args: string[];
  inputs: Record<string, string>;
}

function parse(args: unknown, cfg: SandboxConfig, hostAllowed: boolean): Parsed | string {
  const o = args as { skill?: unknown; script?: unknown; isolation?: unknown; args?: unknown; inputs?: unknown } | null;
  if (typeof o?.skill !== "string") return "skill must be the name of a skill";
  const bad = skillNameProblem(o.skill);
  if (bad) return bad;
  if (typeof o.script !== "string" || !o.script.startsWith("scripts/") || !skillFilePathOk(o.script)) return "script must be a path like scripts/extract.py inside the skill";
  const kind = SCRIPT_KINDS[o.script.slice(o.script.lastIndexOf(".") + 1).toLowerCase()];
  if (!kind) return "unsupported script type (.py, .js, .mjs, .cjs and .sh can run)";
  const isolation = o.isolation === undefined ? "docker" : o.isolation;
  if (isolation !== "docker" && isolation !== "host") return 'isolation must be "docker" (default) or "host"';
  if (isolation === "docker" && !cfg.allowedImages.includes(DOCKER[kind].image)) return `the sandbox does not allow the image ${DOCKER[kind].image}`;
  if (isolation === "host" && !hostAllowed) return "running scripts on this computer is switched off here; use isolation docker";
  const a = o.args ?? [];
  if (!Array.isArray(a) || a.length > 16 || !a.every((x) => typeof x === "string" && x.length <= 300 && !CONTROL.test(x))) return "args must be at most 16 short single-line strings";
  const inputs: Record<string, string> = {};
  if (o.inputs !== undefined && o.inputs !== null) {
    if (typeof o.inputs !== "object" || Array.isArray(o.inputs)) return "inputs must be an object of file name to text";
    const entries = Object.entries(o.inputs as Record<string, unknown>);
    if (entries.length > 5) return "at most 5 input files";
    for (const [k, v] of entries) {
      if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(k) || typeof v !== "string" || Buffer.byteLength(v) > 50_000) return `input "${k.slice(0, 30)}" must be a plain file name with at most 50 KB of text`;
      inputs[k] = v;
    }
  }
  return { skill: o.skill, script: o.script, kind, isolation, args: a as string[], inputs };
}

/** What the owner is told about how much holds the script back. Written for the approval card, so it says plainly what is NOT limited. */
function isolationText(p: Parsed, hostAllowed: boolean): string {
  if (p.isolation === "docker") return `Isolation: Docker container (${DOCKER[p.kind].image}). No network, read-only root, 256 MB, 1 CPU. Sees only the skill (read-only) and a scratch folder.`;
  const limits = `60 s limit, output capped, environment emptied (none of your keys or variables), runs in a scratch folder, started without a shell.`;
  if (!hostAllowed) return "";
  if (p.kind === "node") return `Isolation: Node.js permission model on THIS computer. It can read only a copy of the skill and the scratch folder, write only the scratch folder, and cannot start other programs. The network is NOT blocked. ${limits}`;
  return `Isolation: NONE besides the limits below. This ${p.kind === "python" ? "Python" : "shell"} script runs as you on THIS computer and can do anything you can, including network access and reading your files. ${limits}`;
}

/**
 * Runs a script from an installed skill, after the owner reads the script on the approval card and says yes, every time,
 * and only from a copy of the bytes the lock file approved (the folder could have been changed a moment ago).
 * `isolation: "docker"` (the default) runs it in the Docker sandbox of D7: no network, read-only root, no capabilities,
 * memory, CPU and process limits, nothing of the workspace visible. `isolation: "host"` (D21) runs it on this computer:
 * JavaScript under Node's permission model, Python and shell scripts with no isolation at all. The card says which, in
 * words. Output comes back as untrusted data. Host runs are not available inside the phone app.
 */
export function skillRunTool(library: SkillLibrary, guard: WorkspaceGuard, runner: SandboxRunner = dockerRunner, cfg: SandboxConfig = DEFAULT_SANDBOX, host: HostRunner | null = null): Tool {
  const hostAllowed = host !== null;
  return {
    exposesWorkspaceData: true,
    name: "skill.run",
    description: `Run a script from a skill's scripts/ folder. The owner reads the script and approves each run. isolation "docker" (default) uses a network-less container and needs Docker on the machine the agent runs on; ${hostAllowed ? 'isolation "host" runs it on this computer with far fewer limits (use it only when Docker is missing). ' : ""}The skill is at /skill in Docker; /work is a scratch folder. Pass small text files with inputs if the script needs data.`,
    argsHint: '{"skill":"pdf-processing","script":"scripts/extract.py","isolation?":"docker","args?":["input.pdf"],"inputs?":{"input.txt":"text"}}',
    assess(args): ToolAssessment {
      const p = parse(args, cfg, hostAllowed);
      if (typeof p === "string") return { risk: "exec", summary: "run a skill script (blocked)", resources: [], violation: p };
      const o = library.open(p.skill);
      if ("problem" in o) return { risk: "exec", summary: `run ${p.script} of ${p.skill} (blocked)`, resources: [], violation: o.problem };
      const f = library.openFile(o.skill, p.script);
      if ("problem" in f) return { risk: "exec", summary: `run ${p.script} of ${p.skill} (blocked)`, resources: [], violation: f.problem };
      const text = f.bytes.toString("utf8");
      if (text.length > SCRIPT_REVIEW_CHARS) return { risk: "exec", summary: `run ${p.script} of ${p.skill} (blocked)`, resources: [], violation: `the script is longer than ${SCRIPT_REVIEW_CHARS} characters, more than the owner can read on one approval` };
      // Arguments are shown quoted, so none can pretend to be part of the card's own text.
      const quoted = p.args.map((x) => JSON.stringify(x));
      const command = p.isolation === "docker" ? [...DOCKER[p.kind].command, `/skill/${p.script}`, ...quoted] : [p.kind === "node" ? "node" : p.kind === "python" ? "python" : "sh", p.script, ...quoted];
      const detail = [
        `Skill ${p.skill} (${o.skill.scope}, from ${o.skill.source?.type ?? "unknown"}), ${p.script}`,
        `Runs as: ${command.join(" ")}`,
        isolationText(p, hostAllowed),
        ...(Object.keys(p.inputs).length > 0 ? [`Input files written to the scratch folder: ${Object.keys(p.inputs).join(", ")}`] : []),
        "",
        text,
      ].join("\n");
      const where = p.isolation === "docker" ? "in the Docker sandbox" : p.kind === "node" ? "on this computer (Node permission model)" : "on this computer WITHOUT isolation";
      return { risk: "exec", summary: `Run ${p.script} of skill ${p.skill} ${where}`.slice(0, 300), resources: [`skill:${p.skill}`, p.script, `isolation:${p.isolation}`], irreversible: true, detail };
    },
    async run(args, ctx) {
      const p = parse(args, cfg, hostAllowed);
      if (typeof p === "string") return { ok: false, output: `ERROR: ${p}` };
      const o = library.open(p.skill);
      if ("problem" in o) return { ok: false, output: `ERROR: ${o.problem}` };
      const entries = library.verifiedEntries(o.skill);
      if ("problem" in entries) return { ok: false, output: `ERROR: ${entries.problem}` };
      if (!entries.some((e) => e.path === p.script)) return { ok: false, output: `ERROR: no script ${p.script} in skill ${p.skill}` };
      // A fresh scratch folder and skill copy for every run, never shared with sandbox.exec or an earlier run: nothing a
      // previous script left behind (a link, say) can be followed by this one.
      const run = `${ctx.taskId}-${randomBytes(4).toString("hex")}`;
      const scratch = guard.resolve(join(".aeon", "sandbox", `${run}-work`), { allowProtected: true });
      const copy = guard.resolve(join(".aeon", "sandbox", `${run}-skill`), { allowProtected: true });
      if (!scratch.ok) return { ok: false, output: scratch.violation };
      if (!copy.ok) return { ok: false, output: copy.violation };
      mkdirSync(scratch.abs, { recursive: true });
      const cap = (t: string) => (t.length > OUT_MAX ? `${t.slice(0, OUT_MAX)}\n[… ${t.length - OUT_MAX} more characters not shown]` : t);
      try {
        for (const e of entries) {
          const target = join(copy.abs, ...e.path.split("/"));
          mkdirSync(dirname(target), { recursive: true });
          writeFileSync(target, e.bytes);
        }
        for (const [name, text] of Object.entries(p.inputs)) writeFileSync(join(scratch.abs, name), text, { encoding: "utf8", flag: "wx" });
        if (p.isolation === "docker") {
          const d = DOCKER[p.kind];
          const r = await runner.run(buildDockerArgs(cfg, d.image, [...d.command, `/skill/${p.script}`, ...p.args], scratch.abs, [{ source: copy.abs, target: "/skill" }]), {
            timeoutMs: cfg.timeoutMs ?? 60_000,
            ...(ctx.signal ? { signal: ctx.signal } : {}),
          });
          const hint = r.code !== 0 && /docker|daemon|not found|cannot connect/i.test(r.stderr) ? '\n(If Docker is not installed or not running, a script can be run with isolation "host" instead; the owner will see the difference on the approval card.)' : "";
          return { ok: r.code === 0, output: `exit=${r.code}\nstdout:\n${cap(r.stdout)}\nstderr:\n${cap(r.stderr)}${hint}` };
        }
        const cmd = host!.resolve(p.kind, join(copy.abs, ...p.script.split("/")), p.args, { skill: copy.abs, scratch: scratch.abs });
        if ("problem" in cmd) return { ok: false, output: `ERROR: ${cmd.problem}` };
        const r = await host!.run({ command: cmd, cwd: scratch.abs, env: scrubbedEnv(scratch.abs), timeoutMs: cfg.timeoutMs ?? HOST_TIMEOUT_MS, maxOutputBytes: HOST_OUTPUT_BYTES, ...(ctx.signal ? { signal: ctx.signal } : {}) });
        const note = r.timedOut ? `\n(stopped after ${(cfg.timeoutMs ?? HOST_TIMEOUT_MS) / 1000} seconds)` : r.truncated ? "\n(stopped: it printed more than the output limit)" : "";
        return { ok: r.code === 0 && !r.timedOut && !r.truncated, output: `exit=${r.code}\nstdout:\n${cap(r.stdout)}\nstderr:\n${cap(r.stderr)}${note}` };
      } finally {
        rmSync(copy.abs, { recursive: true, force: true });
        rmSync(scratch.abs, { recursive: true, force: true });
      }
    },
  };
}
