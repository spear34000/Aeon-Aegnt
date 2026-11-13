import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { Tool, ToolAssessment } from "../../core/tools.ts";
import { skillFilePathOk, skillNameProblem } from "../../core/skills.ts";
import type { WorkspaceGuard } from "../fs-guard.ts";
import type { SkillLibrary } from "../skill-library.ts";
import { buildDockerArgs, DEFAULT_SANDBOX, dockerRunner, type SandboxConfig, type SandboxRunner } from "./sandbox-tool.ts";

/** Which image and program run a script, by file ending. Only images in the sandbox allow-list are used (decision D7). */
const INTERPRETERS: Record<string, { image: string; command: string[] }> = {
  py: { image: "python:3.12-alpine", command: ["python"] },
  js: { image: "node:24-alpine", command: ["node"] },
  mjs: { image: "node:24-alpine", command: ["node"] },
  cjs: { image: "node:24-alpine", command: ["node"] },
  sh: { image: "alpine:3.20", command: ["sh"] },
};

const SCRIPT_REVIEW_CHARS = 12_000;
const OUT_MAX = 20_000;

interface Parsed {
  skill: string;
  script: string;
  args: string[];
  inputs: Record<string, string>;
  image: string;
  command: string[];
}

function parse(args: unknown, cfg: SandboxConfig): Parsed | string {
  const o = args as { skill?: unknown; script?: unknown; args?: unknown; inputs?: unknown } | null;
  if (typeof o?.skill !== "string") return "skill must be the name of a skill";
  const bad = skillNameProblem(o.skill);
  if (bad) return bad;
  if (typeof o.script !== "string" || !o.script.startsWith("scripts/") || !skillFilePathOk(o.script)) return "script must be a path like scripts/extract.py inside the skill";
  const interp = INTERPRETERS[o.script.slice(o.script.lastIndexOf(".") + 1).toLowerCase()];
  if (!interp) return "unsupported script type (.py, .js, .mjs, .cjs and .sh can run)";
  if (!cfg.allowedImages.includes(interp.image)) return `the sandbox does not allow the image ${interp.image}`;
  const a = o.args ?? [];
  if (!Array.isArray(a) || a.length > 16 || !a.every((x) => typeof x === "string" && x.length <= 300 && !x.includes("\u0000"))) return "args must be at most 16 short strings";
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
  return { skill: o.skill, script: o.script, args: a as string[], inputs, image: interp.image, command: interp.command };
}

/**
 * Runs a script from an installed skill. Only inside the Docker sandbox (no network, read-only root, no capabilities,
 * memory, CPU and process limits), and only after the owner reads the script on the approval card and says yes, every
 * time. The skill's files are copied from the content the lock file approved (not from the folder, which could have been
 * changed a moment ago) and mounted read-only at /skill; /work is a scratch folder for this task. Nothing from the
 * workspace is visible to the script. Output comes back as untrusted data.
 */
export function skillRunTool(library: SkillLibrary, guard: WorkspaceGuard, runner: SandboxRunner = dockerRunner, cfg: SandboxConfig = DEFAULT_SANDBOX): Tool {
  return {
    exposesWorkspaceData: true,
    name: "skill.run",
    description: "Run a script from a skill's scripts/ folder in a network-less Docker container (needs Docker on the machine the agent runs on). The owner reads the script and approves each run. The skill is at /skill (read-only); /work is a scratch folder. Pass small text files with inputs if the script needs data.",
    argsHint: '{"skill":"pdf-processing","script":"scripts/extract.py","args?":["input.pdf"],"inputs?":{"input.txt":"text"}}',
    assess(args): ToolAssessment {
      const p = parse(args, cfg);
      if (typeof p === "string") return { risk: "exec", summary: "run a skill script (blocked)", resources: [], violation: p };
      const o = library.open(p.skill);
      if ("problem" in o) return { risk: "exec", summary: `run ${p.script} of ${p.skill} (blocked)`, resources: [], violation: o.problem };
      const f = library.openFile(o.skill, p.script);
      if ("problem" in f) return { risk: "exec", summary: `run ${p.script} of ${p.skill} (blocked)`, resources: [], violation: f.problem };
      const text = f.bytes.toString("utf8");
      const cut = text.length > SCRIPT_REVIEW_CHARS;
      if (cut) return { risk: "exec", summary: `run ${p.script} of ${p.skill} (blocked)`, resources: [], violation: `the script is longer than ${SCRIPT_REVIEW_CHARS} characters, more than the owner can read on one approval` };
      const detail = [
        `Skill ${p.skill} (${o.skill.scope}, from ${o.skill.source?.type ?? "unknown"}), ${p.script}`,
        `Runs as: ${[...p.command, `/skill/${p.script}`, ...p.args].join(" ")}`,
        `Image: ${p.image}. No network, read-only root, 256 MB, 1 CPU. Sees only the skill (read-only) and a scratch folder.`,
        ...(Object.keys(p.inputs).length > 0 ? [`Input files written to the scratch folder: ${Object.keys(p.inputs).join(", ")}`] : []),
        "",
        text,
      ].join("\n");
      return { risk: "exec", summary: `Run ${p.script} of skill ${p.skill} in the Docker sandbox`.slice(0, 300), resources: [`skill:${p.skill}`, p.script, p.image], irreversible: true, detail };
    },
    async run(args, ctx) {
      const p = parse(args, cfg);
      if (typeof p === "string") return { ok: false, output: `ERROR: ${p}` };
      const o = library.open(p.skill);
      if ("problem" in o) return { ok: false, output: `ERROR: ${o.problem}` };
      const entries = library.verifiedEntries(o.skill);
      if ("problem" in entries) return { ok: false, output: `ERROR: ${entries.problem}` };
      if (!entries.some((e) => e.path === p.script)) return { ok: false, output: `ERROR: no script ${p.script} in skill ${p.skill}` };
      const scratch = guard.resolve(join(".aeon", "sandbox", ctx.taskId), { allowProtected: true });
      const copy = guard.resolve(join(".aeon", "sandbox", `${ctx.taskId}-skill`), { allowProtected: true });
      if (!scratch.ok) return { ok: false, output: scratch.violation };
      if (!copy.ok) return { ok: false, output: copy.violation };
      mkdirSync(scratch.abs, { recursive: true });
      rmSync(copy.abs, { recursive: true, force: true });
      try {
        for (const e of entries) {
          const target = join(copy.abs, ...e.path.split("/"));
          mkdirSync(dirname(target), { recursive: true });
          writeFileSync(target, e.bytes);
        }
        for (const [name, text] of Object.entries(p.inputs)) writeFileSync(join(scratch.abs, name), text, "utf8");
        const r = await runner.run(buildDockerArgs(cfg, p.image, [...p.command, `/skill/${p.script}`, ...p.args], scratch.abs, [{ source: copy.abs, target: "/skill" }]), {
          timeoutMs: cfg.timeoutMs ?? 60_000,
          ...(ctx.signal ? { signal: ctx.signal } : {}),
        });
        const cap = (t: string) => (t.length > OUT_MAX ? `${t.slice(0, OUT_MAX)}\n[… ${t.length - OUT_MAX} more characters not shown]` : t);
        return { ok: r.code === 0, output: `exit=${r.code}\nstdout:\n${cap(r.stdout)}\nstderr:\n${cap(r.stderr)}` };
      } finally {
        rmSync(copy.abs, { recursive: true, force: true });
      }
    },
  };
}
