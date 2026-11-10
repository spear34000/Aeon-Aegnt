import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import type { Tool, ToolAssessment } from "../../core/tools.ts";
import type { WorkspaceGuard } from "../fs-guard.ts";

const MAX_READ_BYTES = 1_000_000;

function pathArg(args: unknown): unknown {
  return (args as { path?: unknown } | null)?.path;
}

function assessPath(guard: WorkspaceGuard, risk: ToolAssessment["risk"], verb: string, args: unknown): ToolAssessment {
  const r = guard.resolve(pathArg(args));
  if (!r.ok) return { risk, summary: `${verb} (blocked)`, resources: [], violation: r.violation };
  return { risk, summary: `${verb} ${r.abs}`, resources: [r.abs] };
}

export function fsTools(guard: WorkspaceGuard): Tool[] {
  const read: Tool = {
    exposesWorkspaceData: true,
    name: "fs.read",
    description: "Read a UTF-8 text file inside the workspace.",
    argsHint: '{"path":"relative/path"}',
    assess: (a) => assessPath(guard, "read", "Read file", a),
    async run(args) {
      const r = guard.resolve(pathArg(args));
      if (!r.ok) return { ok: false, output: r.violation };
      try {
        const st = statSync(r.abs);
        if (!st.isFile()) return { ok: false, output: "not a file" };
        const buf = readFileSync(r.abs);
        const truncated = buf.length > MAX_READ_BYTES;
        return { ok: true, output: buf.subarray(0, MAX_READ_BYTES).toString("utf8") + (truncated ? "\n[truncated]" : "") };
      } catch (e) {
        return { ok: false, output: (e as Error).message };
      }
    },
  };

  const write: Tool = {
    name: "fs.write",
    description: "Create or overwrite a UTF-8 text file inside the workspace.",
    argsHint: '{"path":"relative/path","content":"text"}',
    assess: (a) => {
      const base0 = assessPath(guard, "write", "Write file", a);
      const overwrites = base0.resources[0] !== undefined && existsSync(base0.resources[0]);
      const base = { ...base0, irreversible: overwrites };
      const content = (a as { content?: unknown } | null)?.content;
      if (!base.violation && typeof content !== "string") return { ...base, violation: "content must be a string" };
      return base;
    },
    async run(args) {
      const r = guard.resolve(pathArg(args));
      const content = (args as { content?: unknown } | null)?.content;
      if (!r.ok) return { ok: false, output: r.violation };
      if (typeof content !== "string") return { ok: false, output: "content must be a string" };
      try {
        mkdirSync(dirname(r.abs), { recursive: true });
        writeFileSync(r.abs, content, "utf8");
        return { ok: true, output: `wrote ${Buffer.byteLength(content)} bytes to ${r.abs}` };
      } catch (e) {
        return { ok: false, output: (e as Error).message };
      }
    },
  };

  const del: Tool = {
    name: "fs.delete",
    description: "Delete a file inside the workspace (requires owner approval).",
    argsHint: '{"path":"relative/path"}',
    assess: (a) => ({ ...assessPath(guard, "delete", "DELETE file", a), irreversible: true }),
    async run(args) {
      const r = guard.resolve(pathArg(args));
      if (!r.ok) return { ok: false, output: r.violation };
      try {
        if (!statSync(r.abs).isFile()) return { ok: false, output: "only files can be deleted" };
        rmSync(r.abs);
        return { ok: true, output: `deleted ${r.abs}` };
      } catch (e) {
        return { ok: false, output: (e as Error).message };
      }
    },
  };

  const list: Tool = {
    exposesWorkspaceData: true,
    name: "fs.list",
    description: "List a directory inside the workspace.",
    argsHint: '{"path":"."}',
    assess: (a) => assessPath(guard, "read", "List directory", a),
    async run(args) {
      const r = guard.resolve(pathArg(args));
      if (!r.ok) return { ok: false, output: r.violation };
      try {
        const names = readdirSync(r.abs, { withFileTypes: true }).map((d) => (d.isDirectory() ? `${d.name}/` : d.name));
        return { ok: true, output: names.join("\n") || "(empty)" };
      } catch (e) {
        return { ok: false, output: (e as Error).message };
      }
    },
  };

  return [read, write, del, list];
}
