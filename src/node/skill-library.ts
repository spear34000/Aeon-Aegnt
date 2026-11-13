import { createHash, randomBytes } from "node:crypto";
import { existsSync, mkdirSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { skillNameProblem, type SkillSpec } from "../core/skills.ts";
import { diffEntries, skillHash } from "./skill-hash.ts";
import { SkillLock, type LockEntry, type SkillScope, type SkillSource } from "./skill-lock.ts";
import { discoverSkills, fetchGithub, parseSkillSource, readLocalFolder, renamed, sourceRecord, type Candidate, type FetchLike, type ParsedSource } from "./skill-sources.ts";
import { SkillError, SkillStore, type SkillFull, type SkillSummary } from "./skills.ts";
import type { Snapshot } from "./tar.ts";

/** `tracked`: the owner approved exactly this content. `modified`: it was approved but changed since. `untracked`: never approved. */
export type SkillStatus = "tracked" | "modified" | "untracked";

export interface LibrarySkill extends SkillSummary {
  scope: SkillScope;
  status: SkillStatus;
  enabled: boolean;
  /** Offered to the model: trusted, switched on, valid, and not hidden by a skill of the same name in a winning scope. */
  active: boolean;
  /** Why it is not active, in a sentence (empty when it is). */
  inactiveBecause: string;
  shadowedBy?: SkillScope;
  source?: SkillSource;
  hash: string;
  version?: string;
}

export interface LibraryOptions {
  /** `<config dir>/skills`: the owner's skills, on every project. */
  userDir: string;
  /** `<workspace>/.agents/skills`: skills of this project (the folder other agent clients use too). */
  projectDir?: string;
  workspace?: string;
  /** `<config dir>/skills-lock.json`: which skills are trusted. Outside the workspace on purpose. */
  lockFile: string;
  fetchImpl?: FetchLike;
  now?: () => number;
}

export interface CandidateView {
  name: string;
  dir: string;
  description: string;
  problems: string[];
  warnings: string[];
  files: { path: string; size: number }[];
  scripts: boolean;
  ignored: string[];
  hash: string;
  bytes: number;
  /** The whole SKILL.md, so the owner reads what the model will be told. */
  skillMd: string;
  /** The text of scripts, so the owner reads what could be run (each cut at 6000 characters). */
  scriptTexts: { path: string; text: string; cut: boolean }[];
  /** The same name is already installed in these scopes. */
  existing: { scope: SkillScope; status: SkillStatus; source?: SkillSource }[];
}

export interface PreviewView {
  id: string;
  source: string;
  commit?: string;
  skipped: string[];
  candidates: CandidateView[];
}

interface Staged {
  id: string;
  at: number;
  parsed: ParsedSource;
  commit?: string;
  candidates: Candidate[];
}

export interface UpdateStatus {
  scope: SkillScope;
  name: string;
  status: "up-to-date" | "update-available" | "modified" | "missing" | "not-remote" | "error";
  message?: string;
  remoteCommit?: string;
}

const PREVIEW_TTL_MS = 15 * 60_000;

function sourceText(s: SkillSource): string | null {
  return s.type === "github" ? `${s.owner}/${s.repo}${s.path ? `/${s.path}` : ""}${s.ref ? `@${s.ref}` : ""}` : null;
}

/**
 * All skills the agent can use, in one place. Two scopes: the owner's (`<config>/skills`) and the project's
 * (`<workspace>/.agents/skills`); a project skill wins over an owner skill of the same name. A skill is offered to the model
 * only when the lock file says the owner approved its exact content and it is switched on, so something that merely
 * appears in a folder (for example with a cloned repository) is listed but never trusted. Installing, updating and
 * trusting are the owner's acts (the server only allows them to an admin device); the agent can only write to its
 * own scope through skill.create and skill.update, which always ask. Nothing here runs a script.
 */
export class SkillLibrary {
  readonly user: SkillStore;
  readonly project: SkillStore | null;
  private readonly lock: SkillLock;
  private readonly fetchImpl: FetchLike | undefined;
  private readonly now: () => number;
  private readonly staged = new Map<string, Staged>();
  private readonly projectKey: string | undefined;

  constructor(o: LibraryOptions) {
    this.user = new SkillStore(o.userDir);
    this.project = o.projectDir ? new SkillStore(o.projectDir) : null;
    this.lock = new SkillLock(o.lockFile);
    this.fetchImpl = o.fetchImpl;
    this.now = o.now ?? Date.now;
    let key: string | undefined;
    if (o.workspace) {
      try {
        key = createHash("sha256").update(realpathSync(o.workspace)).digest("hex").slice(0, 16);
      } catch {
        key = createHash("sha256").update(o.workspace).digest("hex").slice(0, 16);
      }
    }
    this.projectKey = key;
  }

  get userDir(): string {
    return this.user.dir;
  }

  private store(scope: SkillScope): SkillStore {
    if (scope === "project") {
      if (!this.project) throw new SkillError(400, "this agent has no project folder for skills");
      return this.project;
    }
    return this.user;
  }

  private entry(scope: SkillScope, name: string): LockEntry | undefined {
    return this.lock.get(scope, name, this.projectKey);
  }

  private setEntry(scope: SkillScope, name: string, hash: string, source: SkillSource, version: string | undefined, enabled: boolean): void {
    const before = this.entry(scope, name);
    this.lock.set({
      scope, ...(scope === "project" && this.projectKey ? { project: this.projectKey } : {}), name, hash, source,
      installedAt: before?.installedAt ?? this.now(), updatedAt: this.now(), enabled, ...(version ? { version } : {}),
    });
  }

  /** Skills that were in the owner's folder before the lock file existed are adopted once: that folder has only ever held what the owner approved. */
  private adoptExisting(): void {
    if (this.lock.exists()) return;
    for (const s of this.user.list()) {
      const entries = this.user.entries(s.folder);
      if (entries) this.setEntry("user", s.folder, skillHash(entries), { type: "legacy" }, s.metadata?.version, true);
    }
  }

  /** Every skill in every scope, with its trust state. */
  list(): LibrarySkill[] {
    this.adoptExisting();
    const out: LibrarySkill[] = [];
    const scopes: [SkillScope, SkillStore | null][] = [["project", this.project], ["user", this.user]];
    for (const [scope, store] of scopes) {
      if (!store) continue;
      for (const s of store.list()) {
        const entries = store.entries(s.folder);
        const hash = entries ? skillHash(entries) : "";
        const e = this.entry(scope, s.folder);
        const status: SkillStatus = !e ? "untracked" : e.hash === hash ? "tracked" : "modified";
        const enabled = status === "tracked" && e!.enabled;
        const because = s.problems.length > 0 ? "it does not follow the Agent Skills format" : status === "untracked" ? "the owner has not approved it yet" : status === "modified" ? "it changed after the owner approved it" : !e!.enabled ? "it is switched off" : "";
        out.push({ ...s, scope, status, enabled: e?.enabled ?? false, active: because === "" && enabled, inactiveBecause: because, hash, ...(e ? { source: e.source } : {}), ...(e?.version ? { version: e.version } : {}) });
      }
    }
    // The first active one of a name (project before user) is the one used; a lower active one is hidden by it.
    const winners = new Map<string, SkillScope>();
    for (const s of out) {
      if (!s.active) continue;
      const w = winners.get(s.name);
      if (!w) winners.set(s.name, s.scope);
      else {
        s.active = false;
        s.shadowedBy = w;
        s.inactiveBecause = `a ${w} skill with the same name is used instead`;
      }
    }
    return out;
  }

  /** What the model is told exists: names and descriptions of active skills. */
  catalog(): { name: string; description: string }[] {
    return this.list().filter((s) => s.active).map((s) => ({ name: s.name, description: s.description }));
  }

  get(scope: SkillScope, name: string): LibrarySkill | undefined {
    return this.list().find((s) => s.scope === scope && s.folder === name);
  }

  /** The skill that answers to [name] right now (active, in the winning scope), with its instructions. */
  open(name: string): { skill: LibrarySkill; full: SkillFull } | { problem: string } {
    const all = this.list().filter((s) => s.name === name || s.folder === name);
    const hit = all.find((s) => s.active);
    if (!hit) {
      const why = all[0];
      return { problem: why ? `skill "${name}" cannot be used: ${why.inactiveBecause || "it is not available"}` : `no skill named "${name.slice(0, 64)}". Skills: ${this.catalog().map((s) => s.name).join(", ") || "(none)"}` };
    }
    const full = this.store(hit.scope).read(hit.folder);
    return full ? { skill: hit, full } : { problem: `skill "${name}" could not be read` };
  }

  /** A supporting file of an active skill. The hash is checked again at this moment, so a changed file is not served. */
  openFile(skill: LibrarySkill, rel: string): { bytes: Buffer } | { problem: string } {
    const fresh = this.get(skill.scope, skill.folder);
    if (!fresh?.active) return { problem: `skill "${skill.name}" changed and cannot be used until the owner approves it again` };
    const bytes = this.store(skill.scope).readBytes(skill.folder, rel, 1_000_000);
    return bytes ? { bytes } : { problem: `no file "${rel.slice(0, 60)}" in skill "${skill.name}". Files: ${skill.files.join(", ") || "(none)"}` };
  }

  /** The verified content of an active skill (for running a script from a copy that is exactly what was approved). */
  verifiedEntries(skill: LibrarySkill): { path: string; bytes: Buffer }[] | { problem: string } {
    const entries = this.store(skill.scope).entries(skill.folder);
    const e = this.entry(skill.scope, skill.folder);
    if (!entries || !e || !e.enabled || skillHash(entries) !== e.hash) return { problem: `skill "${skill.name}" changed and cannot be used until the owner approves it again` };
    return entries;
  }

  /**
   * The agent's own write (after the owner approved it on the approval card): saved in the owner's scope. A new skill is
   * trusted as written. A change keeps the trust only if the skill was exactly what the owner approved before: the card
   * shows the SKILL.md and the files being written, not the files that stay, so if those had been changed the result is
   * saved but left unapproved (`trusted: false`) and the owner has to review it.
   */
  writeAsAgent(spec: SkillSpec, create: boolean, files: Record<string, string>): SkillFull & { trusted: boolean } {
    this.adoptExisting();
    const wasTracked = !create && this.get("user", spec.name)?.status === "tracked";
    const saved = this.user.write(spec, create, files);
    const before = this.entry("user", saved.folder);
    const trusted = create || wasTracked;
    if (trusted) this.setEntry("user", saved.folder, skillHash(this.user.entries(saved.folder) ?? []), before?.source ?? { type: "agent" }, saved.metadata?.version, before?.enabled ?? true);
    return { ...saved, trusted };
  }

  /** The owner's correction of a skill in the owner's scope. Trusted afterwards only under the same condition as the agent's change. */
  writeAsOwner(spec: SkillSpec): SkillFull & { trusted: boolean } {
    return this.writeAsAgent(spec, false, {});
  }

  remove(scope: SkillScope, name: string): boolean {
    const ok = this.store(scope).remove(name);
    if (ok) this.lock.remove(scope, name, this.projectKey);
    return ok;
  }

  /**
   * A supporting file of any skill in a folder, trusted or not, for the owner to read before approving it (references/,
   * assets/ and scripts/ only; the text is for a person to read and is never given to the model from here).
   */
  readSupportFile(scope: SkillScope, name: string, rel: string): { text: string; size: number } | { binary: true; size: number } | null {
    const bytes = this.store(scope).readBytes(name, rel, 1_000_000);
    if (!bytes) return null;
    return bytes.includes(0) ? { binary: true, size: bytes.length } : { text: bytes.toString("utf8"), size: bytes.length };
  }

  setEnabled(scope: SkillScope, name: string, enabled: boolean): LibrarySkill {
    const s = this.get(scope, name);
    if (!s) throw new SkillError(404, "skill not found");
    if (s.status !== "tracked") throw new SkillError(409, s.status === "modified" ? "this skill changed after you approved it: review it and approve it again first" : "review this skill and approve it first");
    this.setEntry(scope, name, s.hash, s.source ?? { type: "legacy" }, s.version, enabled);
    return this.get(scope, name)!;
  }

  /**
   * The owner has read this skill and accepts it. [expectedHash] is the hash of what they were shown: if the folder
   * changed since, nothing is approved (409), so what is trusted is always what was read.
   */
  trust(scope: SkillScope, name: string, expectedHash: string): LibrarySkill {
    const s = this.get(scope, name);
    if (!s) throw new SkillError(404, "skill not found");
    if (s.hash !== expectedHash) throw new SkillError(409, "the skill changed since you looked at it: look at it again");
    if (s.problems.length > 0) throw new SkillError(400, `it does not follow the Agent Skills format: ${s.problems.join("; ")}`);
    this.setEntry(scope, name, s.hash, s.source ?? { type: "local", path: this.store(scope).folderPath(name) }, s.version, true);
    return this.get(scope, name)!;
  }

  // ---- finding and installing skills from a folder or GitHub ----

  private prune(): void {
    const cutoff = this.now() - PREVIEW_TTL_MS;
    for (const [id, p] of this.staged) if (p.at < cutoff) this.staged.delete(id);
    while (this.staged.size > 5) this.staged.delete(this.staged.keys().next().value!);
  }

  private view(c: Candidate): CandidateView {
    const text = (b: Buffer, max: number) => ({ text: b.toString("utf8").slice(0, max), cut: b.length > max });
    return {
      name: c.name, dir: c.dir, description: c.description, problems: c.problems, warnings: c.warnings,
      files: c.entries.map((e) => ({ path: e.path, size: e.bytes.length })), scripts: c.scripts, ignored: c.ignored, hash: c.hash, bytes: c.bytes,
      skillMd: c.entries.find((e) => e.path === "SKILL.md")!.bytes.toString("utf8").slice(0, 100_000),
      scriptTexts: c.entries.filter((e) => e.path.startsWith("scripts/")).slice(0, 8).map((e) => ({ path: e.path, ...text(e.bytes, 6000) })),
      existing: this.list().filter((s) => s.folder === c.name).map((s) => ({ scope: s.scope, status: s.status, ...(s.source ? { source: s.source } : {}) })),
    };
  }

  /** Reads a source (a GitHub repository or a folder on this machine), finds the skills in it and keeps them for the owner to review. Installs nothing. */
  async inspect(input: string): Promise<PreviewView> {
    this.prune();
    const parsed = parseSkillSource(input);
    const snap: Snapshot = parsed.type === "github" ? await fetchGithub(parsed, this.fetchImpl) : readLocalFolder(parsed.path);
    const candidates = discoverSkills(snap, parsed.type === "github" ? parsed.path : "");
    if (candidates.length === 0) throw new SkillError(404, "no SKILL.md was found there (looked at the root, skills/, .agents/skills/, .claude/skills/ and up to three folders down)");
    const id = randomBytes(8).toString("hex");
    this.staged.set(id, { id, at: this.now(), parsed, ...(snap.commit ? { commit: snap.commit } : {}), candidates });
    return { id, source: parsed.type === "github" ? (sourceText({ type: "github", owner: parsed.owner, repo: parsed.repo, ref: parsed.ref, path: parsed.path }) ?? input) : parsed.path, ...(snap.commit ? { commit: snap.commit } : {}), skipped: snap.skipped.slice(0, 20), candidates: candidates.map((c) => this.view(c)) };
  }

  /** Installs one skill from a preview, in the owner's or the project's scope. */
  install(o: { previewId: string; name: string; scope: SkillScope; rename?: string; replace?: boolean }): LibrarySkill {
    this.prune();
    const p = this.staged.get(o.previewId);
    if (!p) throw new SkillError(404, "that preview expired: look at the source again");
    let c = p.candidates.find((x) => x.name === o.name);
    if (!c) throw new SkillError(404, "that skill is not in the preview");
    if (c.problems.length > 0) throw new SkillError(400, `it does not follow the Agent Skills format: ${c.problems.join("; ")}`);
    const store = this.store(o.scope);
    this.adoptExisting();
    const finalName = o.rename ?? c.name;
    const bad = skillNameProblem(finalName);
    if (bad) throw new SkillError(400, bad);
    if (finalName !== c.name) c = renamed(c, finalName);
    const exists = existsSync(store.folderPath(finalName));
    if (exists && !o.replace) throw new SkillError(409, `a skill named "${finalName}" is already installed: choose another name or replace it`);
    const stageRoot = join(store.dir, `.staging-${p.id}-${randomBytes(3).toString("hex")}`);
    const staged = join(stageRoot, finalName);
    try {
      for (const e of c.entries) {
        const target = join(staged, ...e.path.split("/"));
        mkdirSync(dirname(target), { recursive: true });
        writeFileSync(target, e.bytes);
      }
      store.place(finalName, staged, o.replace === true);
    } finally {
      rmSync(stageRoot, { recursive: true, force: true });
    }
    const back = store.entries(finalName);
    if (!back || skillHash(back) !== c.hash) {
      store.remove(finalName);
      throw new SkillError(500, "the installed files do not match what was previewed; nothing was kept");
    }
    this.setEntry(o.scope, finalName, c.hash, sourceRecord(p.parsed, p.commit, c.dir), c.spec.metadata?.version, true);
    return this.get(o.scope, finalName)!;
  }

  /** For every skill that came from GitHub: is there a newer commit, and was the local copy changed? Needs the network. */
  async check(): Promise<UpdateStatus[]> {
    const out: UpdateStatus[] = [];
    const snaps = new Map<string, Snapshot | string>();
    for (const s of this.list()) {
      const src = s.source;
      const base = { scope: s.scope, name: s.folder };
      if (!src || src.type !== "github") {
        if (s.status !== "tracked" && s.status !== "modified") continue;
        out.push({ ...base, status: s.status === "modified" ? "modified" : "not-remote" });
        continue;
      }
      if (s.status === "modified") {
        out.push({ ...base, status: "modified" });
        continue;
      }
      const key = `${src.owner}/${src.repo}@${src.ref ?? ""}`;
      if (!snaps.has(key)) {
        try {
          snaps.set(key, await fetchGithub({ type: "github", owner: src.owner, repo: src.repo, ref: src.ref, path: "" }, this.fetchImpl));
        } catch (e) {
          snaps.set(key, e instanceof Error ? e.message : "could not reach GitHub");
        }
      }
      const snap = snaps.get(key)!;
      if (typeof snap === "string") {
        out.push({ ...base, status: "error", message: snap });
        continue;
      }
      const found = discoverSkills(snap, src.path).find((c) => c.dir === src.path);
      if (!found) out.push({ ...base, status: "missing", message: "the skill is no longer in the repository" });
      else {
        const newer = snap.commit && src.commit ? snap.commit !== src.commit : found.hash !== s.hash;
        out.push({ ...base, status: newer && found.hash !== s.hash ? "update-available" : "up-to-date", ...(snap.commit ? { remoteCommit: snap.commit } : {}) });
      }
    }
    return out;
  }

  /** Looks at the source of an installed skill again. Returns a preview to install over the old one, and what changes. */
  async previewUpdate(scope: SkillScope, name: string): Promise<{ preview: PreviewView; install: { name: string; rename?: string }; diff: ReturnType<typeof diffEntries> }> {
    const s = this.get(scope, name);
    if (!s) throw new SkillError(404, "skill not found");
    const src = s.source;
    if (!src || (src.type !== "github" && src.type !== "local")) throw new SkillError(400, "this skill did not come from a source that can be looked at again");
    const preview = await this.inspect(src.type === "github" ? (sourceText(src) ?? "") : src.path);
    const staged = this.staged.get(preview.id)!;
    const c = src.type === "github" ? staged.candidates.find((x) => x.dir === src.path) : staged.candidates.find((x) => x.name === name) ?? staged.candidates[0];
    if (!c) throw new SkillError(404, "the skill is no longer at its source");
    const mine = this.store(scope).entries(name) ?? [];
    return { preview: { ...preview, candidates: preview.candidates.filter((x) => x.dir === c.dir) }, install: { name: c.name, ...(c.name !== name ? { rename: name } : {}) }, diff: diffEntries(mine, c.entries) };
  }
}
