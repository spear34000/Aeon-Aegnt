import { existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, renameSync, rmdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { composeSkill, parseSkill, type SkillDoc } from "../core/connections.ts";

export const SKILL_FOLDER = /^[a-z0-9][a-z0-9-]{0,63}$/;
export const SKILL_MAX_BYTES = 100_000;

export interface SkillSummary extends SkillDoc {
  folder: string;
  size: number;
  updatedAt: number;
}

export class SkillError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/**
 * Skills are folders under `<config dir>/skills`, each with a SKILL.md. Folder names are restricted so a request
 * can never name a path outside that directory, and a folder or file that is a link is never followed.
 */
export class SkillStore {
  readonly dir: string;
  constructor(dir: string) {
    this.dir = dir;
  }

  private fileFor(folder: string): string {
    if (!SKILL_FOLDER.test(folder)) throw new SkillError(400, "folder must be lower-case letters, digits and hyphens (max 64)");
    return join(this.dir, folder, "SKILL.md");
  }

  private plain(path: string): boolean {
    try {
      return !lstatSync(path).isSymbolicLink();
    } catch {
      return false;
    }
  }

  list(): SkillSummary[] {
    if (!existsSync(this.dir)) return [];
    const out: SkillSummary[] = [];
    for (const folder of readdirSync(this.dir).sort()) {
      if (!SKILL_FOLDER.test(folder)) continue;
      const s = this.read(folder);
      if (s) out.push(s);
    }
    return out;
  }

  read(folder: string): SkillSummary | null {
    const file = this.fileFor(folder);
    if (!this.plain(join(this.dir, folder)) || !this.plain(file)) return null;
    try {
      const st = statSync(file);
      if (!st.isFile() || st.size > SKILL_MAX_BYTES) return null;
      return { folder, ...parseSkill(readFileSync(file, "utf8"), folder), size: st.size, updatedAt: st.mtimeMs };
    } catch {
      return null;
    }
  }

  write(folder: string, doc: SkillDoc, create: boolean): SkillSummary {
    const file = this.fileFor(folder);
    const exists = existsSync(join(this.dir, folder));
    if (create && exists) throw new SkillError(409, "a skill with that folder already exists");
    if (!create && !this.read(folder)) throw new SkillError(404, "skill not found");
    const text = composeSkill(doc);
    if (Buffer.byteLength(text) > SKILL_MAX_BYTES) throw new SkillError(413, "skill is too large (max 100 KB)");
    mkdirSync(join(this.dir, folder), { recursive: true });
    if (!this.plain(join(this.dir, folder))) throw new SkillError(400, "folder is a link");
    const tmp = `${file}.tmp-${process.pid}`;
    writeFileSync(tmp, text, "utf8");
    renameSync(tmp, file);
    return this.read(folder)!;
  }

  remove(folder: string): boolean {
    const file = this.fileFor(folder);
    if (!this.read(folder)) return false;
    rmSync(file);
    try {
      rmdirSync(join(this.dir, folder)); // only if nothing else is in it: the owner may keep other files there
    } catch {
      /* not empty: leave the owner's other files alone */
    }
    return true;
  }
}
