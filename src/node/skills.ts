import { existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, rmdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { composeSkillMd, parseSkillMd, skillProblems, SKILL_FILE_MAX_BYTES, type SkillSpec } from "../core/skills.ts";

/** A folder name the store will look at. Whether it follows the spec is reported per skill, not decided here. */
const FOLDER = /^[a-z0-9][a-z0-9-]{0,63}$/;
export const SKILL_MAX_BYTES = 100_000;
const MAX_FILES = 20;
const MAX_TOTAL_BYTES = 500_000;
/** Supporting files: `references/` and `assets/`, one level below. Aeon cannot run `scripts/`, so it does not accept them. */
const FILE_PATH = /^(references|assets)\/[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export interface SkillSummary {
  folder: string;
  name: string;
  description: string;
  license?: string;
  compatibility?: string;
  metadata?: Record<string, string>;
  allowedTools?: string;
  /** What is wrong with the SKILL.md, in plain sentences. Empty when it follows the Agent Skills spec. */
  problems: string[];
  /** Supporting files that can be read with `readFile`. */
  files: string[];
  size: number;
  updatedAt: number;
}

export type SkillFull = SkillSummary & { body: string };

export class SkillError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/**
 * Skills are folders under `<config dir>/skills`, each with a SKILL.md in the Agent Skills format (see core/skills.ts).
 * Folder names are restricted so a request can never name a path outside that directory, and a folder or file that is
 * a link is never followed. Only the agent (after the owner approves) or the owner changes them; nothing here runs code.
 */
export class SkillStore {
  readonly dir: string;
  constructor(dir: string) {
    this.dir = dir;
  }

  private folderPath(folder: string): string {
    if (!FOLDER.test(folder)) throw new SkillError(400, "a skill name is lower-case letters, digits and hyphens (max 64)");
    return join(this.dir, folder);
  }

  private plain(path: string): boolean {
    try {
      return !lstatSync(path).isSymbolicLink();
    } catch {
      return false;
    }
  }

  private supportFiles(folderDir: string): string[] {
    const out: string[] = [];
    for (const sub of ["references", "assets"]) {
      const d = join(folderDir, sub);
      if (!this.plain(d)) continue;
      try {
        for (const f of readdirSync(d).sort()) {
          const rel = `${sub}/${f}`;
          if (FILE_PATH.test(rel) && this.plain(join(d, f)) && statSync(join(d, f)).isFile()) out.push(rel);
        }
      } catch {
        /* no such folder */
      }
    }
    return out;
  }

  list(): SkillSummary[] {
    if (!existsSync(this.dir)) return [];
    const out: SkillSummary[] = [];
    for (const folder of readdirSync(this.dir).sort()) {
      if (!FOLDER.test(folder)) continue;
      const s = this.read(folder);
      if (s) out.push(summary(s));
    }
    return out;
  }

  read(folder: string): SkillFull | null {
    const dir = this.folderPath(folder);
    const file = join(dir, "SKILL.md");
    if (!this.plain(dir) || !this.plain(file)) return null;
    try {
      const st = statSync(file);
      if (!st.isFile() || st.size > SKILL_MAX_BYTES) return null;
      const { spec, problems } = parseSkillMd(readFileSync(file, "utf8"));
      const full: SkillSpec = { name: spec.name ?? "", description: spec.description ?? "", body: spec.body, ...(spec.license !== undefined ? { license: spec.license } : {}), ...(spec.compatibility !== undefined ? { compatibility: spec.compatibility } : {}), ...(spec.metadata ? { metadata: spec.metadata } : {}), ...(spec.allowedTools !== undefined ? { allowedTools: spec.allowedTools } : {}) };
      const all = [...new Set([...problems, ...skillProblems(full, folder)])];
      return { folder, ...full, name: full.name || folder, problems: all, files: this.supportFiles(dir), size: st.size, updatedAt: st.mtimeMs, body: full.body };
    } catch {
      return null;
    }
  }

  /** A supporting file of a skill, as text. Only files this store would list; anything else is "not found". */
  readFile(folder: string, rel: string): string | null {
    const dir = this.folderPath(folder);
    if (!FILE_PATH.test(rel) || !this.plain(dir) || !this.plain(join(dir, dirname(rel))) || !this.plain(join(dir, rel))) return null;
    try {
      const st = statSync(join(dir, rel));
      if (!st.isFile() || st.size > SKILL_FILE_MAX_BYTES) return null;
      return readFileSync(join(dir, rel), "utf8");
    } catch {
      return null;
    }
  }

  /** Throws a SkillError with every problem when the skill or its files do not follow the spec. */
  check(spec: SkillSpec, files: Record<string, string> = {}): void {
    const problems = skillProblems(spec, spec.name);
    if (spec.body.trim().length === 0) problems.push("the instructions are empty");
    const names = Object.keys(files);
    if (names.length > MAX_FILES) problems.push(`more than ${MAX_FILES} supporting files`);
    let total = 0;
    for (const n of names) {
      if (!FILE_PATH.test(n)) problems.push(`file "${n.slice(0, 40)}" must be references/<name> or assets/<name> (scripts are not supported)`);
      const bytes = Buffer.byteLength(files[n]!);
      total += bytes;
      if (bytes > SKILL_FILE_MAX_BYTES) problems.push(`file "${n.slice(0, 40)}" is larger than ${SKILL_FILE_MAX_BYTES} bytes`);
    }
    if (total > MAX_TOTAL_BYTES) problems.push("supporting files are larger than 500 KB together");
    if (Buffer.byteLength(composeSkillMd(spec)) > SKILL_MAX_BYTES) problems.push(`SKILL.md is larger than ${SKILL_MAX_BYTES} bytes (move detail into references/)`);
    if (problems.length > 0) throw new SkillError(400, problems.join("; "));
  }

  /** Writes SKILL.md and the given supporting files. `create` fails when the folder exists, otherwise it must exist. */
  write(spec: SkillSpec, create: boolean, files: Record<string, string> = {}): SkillFull {
    this.check(spec, files);
    const dir = this.folderPath(spec.name);
    const exists = existsSync(dir);
    if (create && exists) throw new SkillError(409, `a skill named "${spec.name}" already exists (use skill.update to change it)`);
    const before = create ? null : this.read(spec.name);
    if (!create && !before) throw new SkillError(404, "skill not found");
    // An edit that does not mention an optional field keeps what the skill had.
    if (before) {
      spec = {
        ...spec,
        ...(spec.license === undefined && before.license !== undefined ? { license: before.license } : {}),
        ...(spec.compatibility === undefined && before.compatibility !== undefined ? { compatibility: before.compatibility } : {}),
        ...(spec.metadata === undefined && before.metadata !== undefined ? { metadata: before.metadata } : {}),
        ...(spec.allowedTools === undefined && before.allowedTools !== undefined ? { allowedTools: before.allowedTools } : {}),
      };
    }
    mkdirSync(dir, { recursive: true });
    if (!this.plain(dir)) throw new SkillError(400, "folder is a link");
    for (const [rel, text] of Object.entries(files)) {
      const target = join(dir, rel);
      mkdirSync(dirname(target), { recursive: true });
      if (!this.plain(dirname(target)) || (existsSync(target) && !this.plain(target))) throw new SkillError(400, "a supporting file location is a link");
      this.atomic(target, text);
    }
    this.atomic(join(dir, "SKILL.md"), composeSkillMd(spec));
    return this.read(spec.name)!;
  }

  private atomic(file: string, text: string): void {
    const tmp = `${file}.tmp-${process.pid}`;
    writeFileSync(tmp, text, "utf8");
    renameSync(tmp, file);
  }

  remove(folder: string): boolean {
    const dir = this.folderPath(folder);
    if (!this.read(folder)) return false;
    rmSync(join(dir, "SKILL.md"));
    for (const rel of this.supportFiles(dir)) rmSync(join(dir, rel), { force: true });
    for (const sub of ["references", "assets", ""]) {
      try {
        rmdirSync(sub ? join(dir, sub) : dir); // only if empty: anything else in it is the owner's and stays
      } catch {
        /* not empty or not there */
      }
    }
    return true;
  }
}

function summary(s: SkillFull): SkillSummary {
  const out: Partial<SkillFull> = { ...s };
  delete out.body;
  return out as SkillSummary;
}
