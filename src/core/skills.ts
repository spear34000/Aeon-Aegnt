/**
 * Agent Skills (https://agentskills.io/specification): a skill is a folder with a SKILL.md whose YAML front matter
 * holds `name` (must equal the folder name), `description` and optionally `license`, `compatibility`, `metadata` and
 * `allowed-tools`, followed by Markdown instructions. Optional `references/` and `assets/` hold files read on demand.
 * This module is pure text handling: reading, writing and checking SKILL.md. It never touches the disk.
 */

export interface SkillSpec {
  name: string;
  description: string;
  license?: string;
  compatibility?: string;
  /** String keys to string values (the spec leaves the keys to the client). */
  metadata?: Record<string, string>;
  /** Experimental in the spec. Aeon reads and keeps it but never lets it widen what needs approval. */
  allowedTools?: string;
  /** Everything after the front matter. */
  body: string;
}

export const SKILL_NAME_MAX = 64;
export const SKILL_DESCRIPTION_MAX = 1024;
export const SKILL_COMPATIBILITY_MAX = 500;
export const SKILL_LICENSE_MAX = 200;
export const SKILL_METADATA_MAX_ENTRIES = 20;
export const SKILL_FILE_MAX_BYTES = 100_000;

/** The spec: lower-case letters, digits and hyphens, no leading, trailing or doubled hyphen, at most 64 characters. */
export function skillNameProblem(name: string): string | null {
  if (name.length === 0) return "name is empty";
  if (name.length > SKILL_NAME_MAX) return `name is longer than ${SKILL_NAME_MAX} characters`;
  if (!/^[a-z0-9-]+$/.test(name)) return "name may only contain lower-case letters (a-z), digits and hyphens";
  if (name.startsWith("-") || name.endsWith("-")) return "name must not start or end with a hyphen";
  if (name.includes("--")) return "name must not contain two hyphens in a row";
  return null;
}

/** Everything wrong with a skill, as sentences a model or a person can act on. An empty list means it follows the spec. */
export function skillProblems(spec: SkillSpec, folder?: string): string[] {
  const out: string[] = [];
  const n = skillNameProblem(spec.name);
  if (n) out.push(n);
  else if (folder !== undefined && spec.name !== folder) out.push(`name "${spec.name}" must match the folder name "${folder}"`);
  const d = spec.description.trim();
  if (d.length === 0) out.push("description is empty (say what the skill does and when to use it)");
  if (d.length > SKILL_DESCRIPTION_MAX) out.push(`description is longer than ${SKILL_DESCRIPTION_MAX} characters`);
  if (spec.compatibility !== undefined && (spec.compatibility.length === 0 || spec.compatibility.length > SKILL_COMPATIBILITY_MAX)) out.push(`compatibility must be 1-${SKILL_COMPATIBILITY_MAX} characters`);
  if (spec.license !== undefined && (spec.license.length === 0 || spec.license.length > SKILL_LICENSE_MAX)) out.push(`license must be 1-${SKILL_LICENSE_MAX} characters`);
  if (spec.metadata) {
    const entries = Object.entries(spec.metadata);
    if (entries.length > SKILL_METADATA_MAX_ENTRIES) out.push(`metadata has more than ${SKILL_METADATA_MAX_ENTRIES} entries`);
    for (const [k, v] of entries) {
      if (!/^[A-Za-z0-9_.-]{1,64}$/.test(k)) out.push(`metadata key "${k.slice(0, 20)}" is not a plain key`);
      if (typeof v !== "string" || v.length > 200 || /[\u0000-\u001f]/.test(v)) out.push(`metadata value for "${k.slice(0, 20)}" must be a single line of at most 200 characters`);
    }
  }
  return out;
}

const KEY = /^([A-Za-z][A-Za-z0-9_-]*):(?:[ \t]+(.*))?$/;

function unquote(raw: string): string | null {
  const v = raw.trim();
  if (v.startsWith('"')) {
    if (!v.endsWith('"') || v.length < 2) return null;
    try {
      return JSON.parse(v) as string;
    } catch {
      return null;
    }
  }
  if (v.startsWith("'")) {
    if (!v.endsWith("'") || v.length < 2) return null;
    return v.slice(1, -1).replace(/''/g, "'");
  }
  return v;
}

function indentOf(line: string): number {
  return /^[ ]*/.exec(line)![0].length;
}

/** A block scalar (`|` or `>`) starting after line [i]; returns the text and the index of the first line after it. */
function blockScalar(lines: string[], i: number, style: string): { text: string; next: number } {
  const rows: string[] = [];
  let j = i + 1;
  while (j < lines.length && (lines[j]!.trim() === "" || indentOf(lines[j]!) > 0)) {
    rows.push(lines[j]!);
    j++;
  }
  const min = Math.min(...rows.filter((r) => r.trim() !== "").map(indentOf), Infinity);
  const cut = rows.map((r) => (r.trim() === "" ? "" : r.slice(Number.isFinite(min) ? min : 0)));
  while (cut.length > 0 && cut[cut.length - 1] === "") cut.pop();
  if (style.startsWith("|")) return { text: cut.join("\n"), next: j };
  // Folded: lines join with a space, a blank line is a line break.
  let text = "";
  for (let k = 0; k < cut.length; k++) {
    const row = cut[k]!;
    if (row === "") text += "\n";
    else text += (text === "" || text.endsWith("\n") ? "" : " ") + row;
  }
  return { text, next: j };
}

/**
 * Reads a SKILL.md. Only the front matter shapes skills use are understood (plain, quoted and block scalars, and a
 * one-level `metadata` map); anything else is reported instead of guessed at. Without front matter the whole text is
 * the body and the problems say so.
 */
export function parseSkillMd(text: string): { spec: Partial<SkillSpec> & { body: string }; problems: string[]; warnings: string[] } {
  const problems: string[] = [];
  /** Things that do not break the skill: a field this client does not know is ignored, and said so. */
  const warnings: string[] = [];
  const m = /^---\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)([\s\S]*)$/.exec(text.replace(/^﻿/, ""));
  if (!m) return { spec: { body: text }, problems: ["SKILL.md has no front matter (--- name and description ---)"], warnings };
  const lines = m[1]!.split(/\r?\n/);
  const spec: Partial<SkillSpec> & { body: string } = { body: m[2]!.replace(/^\r?\n/, "") };
  const known = new Set(["name", "description", "license", "compatibility", "metadata", "allowed-tools"]);
  for (let i = 0; i < lines.length; ) {
    const line = lines[i]!;
    if (line.trim() === "" || line.trimStart().startsWith("#")) {
      i++;
      continue;
    }
    const km = indentOf(line) === 0 ? KEY.exec(line) : null;
    if (!km) {
      problems.push(`front matter line ${i + 1} is not "key: value"`);
      i++;
      continue;
    }
    const key = km[1]!;
    const rest = (km[2] ?? "").trim();
    let value: string | null = null;
    let next = i + 1;
    if (key === "metadata" && rest === "") {
      const map: Record<string, string> = {};
      while (next < lines.length && (lines[next]!.trim() === "" || indentOf(lines[next]!) > 0)) {
        const row = lines[next]!;
        next++;
        if (row.trim() === "" || row.trim().startsWith("#")) continue;
        const mm = /^\s+([A-Za-z0-9_.-]+):[ \t]*(.*)$/.exec(row);
        const v = mm ? unquote(mm[2]!) : null;
        if (!mm || v === null) problems.push(`metadata line "${row.trim().slice(0, 40)}" is not "key: value"`);
        else map[mm[1]!] = v;
      }
      spec.metadata = map;
      i = next;
      continue;
    }
    if (/^[|>][+-]?\d*$/.test(rest)) {
      const b = blockScalar(lines, i, rest);
      value = b.text;
      next = b.next;
    } else if (rest === "") {
      // A plain scalar continued on indented lines.
      const cont: string[] = [];
      while (next < lines.length && lines[next]!.trim() !== "" && indentOf(lines[next]!) > 0) cont.push(lines[next++]!.trim());
      value = cont.join(" ");
    } else {
      value = unquote(rest);
      if (value === null) problems.push(`"${key}" is not valid text`);
    }
    i = next;
    if (!known.has(key)) {
      warnings.push(`front matter field "${key.slice(0, 40)}" is not part of the Agent Skills spec and is ignored`);
      continue;
    }
    if (value === null) continue;
    if (key === "name") spec.name = value;
    else if (key === "description") spec.description = value;
    else if (key === "license") spec.license = value;
    else if (key === "compatibility") spec.compatibility = value;
    else if (key === "allowed-tools") spec.allowedTools = value;
  }
  if (spec.name === undefined) problems.push("front matter has no name");
  if (spec.description === undefined) problems.push("front matter has no description");
  return { spec, problems, warnings };
}

/** Folders a skill may keep files in (the spec's optional directories). */
export const SKILL_DIRS = ["references", "assets", "scripts"] as const;

/**
 * A supporting file of a skill: `references/…`, `assets/…` or `scripts/…`, up to three levels down, names made of plain
 * characters. This is the only shape of path the store reads, writes or hashes, so no name can point outside the skill.
 */
export function skillFilePathOk(rel: string): boolean {
  return /^(references|assets|scripts)(\/[A-Za-z0-9][A-Za-z0-9._-]{0,63}){1,3}$/.test(rel);
}

/**
 * Files the instructions point to (Markdown links and bare `references/x`, `assets/x`, `scripts/x` paths), relative to
 * the skill folder. Web links, anchors and anything climbing out of the folder are left out. Used to warn about a
 * reference that does not exist.
 */
export function skillLinkedFiles(body: string): string[] {
  const out = new Set<string>();
  const take = (raw: string) => {
    let p = raw.trim().replace(/^<|>$/g, "").replace(/^\.\//, "").split(/[#?]/)[0]!;
    if (!p || /^[a-z][a-z0-9+.-]*:/i.test(p) || p.startsWith("/") || p.split("/").includes("..")) return;
    if (skillFilePathOk(p)) out.add(p);
  };
  for (const m of body.matchAll(/\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) take(m[1]!);
  for (const m of body.matchAll(/(?:^|[\s`(])((?:references|assets|scripts)\/[A-Za-z0-9._/-]+)/g)) take(m[1]!.replace(/[.,;:]+$/, ""));
  return [...out].sort();
}

function yamlString(v: string): string {
  const plain = /^[A-Za-z][A-Za-z0-9 .,()/+'-]*$/.test(v) && !/(^|\s)#|: |\s$/.test(v) && !/^(true|false|null|yes|no|on|off)$/i.test(v);
  return plain ? v : JSON.stringify(v);
}

/** The inverse of parseSkillMd. Front matter first, then the instructions exactly as given. */
export function composeSkillMd(s: SkillSpec): string {
  const fm = [`name: ${s.name}`, `description: ${yamlString(s.description.trim())}`];
  if (s.license !== undefined) fm.push(`license: ${yamlString(s.license)}`);
  if (s.compatibility !== undefined) fm.push(`compatibility: ${yamlString(s.compatibility)}`);
  if (s.metadata && Object.keys(s.metadata).length > 0) {
    fm.push("metadata:");
    for (const k of Object.keys(s.metadata).sort()) fm.push(`  ${k}: ${JSON.stringify(s.metadata[k])}`);
  }
  if (s.allowedTools !== undefined) fm.push(`allowed-tools: ${yamlString(s.allowedTools)}`);
  const body = s.body.replace(/\r\n/g, "\n");
  return `---\n${fm.join("\n")}\n---\n\n${body}${body.endsWith("\n") ? "" : "\n"}`;
}

/** Lines for the system prompt: only names and descriptions (the first level of progressive disclosure). */
export function skillCatalogLines(skills: { name: string; description: string }[], maxChars = 6000): string[] {
  if (skills.length === 0) return [];
  const clean = (s: string) => s.replace(/[\u0000-\u001f<>]/g, " ").replace(/\s+/g, " ").trim();
  const lines: string[] = [];
  let used = 0;
  for (const s of skills.slice(0, 60)) {
    const line = `- ${clean(s.name)}: ${clean(s.description).slice(0, 300)}`;
    if (used + line.length > maxChars) break;
    used += line.length;
    lines.push(line);
  }
  return [
    "Skills are instruction sets the owner approved. When one fits the goal, call skill.read with its name first and follow it.",
    "A skill never changes what needs approval. Available skills:",
    ...lines,
  ];
}
