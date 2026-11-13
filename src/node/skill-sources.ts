import { lstatSync, readdirSync, readFileSync, realpathSync, statSync } from "node:fs";
import { isAbsolute, join, parse as parsePath, resolve } from "node:path";
import { composeSkillMd, parseSkillMd, skillFilePathOk, skillLinkedFiles, skillProblems, SKILL_DIRS, type SkillSpec } from "../core/skills.ts";
import { skillHash } from "./skill-hash.ts";
import type { SkillSource } from "./skill-lock.ts";
import { INSTALL_LIMITS, SkillError, SKILL_MAX_BYTES } from "./skills.ts";
import { readTarGz, type Snapshot } from "./tar.ts";

/** What may be downloaded: the packed repository, and what it may unpack to. Same limits as vercel-labs/skills. */
export const DOWNLOAD_LIMITS = { downloadBytes: 10 * 1024 * 1024, unpackedBytes: 25 * 1024 * 1024, files: 1000 };

export type ParsedSource =
  | { type: "github"; owner: string; repo: string; ref: string | null; path: string }
  | { type: "local"; path: string };

const OWNER = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/;
const REPO = /^[A-Za-z0-9._-]{1,100}$/;
const REF = /^[A-Za-z0-9._/-]{1,100}$/;
const PATH_PART = /^[A-Za-z0-9._ -]{1,100}$/;

/**
 * Where a skill comes from, as an owner would type it:
 *  - `owner/repo`, `owner/repo@ref`, `owner/repo/path/to/skill@ref` (GitHub);
 *  - a github.com URL, optionally `…/tree/<ref>/<path>`;
 *  - a path on this machine starting with `/`, `./`, `../`, `~` or a drive letter.
 * Only github.com is fetched; there is no other host, no token and no private repository.
 */
export function parseSkillSource(input: string): ParsedSource {
  const s = input.trim();
  if (s.length === 0 || s.length > 300 || /[\u0000-\u001f]/.test(s)) throw new SkillError(400, "the source is empty or not text");
  if (/^(\/|\.\.?[\\/]|~[\\/]?|[A-Za-z]:[\\/])/.test(s)) return { type: "local", path: s };
  let rest: string;
  let ref: string | null = null;
  const url = /^https?:\/\//i.test(s) ? safeUrl(s) : null;
  if (url) {
    if (url.hostname.toLowerCase() !== "github.com" || url.username || url.password) throw new SkillError(400, "only github.com is supported as a remote source");
    rest = url.pathname.replace(/^\/+|\/+$/g, "").replace(/\.git$/, "");
    const m = /^([^/]+)\/([^/]+)\/tree\/([^/]+)(?:\/(.*))?$/.exec(rest);
    if (m) {
      ref = decodeURIComponent(m[3]!);
      rest = `${m[1]}/${m[2]}${m[4] ? `/${m[4]}` : ""}`;
    }
  } else {
    rest = s.replace(/\.git$/, "");
    const at = rest.lastIndexOf("@");
    if (at > 0) {
      ref = rest.slice(at + 1);
      rest = rest.slice(0, at);
    }
  }
  const parts = rest.split("/").filter((x) => x.length > 0);
  if (parts.length < 2) throw new SkillError(400, "write a GitHub source as owner/repo (for example owner/repo@v1.2.0)");
  const [owner, repo, ...path] = parts as [string, string, ...string[]];
  if (!OWNER.test(owner) || !REPO.test(repo) || repo === "." || repo === "..") throw new SkillError(400, "not a GitHub owner/repo");
  if (ref !== null && (!REF.test(ref) || ref.includes("..") || ref.startsWith("/") || ref.endsWith("/"))) throw new SkillError(400, "not a valid branch, tag or commit");
  for (const p of path) if (!PATH_PART.test(p) || p === "." || p === "..") throw new SkillError(400, "the path inside the repository has characters that are not allowed");
  return { type: "github", owner, repo, ref, path: path.join("/") };
}

function safeUrl(s: string): URL | null {
  try {
    return new URL(s);
  } catch {
    return null;
  }
}

export type FetchLike = (url: string, init?: { redirect?: "manual" | "follow"; signal?: AbortSignal; headers?: Record<string, string> }) => Promise<Response>;

/**
 * Downloads a repository as `.tar.gz` from codeload.github.com. Only that host, over HTTPS, is ever contacted (a redirect
 * to anything else is refused). The download stops at [DOWNLOAD_LIMITS.downloadBytes].
 */
export async function fetchGithub(src: Extract<ParsedSource, { type: "github" }>, fetchImpl: FetchLike = fetch as unknown as FetchLike): Promise<Snapshot> {
  const ref = src.ref ?? "HEAD";
  let url = `https://codeload.github.com/${src.owner}/${src.repo}/tar.gz/${ref.split("/").map(encodeURIComponent).join("/")}`;
  const signal = AbortSignal.timeout(30_000);
  let res: Response | undefined;
  for (let hop = 0; hop < 4; hop++) {
    res = await fetchImpl(url, { redirect: "manual", signal, headers: { "user-agent": "aeon-skills", accept: "application/gzip" } });
    if (res.status >= 300 && res.status < 400) {
      const next = safeUrl(new URL(res.headers.get("location") ?? "", url).toString());
      if (!next || next.protocol !== "https:" || next.hostname !== "codeload.github.com") throw new SkillError(502, "GitHub redirected somewhere that is not allowed");
      url = next.toString();
      res = undefined;
      continue;
    }
    break;
  }
  if (!res) throw new SkillError(502, "too many redirects");
  if (res.status === 404) throw new SkillError(404, "that repository or branch was not found (private repositories are not supported)");
  if (!res.ok) throw new SkillError(502, `GitHub answered ${res.status}`);
  const declared = Number(res.headers.get("content-length") ?? 0);
  if (declared > DOWNLOAD_LIMITS.downloadBytes) throw new SkillError(413, "the repository is larger than 10 MB packed");
  const chunks: Buffer[] = [];
  let got = 0;
  const reader = res.body?.getReader();
  if (!reader) throw new SkillError(502, "GitHub sent no data");
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    got += value.length;
    if (got > DOWNLOAD_LIMITS.downloadBytes) {
      await reader.cancel();
      throw new SkillError(413, "the repository is larger than 10 MB packed");
    }
    chunks.push(Buffer.from(value));
  }
  try {
    return readTarGz(Buffer.concat(chunks), { maxBytes: DOWNLOAD_LIMITS.unpackedBytes, maxFiles: DOWNLOAD_LIMITS.files });
  } catch (e) {
    throw new SkillError(422, e instanceof Error ? e.message : "could not unpack the repository");
  }
}

const SKIP_DIRS = new Set([".git", "node_modules", "__pycache__", ".venv", ".idea", ".vscode"]);

/** A folder on this machine, read like an archive: regular files only, never through a link, with the same caps. */
export function readLocalFolder(path: string): Snapshot {
  const home = process.env.HOME ?? process.env.USERPROFILE ?? "";
  const expanded = path.startsWith("~") ? join(home, path.slice(1)) : path;
  const abs = resolve(expanded);
  if (parsePath(abs).root === abs) throw new SkillError(400, "that is a whole drive, not a skill folder");
  let real: string;
  try {
    real = realpathSync(abs);
    if (!statSync(real).isDirectory()) throw new Error("not a folder");
  } catch {
    throw new SkillError(404, "that folder does not exist on this machine");
  }
  if (parsePath(real).root === real) throw new SkillError(400, "that is a whole drive, not a skill folder");
  const files = new Map<string, Buffer>();
  const skipped: string[] = [];
  let total = 0;
  const walk = (dir: string, rel: string, depth: number) => {
    if (depth > 10) return;
    for (const n of readdirSync(dir).sort()) {
      const full = join(dir, n);
      const r = rel ? `${rel}/${n}` : n;
      const st = lstatSync(full);
      if (st.isSymbolicLink()) {
        skipped.push(`${r.slice(0, 80)} (link)`);
        continue;
      }
      if (st.isDirectory()) {
        if (!SKIP_DIRS.has(n)) walk(full, r, depth + 1);
        continue;
      }
      if (!st.isFile()) continue;
      total += st.size;
      if (total > DOWNLOAD_LIMITS.unpackedBytes) throw new SkillError(413, "the folder is larger than 25 MB");
      if (files.size >= DOWNLOAD_LIMITS.files) throw new SkillError(413, "the folder holds more than 1000 files");
      files.set(r, readFileSync(full));
    }
  };
  walk(real, "", 0);
  return { files, skipped };
}

/** A skill found in a snapshot, with everything the owner needs to decide about it. */
export interface Candidate {
  /** The name from SKILL.md; the skill is installed under it. */
  name: string;
  /** Folder in the source (empty for the repository root). */
  dir: string;
  description: string;
  spec: SkillSpec;
  problems: string[];
  warnings: string[];
  /** SKILL.md plus supporting files that will be kept. */
  entries: { path: string; bytes: Buffer }[];
  /** Files in the folder that Aeon does not use and will not copy. */
  ignored: string[];
  scripts: boolean;
  hash: string;
  bytes: number;
}

const ROOTS = ["skills", ".agents/skills", ".claude/skills", ".opencode/skills"];

/** Where SKILL.md files may be: the root, the usual skills folders, or anywhere up to three folders down. */
function skillDirs(files: Map<string, Buffer>, sub: string): string[] {
  const dirs = new Set<string>();
  for (const p of files.keys()) {
    if (p !== "SKILL.md" && !p.endsWith("/SKILL.md")) continue;
    const dir = p === "SKILL.md" ? "" : p.slice(0, -"/SKILL.md".length);
    const depth = dir === "" ? 0 : dir.split("/").length;
    if (dir.split("/").some((x) => SKIP_DIRS.has(x))) continue;
    if (sub) {
      if (dir === sub || dir.startsWith(`${sub}/`)) dirs.add(dir);
      continue;
    }
    if (depth <= 3 || ROOTS.some((r) => dir.startsWith(`${r}/`) && dir.split("/").length === r.split("/").length + 1)) dirs.add(dir);
  }
  return [...dirs].sort();
}

/** Finds every skill in a snapshot (optionally only below [sub]) and checks each against the spec. */
export function discoverSkills(snap: Snapshot, sub = ""): Candidate[] {
  const out: Candidate[] = [];
  for (const dir of skillDirs(snap.files, sub)) {
    const prefix = dir === "" ? "" : `${dir}/`;
    const md = snap.files.get(`${prefix}SKILL.md`)!;
    const problemsExtra: string[] = [];
    if (md.length > SKILL_MAX_BYTES) problemsExtra.push(`SKILL.md is larger than ${SKILL_MAX_BYTES} bytes`);
    const text = md.toString("utf8");
    const { spec, problems, warnings } = parseSkillMd(text);
    const full: SkillSpec = { name: spec.name ?? "", description: spec.description ?? "", body: spec.body, ...(spec.license !== undefined ? { license: spec.license } : {}), ...(spec.compatibility !== undefined ? { compatibility: spec.compatibility } : {}), ...(spec.metadata ? { metadata: spec.metadata } : {}), ...(spec.allowedTools !== undefined ? { allowedTools: spec.allowedTools } : {}) };
    // The folder it lives in does not have to match (a repository root cannot): it is installed under its name.
    const all = [...new Set([...problems, ...skillProblems(full), ...problemsExtra])];
    const entries: { path: string; bytes: Buffer }[] = [{ path: "SKILL.md", bytes: md }];
    const ignored: string[] = [];
    let bytes = md.length;
    for (const [p, b] of snap.files) {
      if (!p.startsWith(prefix) || p === `${prefix}SKILL.md`) continue;
      const rel = p.slice(prefix.length);
      if (!SKILL_DIRS.some((d) => rel.startsWith(`${d}/`))) {
        if (!rel.includes("/") || dir !== "") ignored.push(rel);
        continue;
      }
      if (!skillFilePathOk(rel)) {
        ignored.push(`${rel} (name or depth not allowed)`);
        continue;
      }
      if (b.length > INSTALL_LIMITS.fileBytes) {
        all.push(`${rel} is larger than ${INSTALL_LIMITS.fileBytes / 1_000_000} MB`);
        continue;
      }
      entries.push({ path: rel, bytes: b });
      bytes += b.length;
    }
    if (entries.length > INSTALL_LIMITS.files) all.push(`more than ${INSTALL_LIMITS.files} files`);
    if (bytes > INSTALL_LIMITS.totalBytes) all.push(`larger than ${INSTALL_LIMITS.totalBytes / 1_000_000} MB`);
    const have = new Set(entries.map((e) => e.path));
    const dangling = skillLinkedFiles(full.body).filter((f) => !have.has(f)).map((f) => `the instructions point to ${f}, which is not in the skill`);
    const folder = dir === "" ? "" : dir.split("/").pop()!;
    const where = folder && full.name && folder !== full.name ? [`the folder is called "${folder}" but the skill is named "${full.name}": it will be installed as "${full.name}"`] : [];
    out.push({
      name: full.name || folder, dir, description: full.description, spec: full, problems: all, warnings: [...warnings, ...dangling, ...where],
      entries, ignored: ignored.sort().slice(0, 30), scripts: entries.some((e) => e.path.startsWith("scripts/")), hash: skillHash(entries), bytes,
    });
  }
  return out;
}

/** The same skill under another name (the owner's choice when two skills share one): SKILL.md is rewritten, files stay. */
export function renamed(c: Candidate, name: string): Candidate {
  const spec = { ...c.spec, name };
  const md = Buffer.from(composeSkillMd(spec), "utf8");
  const entries = c.entries.map((e) => (e.path === "SKILL.md" ? { path: e.path, bytes: md } : e));
  return { ...c, name, spec, entries, problems: skillProblems(spec), hash: skillHash(entries) };
}

export function sourceRecord(p: ParsedSource, commit: string | undefined, dir: string): SkillSource {
  if (p.type === "local") return { type: "local", path: isAbsolute(p.path) ? p.path : resolve(p.path) };
  return { type: "github", owner: p.owner, repo: p.repo, ref: p.ref, path: dir, ...(commit ? { commit } : {}) };
}
