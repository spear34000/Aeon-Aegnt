import type { McpServerConfig } from "./config.ts";
import { parseSkillMd } from "./skills.ts";
import type { Tool } from "./tools.ts";

/**
 * A "connection" is anything the agent can be extended with: Aeon's own tools, remote MCP servers
 * ("integration"), MCP servers that run as a local package ("mcp") and skill folders.
 * Nothing in this list is hard-coded: built-ins come from the live tool registry, added servers from
 * config.json, skills from the skills folder and installable entries from a registry at request time.
 */
export type ConnectionKind = "builtin" | "integration" | "mcp" | "skill";
/** connected: usable now. added: saved by the owner, not executed by Aeon yet. available: can be added. */
export type ConnectionState = "connected" | "added" | "available";

export interface Connection {
  id: string;
  kind: ConnectionKind;
  state: ConnectionState;
  title: string;
  description: string;
  publisher: string | null;
  version: string | null;
  websiteUrl: string | null;
  /** Remote endpoint for kind "integration". */
  remoteUrl: string | null;
  /** Package registry for kind "mcp" (npm, pypi, oci, ...). */
  runtime: string | null;
  /** Names of secrets (headers, environment variables) the entry asks for. */
  requires: string[];
  tools: string[];
  /** API path of the icon, or null when there is nothing to look up. */
  icon: string | null;
}

/** Where an icon may be found, in the order the resolver tries them. */
export interface IconPlan {
  title: string;
  /** Icons the publisher declared in its manifest. */
  urls: string[];
  /** Host of the publisher's website or endpoint. */
  host: string | null;
  githubOwner: string | null;
  /** An SVG logo to read the shapes from (provider logos). When set and the title is empty, nothing else is tried. */
  svg?: string | null;
}

export type IconPayload = { kind: "image"; mime: string; data: string } | { kind: "path"; d: string; color: string; rule?: "evenodd"; size?: number };

const PATH_DATA = /^[MmLlHhVvCcSsQqTtAaZz0-9eE\s.,+-]+$/;

/**
 * Reads a one-colour logo out of SVG text. Only what can be drawn faithfully as one filled shape is accepted:
 * a square canvas whose paint is `currentColor`, and nothing but `<path>` elements without strokes, transforms or
 * opacity. Everything else (the placeholder a catalogue serves for a missing logo draws with strokes) is refused, so
 * the caller shows a letter instead of a wrong picture. `size` is the side of the canvas the path is drawn on.
 */
export function logoFromSvg(text: string): { d: string; size: number; rule?: "evenodd" } | null {
  const root = /<svg\b[^>]*>/i.exec(text)?.[0];
  const box = root ? /\bviewBox="0 0 (\d{1,3}) (\d{1,3})"/.exec(root) : null;
  if (!root || !box || box[1] !== box[2] || Number(box[1]) < 8) return null;
  const size = Number(box[1]);
  const rootFill = /\sfill="([^"]*)"/.exec(root)?.[1];
  if (/<(?!\/?(?:svg|path|title)\b)[a-z]/i.test(text.replace(/<!--[\s\S]*?-->/g, ""))) return null;
  const tags = text.match(/<path\b[^>]*>/gi) ?? [];
  if (tags.length === 0 || tags.length > 12) return null;
  const parts: string[] = [];
  const rules = new Set<string>();
  for (const tag of tags) {
    if (/\s(?:stroke|transform|opacity|fill-opacity|stroke-width|style)\s*=/i.test(tag)) return null;
    if ((/\sfill="([^"]*)"/.exec(tag)?.[1] ?? rootFill) !== "currentColor") return null;
    const d = /\sd="([^"]+)"/.exec(tag)?.[1];
    if (!d || !PATH_DATA.test(d)) return null;
    rules.add(/\s(?:fill-rule|clip-rule)="evenodd"/.test(tag) ? "evenodd" : "nonzero");
    parts.push(d.replace(/\s+/g, " ").trim());
  }
  if (rules.size > 1) return null;
  const d = parts.join(" ");
  if (d.length > 16_000) return null;
  return rules.has("evenodd") ? { d, size, rule: "evenodd" } : { d, size };
}

type Json = Record<string, unknown>;
const obj = (v: unknown): Json | null => (v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Json) : null);
const arr = (v: unknown): Json[] => (Array.isArray(v) ? v.map(obj).filter((x): x is Json => x !== null) : []);
const str = (v: unknown, max = 300): string | null => (typeof v === "string" && v.trim() ? v.trim().slice(0, max) : null);

function https(v: unknown): string | null {
  const s = str(v, 500);
  if (!s) return null;
  try {
    const u = new URL(s);
    return u.protocol === "https:" && !u.username && !u.password ? u.toString() : null;
  } catch {
    return null;
  }
}

function host(url: string | null): string | null {
  if (!url) return null;
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return null;
  }
}

const blank = (): Omit<Connection, "id" | "kind" | "state" | "title"> => ({
  description: "",
  publisher: null,
  version: null,
  websiteUrl: null,
  remoteUrl: null,
  runtime: null,
  requires: [],
  tools: [],
  icon: null,
});

/** One entry per tool namespace ("fs", "http", ...), taken from the live registry. */
export function builtinConnections(tools: Pick<Tool, "name" | "description">[]): Connection[] {
  const groups = new Map<string, Pick<Tool, "name" | "description">[]>();
  for (const t of tools) {
    const g = t.name.split(".")[0]!;
    groups.set(g, [...(groups.get(g) ?? []), t]);
  }
  return [...groups].map(([g, ts]) => ({
    ...blank(),
    id: `builtin:${g}`,
    kind: "builtin",
    state: "connected",
    title: g,
    description: ts.map((t) => t.description).join(" ").slice(0, 300),
    publisher: "Aeon",
    tools: ts.map((t) => t.name),
  }));
}

export function mcpConfigConnection(s: McpServerConfig): { connection: Connection; plan: IconPlan } {
  const h = host(s.websiteUrl ?? null) ?? host(s.url);
  return {
    connection: {
      ...blank(),
      id: `mcp:${s.name}`,
      kind: "integration",
      state: "added",
      title: s.title ?? prettyName(s.name),
      publisher: publisherOf(s.name) ?? host(s.url),
      websiteUrl: s.websiteUrl ?? null,
      remoteUrl: s.url,
      requires: s.requires ?? [],
    },
    plan: { title: s.title ?? prettyName(s.name), urls: [], host: h, githubOwner: githubOwner(s.name, null) },
  };
}

const GENERIC = new Set(["mcp", "server", "mcp-server", "api", "remote"]);

/** "io.github.acme/naver-mail-mcp" -> "Naver Mail"; "com.notion/mcp" -> "Notion". */
export function prettyName(name: string): string {
  const [ns, local = ""] = name.split("/", 2) as [string, string?];
  const labels = ns.split(".");
  let base = local && !GENERIC.has(local.toLowerCase()) ? local : (labels[labels.length - 1] ?? name);
  if (labels[0] === "io" && labels[1] === "github" && (!local || GENERIC.has(local.toLowerCase()))) base = labels[2] ?? base;
  const words = base
    .replace(/(^|[-_.])(mcp|server)(?=$|[-_.])/gi, "$1")
    .split(/[-_.\s]+/)
    .filter(Boolean);
  return (words.length ? words : [base]).map((w) => w[0]!.toUpperCase() + w.slice(1)).join(" ");
}

/** Reverse-DNS namespace -> the domain or GitHub account that owns it. */
export function publisherOf(name: string): string | null {
  const ns = name.split("/")[0]!;
  const labels = ns.split(".").filter(Boolean);
  if (labels.length < 2) return null;
  if (labels[0] === "io" && labels[1] === "github") return labels[2] ? `github.com/${labels.slice(2).join(".")}` : null;
  return [...labels].reverse().join(".");
}

function githubOwner(name: string, repoUrl: string | null): string | null {
  const labels = name.split("/")[0]!.split(".");
  const fromNs = labels[0] === "io" && labels[1] === "github" ? labels.slice(2).join(".") : null;
  let fromRepo: string | null = null;
  if (repoUrl && host(repoUrl) === "github.com") fromRepo = new URL(repoUrl).pathname.split("/").filter(Boolean)[0] ?? null;
  const owner = fromNs ?? fromRepo;
  return owner && /^[A-Za-z0-9-]{1,39}$/.test(owner) ? owner : null;
}

/** Maps one entry of the official MCP registry (server.json) to a connection. Returns null for unusable entries. */
export function registryConnection(entry: unknown): { connection: Connection; plan: IconPlan } | null {
  const outer = obj(entry);
  const s = obj(outer?.server) ?? outer;
  const name = str(s?.name, 200);
  if (!s || !name || !/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(name)) return null;
  const official = obj(obj(outer?._meta)?.["io.modelcontextprotocol.registry/official"]);
  if (official && str(official.status) && official.status !== "active") return null;

  const remotes = arr(s.remotes).filter((r) => https(r.url));
  const packages = arr(s.packages);
  if (remotes.length === 0 && packages.length === 0) return null;
  const remote = remotes.find((r) => r.type === "streamable-http") ?? remotes[0];
  const pkg = packages[0];
  const need = (list: unknown) =>
    arr(list)
      .filter((x) => x.isRequired === true)
      .map((x) => str(x.name, 64))
      .filter((x): x is string => x !== null);
  const requires = remote ? need(remote.headers) : need(pkg?.environmentVariables);

  const website = https(s.websiteUrl);
  const repo = https(obj(s.repository)?.url);
  const title = str(s.title, 80) ?? prettyName(name);
  const publisher = publisherOf(name);
  const websiteHost = host(website);
  // A website on github.com would yield GitHub's own favicon, which says nothing about the server.
  const planHost = websiteHost && websiteHost !== "github.com" ? websiteHost : publisher && !publisher.startsWith("github.com/") ? publisher : null;
  return {
    connection: {
      ...blank(),
      id: `mcp:${name}`,
      kind: remote ? "integration" : "mcp",
      state: "available",
      title,
      description: str(s.description, 300) ?? "",
      publisher,
      version: str(s.version, 40),
      websiteUrl: website ?? repo,
      remoteUrl: remote ? https(remote.url) : null,
      runtime: remote ? null : str(pkg?.registryType, 20),
      requires: [...new Set(requires)].slice(0, 8),
    },
    plan: {
      title,
      urls: arr(s.icons)
        .map((i) => https(i.src))
        .filter((u): u is string => u !== null)
        .slice(0, 3),
      host: planHost,
      githubOwner: githubOwner(name, repo),
    },
  };
}

/** Higher is a better match for the search text. Entries published by the brand itself come first. */
export function relevance(c: Connection, q: string): number {
  const t = q.trim().toLowerCase();
  if (!t) return 0;
  let score = 0;
  const pub = (c.publisher ?? "").toLowerCase();
  if (pub.split(/[./]/).includes(t)) score += 4;
  const title = c.title.toLowerCase();
  if (title === t) score += 3;
  else if (title.startsWith(t)) score += 2;
  if (c.websiteUrl) score += 1;
  if (c.remoteUrl) score += 1;
  return score;
}

/** A skill folder as a connection: the spec's `name` and `description`. A folder that does not follow the spec still shows, under its folder name. */
export function skillConnection(folder: string, text: string): Connection {
  const { spec } = parseSkillMd(text);
  const clean = (v: string | undefined, max: number) => (v ?? "").replace(/[\u0000-\u001f]+/g, " ").trim().slice(0, max);
  return { ...blank(), id: `skill:${folder}`, kind: "skill", state: "connected", title: clean(spec.name, 80) || folder, description: clean(spec.description, 300) };
}

/** Same normalisation Simple Icons uses for its slugs. */
export function brandKey(title: string): string {
  return title
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/\+/g, "plus")
    .replace(/\./g, "dot")
    .replace(/&/g, "and")
    .replace(/[^a-z0-9]/g, "");
}

const STOP = new Set(["mcp", "server", "api", "for", "by", "the", "official", "remote", "agent", "ai", "tools", "tool", "integration"]);

/** Brand names lead a title ("Naver Mail", "Gmail MCP"), so only the whole title and its first words are tried. */
export function brandCandidates(title: string): string[] {
  const words = title
    .replace(/\(.*?\)/g, " ")
    .split(/[\s\-_/:,]+/)
    .filter((w) => w && !STOP.has(w.toLowerCase()));
  const out = [brandKey(words.join("")), brandKey(words.slice(0, 2).join("")), brandKey(words[0] ?? "")];
  return [...new Set(out.filter((s) => s.length >= 2))];
}

/** "mcp.shop.example.co.kr" -> "example.co.kr". A heuristic, not the public suffix list. */
export function siteDomain(hostname: string): string {
  const p = hostname.toLowerCase().split(".").filter(Boolean);
  if (p.length <= 2) return p.join(".");
  const second = p[p.length - 2]!;
  const n = p[p.length - 1]!.length === 2 && ["co", "or", "go", "ne", "ac", "re", "com", "net", "org", "edu", "gov"].includes(second) ? 3 : 2;
  return p.slice(-n).join(".");
}

/** Icon URLs declared by a page, best first (large PNG touch icons, then PNG, then ICO). SVG is skipped. */
export function iconLinks(html: string, base: string): string[] {
  const found: { url: string; score: number }[] = [];
  for (const tag of html.slice(0, 400_000).match(/<link\b[^>]*>/gi) ?? []) {
    const attr: Record<string, string> = {};
    for (const m of tag.matchAll(/([a-zA-Z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)) attr[m[1]!.toLowerCase()] = m[2] ?? m[3] ?? m[4] ?? "";
    const rel = (attr.rel ?? "").toLowerCase();
    if (!rel.includes("icon") || !attr.href) continue;
    let url: URL;
    try {
      url = new URL(attr.href, base);
    } catch {
      continue;
    }
    if (url.protocol !== "https:") continue;
    const path = url.pathname.toLowerCase();
    if (path.endsWith(".svg") || (attr.type ?? "").includes("svg")) continue;
    const size = Number(/(\d+)x\d+/.exec(attr.sizes ?? "")?.[1] ?? 0);
    const score = (rel.includes("apple-touch-icon") ? 300 : path.endsWith(".png") ? 200 : 100) + Math.min(size, 99);
    found.push({ url: url.toString(), score });
  }
  return [...new Set(found.sort((a, b) => b.score - a.score).map((f) => f.url))];
}
