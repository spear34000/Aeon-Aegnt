import { existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, rmdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { composeSkillMd, parseSkillMd, skillFilePathOk, skillLinkedFiles, skillProblems, SKILL_DIRS, SKILL_FILE_MAX_BYTES, type SkillSpec } from "../core/skills.ts";

/** A folder name the store will look at. Whether it follows the spec is reported per skill, not decided here. */
const FOLDER = /^[a-z0-9][a-z0-9-]{0,63}$/;
export const SKILL_MAX_BYTES = 100_000;
/** What the agent may write itself (skill.create / skill.update). Installed skills may be larger, see INSTALL_LIMITS. */
const MAX_FILES = 20;
const MAX_TOTAL_BYTES = 500_000;
/** What one installed skill may hold in total. */
export const INSTALL_LIMITS = { files: 200, fileBytes: 1_000_000, totalBytes: 5_000_000 };

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
  /** Things that do not break the skill (an unknown field, a file the instructions point to that is missing). */
  warnings: string[];
  /** Supporting files (references/, assets/, scripts/). */
  files: string[];
  /** The skill has files in scripts/. */
  scripts: boolean;
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
 * Skills are folders under one directory, each with a SKILL.md in the Agent Skills format (see core/skills.ts).
 * Folder names are restricted so a request can never name a path outside that directory, and a folder or file that is
 * a link is never followed. Nothing here runs code.
 */
export class SkillStore {
  readonly dir: string;
  constructor(dir: string) {
    this.dir = dir;
  }

  folderPath(folder: string): string {
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

  /** Supporting files below references/, assets/ and scripts/ (three levels), skipping links and anything oddly named. */
  private supportFiles(folderDir: string): string[] {
    const out: string[] = [];
    const walk = (rel: string, depth: number) => {
      const d = join(folderDir, rel);
      if (!this.plain(d)) return;
      let names: string[];
      try {
        names = readdirSync(d).sort();
      } catch {
        return;
      }
      for (const n of names) {
        const r = `${rel}/${n}`;
        const full = join(folderDir, r);
        if (!this.plain(full)) continue;
        try {
          const st = statSync(full);
          if (st.isFile() && skillFilePathOk(r)) out.push(r);
          else if (st.isDirectory() && depth < 3) walk(r, depth + 1);
        } catch {
          /* vanished */
        }
      }
    };
    for (const sub of SKILL_DIRS) walk(sub, 1);
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
      const { spec, problems, warnings } = parseSkillMd(readFileSync(file, "utf8"));
      const full: SkillSpec = { name: spec.name ?? "", description: spec.description ?? "", body: spec.body, ...(spec.license !== undefined ? { license: spec.license } : {}), ...(spec.compatibility !== undefined ? { compatibility: spec.compatibility } : {}), ...(spec.metadata ? { metadata: spec.metadata } : {}), ...(spec.allowedTools !== undefined ? { allowedTools: spec.allowedTools } : {}) };
      const all = [...new Set([...problems, ...skillProblems(full, folder)])];
      const files = this.supportFiles(dir);
      const missing = skillLinkedFiles(full.body).filter((f) => !files.includes(f)).map((f) => `the instructions point to ${f}, which is not in the skill`);
      return { folder, ...full, name: full.name || folder, problems: all, warnings: [...warnings, ...missing], files, scripts: files.some((f) => f.startsWith("scripts/")), size: st.size, updatedAt: st.mtimeMs, body: full.body };
    } catch {
      return null;
    }
  }

  /** A supporting file of a skill, as bytes. Only files this store would list; anything else is "not found". */
  readBytes(folder: string, rel: string, maxBytes = SKILL_FILE_MAX_BYTES): Buffer | null {
    if (!skillFilePathOk(rel)) return null;
    const dir = this.folderPath(folder);
    if (!this.plain(dir)) return null;
    let at = dir;
    for (const part of rel.split("/")) {
      at = join(at, part);
      if (!this.plain(at)) return null;
    }
    try {
      const st = statSync(at);
      if (!st.isFile() || st.size > maxBytes) return null;
      return readFileSync(at);
    } catch {
      return null;
    }
  }

  /** A supporting file as text (null when missing, too large or not text). */
  readFile(folder: string, rel: string): string | null {
    const b = this.readBytes(folder, rel);
    if (!b || b.includes(0)) return null;
    return b.toString("utf8");
  }

  /**
   * Everything the skill is made of that Aeon reads or runs: SKILL.md and the supporting files, as bytes. Used for the
   * content hash and for copying a verified skill. Null when something is a link, too large or unreadable.
   */
  entries(folder: string): { path: string; bytes: Buffer }[] | null {
    const dir = this.folderPath(folder);
    const file = join(dir, "SKILL.md");
    if (!this.plain(dir) || !this.plain(file)) return null;
    try {
      const out: { path: string; bytes: Buffer }[] = [{ path: "SKILL.md", bytes: readFileSync(file) }];
      let total = out[0]!.bytes.length;
      for (const rel of this.supportFiles(dir)) {
        const b = this.readBytes(folder, rel, INSTALL_LIMITS.fileBytes);
        if (!b) return null;
        total += b.length;
        if (total > INSTALL_LIMITS.totalBytes || out.length >= INSTALL_LIMITS.files) return null;
        out.push({ path: rel, bytes: b });
      }
      return out;
    } catch {
      return null;
    }
  }

  /** Throws a SkillError with every problem when the skill or its files do not follow the spec. */
  check(spec: SkillSpec, files: Record<string, string> = {}, opts: { scripts?: boolean } = {}): void {
    const problems = skillProblems(spec, spec.name);
    if (spec.body.trim().length === 0) problems.push("the instructions are empty");
    const names = Object.keys(files);
    if (names.length > MAX_FILES) problems.push(`more than ${MAX_FILES} supporting files`);
    let total = 0;
    for (const n of names) {
      if (!skillFilePathOk(n)) problems.push(`file "${n.slice(0, 40)}" must be references/<name>, assets/<name> or scripts/<name> (names of letters, digits . _ -)`);
      else if (n.startsWith("scripts/") && !opts.scripts) problems.push(`file "${n.slice(0, 40)}": the agent cannot add scripts; they come only from a skill the owner installs`);
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

  /**
   * Puts a prepared folder (already checked, in a staging folder inside this store's directory) in place of the skill
   * called [name]. The old one is moved aside first and deleted only after the new one is in place, so a failure leaves
   * one or the other, never half of each.
   */
  place(name: string, staged: string, replace: boolean): void {
    const dir = this.folderPath(name);
    if (existsSync(dir)) {
      if (!replace) throw new SkillError(409, `a skill named "${name}" already exists`);
      const aside = join(this.dir, `.old-${name}-${process.pid}-${Date.now()}`);
      renameSync(dir, aside);
      try {
        renameSync(staged, dir);
      } catch (e) {
        renameSync(aside, dir);
        throw e;
      }
      rmSync(aside, { recursive: true, force: true });
    } else {
      mkdirSync(this.dir, { recursive: true });
      renameSync(staged, dir);
    }
  }

  private atomic(file: string, text: string): void {
    const tmp = `${file}.tmp-${process.pid}`;
    writeFileSync(tmp, text, "utf8");
    renameSync(tmp, file);
  }

  /** Deletes the skill: SKILL.md and the files Aeon knows about. Anything else in the folder is the owner's and stays. */
  remove(folder: string): boolean {
    const dir = this.folderPath(folder);
    if (!this.read(folder)) return false;
    rmSync(join(dir, "SKILL.md"));
    for (const rel of this.supportFiles(dir)) rmSync(join(dir, rel), { force: true });
    // Empty folders go, deepest first; a folder with something else in it is left alone.
    const dirs: string[] = [];
    const collect = (d: string) => {
      try {
        for (const n of readdirSync(d)) {
          const p = join(d, n);
          if (this.plain(p) && statSync(p).isDirectory()) {
            collect(p);
            dirs.push(p);
          }
        }
      } catch {
        /* gone */
      }
    };
    collect(dir);
    for (const d of [...dirs, dir]) {
      try {
        if (readdirSync(d).length === 0) rmdirSync(d);
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
