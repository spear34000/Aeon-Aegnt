import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export type SkillScope = "user" | "project";

/** Where a skill came from. `agent` skills were written by the agent after the owner approved them. */
export type SkillSource =
  | { type: "agent" }
  /** Found in the skills folder before this file existed: adopted once, on the owner's own machine. */
  | { type: "legacy" }
  | { type: "local"; path: string }
  | { type: "github"; owner: string; repo: string; ref: string | null; path: string; commit?: string };

/**
 * One trusted skill. A skill is trusted only while its folder still hashes to [hash]: the owner approved exactly that
 * content (what they read on the approval card or in the install preview). Anything else in a skills folder, such as a
 * skill that arrived with a cloned repository, is listed but never given to the model.
 */
export interface LockEntry {
  scope: SkillScope;
  /** For project skills: which workspace (a short hash of its path). Project skills of another workspace do not count. */
  project?: string;
  name: string;
  hash: string;
  source: SkillSource;
  installedAt: number;
  updatedAt: number;
  enabled: boolean;
  /** `metadata.version` of the installed SKILL.md, if it had one. */
  version?: string;
  /**
   * The owner let this skill's scripts run in the Docker sandbox without a question each time. Only ever true for a
   * skill whose scripts passed the install-time check (skill-scan.ts); it goes with the hash, so a change turns it off.
   */
  autoRun?: boolean;
}

interface LockFile {
  version: 1;
  skills: LockEntry[];
}

/** The trust file. It lives in the config directory, outside every workspace, so a repository cannot trust itself. */
export class SkillLock {
  readonly file: string;
  constructor(file: string) {
    this.file = file;
  }

  /** False until the first entry is written (used to adopt skills that existed before this file did). */
  exists(): boolean {
    return existsSync(this.file);
  }

  entries(): LockEntry[] {
    if (!existsSync(this.file)) return [];
    try {
      const j = JSON.parse(readFileSync(this.file, "utf8")) as LockFile;
      if (j?.version !== 1 || !Array.isArray(j.skills)) return [];
      return j.skills.filter((e) => e && typeof e.name === "string" && typeof e.hash === "string" && (e.scope === "user" || e.scope === "project") && e.source && typeof e.source.type === "string");
    } catch {
      return [];
    }
  }

  get(scope: SkillScope, name: string, project?: string): LockEntry | undefined {
    return this.entries().find((e) => e.scope === scope && e.name === name && (scope === "user" || e.project === project));
  }

  set(entry: LockEntry): void {
    const rest = this.entries().filter((e) => !(e.scope === entry.scope && e.name === entry.name && (entry.scope === "user" || e.project === entry.project)));
    this.save([...rest, entry].sort((a, b) => `${a.scope}/${a.project ?? ""}/${a.name}`.localeCompare(`${b.scope}/${b.project ?? ""}/${b.name}`)));
  }

  remove(scope: SkillScope, name: string, project?: string): void {
    if (!this.exists()) return;
    this.save(this.entries().filter((e) => !(e.scope === scope && e.name === name && (scope === "user" || e.project === project))));
  }

  private save(skills: LockEntry[]): void {
    mkdirSync(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp-${process.pid}`;
    writeFileSync(tmp, JSON.stringify({ version: 1, skills } satisfies LockFile, null, 2), { mode: 0o600 });
    renameSync(tmp, this.file);
  }
}
