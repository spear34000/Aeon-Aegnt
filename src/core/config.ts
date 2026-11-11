/**
 * Non-secret agent settings (what the UI will edit). Secrets never appear here.
 * Parsing is strict: unknown or mistyped fields are rejected instead of silently ignored, because
 * a typo in `dataConsent` or `fallbackAllowed` is a privacy-relevant mistake.
 */
export interface AeonConfig {
  providers: string[];
  /** External providers that may receive prompts (data-transfer consent). */
  dataConsent: string[];
  /** Owner-approved automatic switching to later providers. */
  fallbackAllowed: boolean;
  ollamaModel?: string;
  ollamaBaseUrl?: string;
  openaiCompatBaseUrl?: string;
  openaiCompatModel?: string;
  /** Remote MCP servers the owner added. Secrets they need live in the secret store, never here. */
  mcpServers?: McpServerConfig[];
}

export interface McpServerConfig {
  /** Stable id: the registry name (e.g. "com.notion/mcp") or a slug the owner typed. */
  name: string;
  title?: string;
  /** Remote endpoint. https only, no credentials in the URL. */
  url: string;
  websiteUrl?: string;
  /** Names (not values) of the secrets this server asks for, e.g. a header name. */
  requires?: string[];
}

const MCP_NAME = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$/;
const REQUIRE_NAME = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;

function httpsUrl(field: string, v: unknown): string {
  if (typeof v !== "string" || v.length > 500) throw new Error(`${field} must be an https URL`);
  let u: URL;
  try {
    u = new URL(v);
  } catch {
    throw new Error(`${field} must be an https URL`);
  }
  if (u.protocol !== "https:" || u.username || u.password) throw new Error(`${field} must be an https URL without credentials`);
  return u.toString();
}

export function parseMcpServers(v: unknown): McpServerConfig[] {
  if (!Array.isArray(v) || v.length > 64) throw new Error("mcpServers must be an array (max 64)");
  const seen = new Set<string>();
  return v.map((raw) => {
    if (raw === null || typeof raw !== "object" || Array.isArray(raw)) throw new Error("mcpServers entries must be objects");
    const o = raw as Record<string, unknown>;
    for (const k of Object.keys(o)) if (!["name", "title", "url", "websiteUrl", "requires"].includes(k)) throw new Error(`unknown mcpServers field: ${k}`);
    if (typeof o.name !== "string" || !MCP_NAME.test(o.name)) throw new Error("mcpServers name must be 1-128 chars of letters, digits, . _ / -");
    if (seen.has(o.name)) throw new Error(`duplicate mcpServers name: ${o.name}`);
    seen.add(o.name);
    const out: McpServerConfig = { name: o.name, url: httpsUrl("mcpServers url", o.url) };
    if (o.title !== undefined) {
      if (typeof o.title !== "string" || o.title.length === 0 || o.title.length > 80) throw new Error("mcpServers title must be 1-80 chars");
      out.title = o.title;
    }
    if (o.websiteUrl !== undefined) out.websiteUrl = httpsUrl("mcpServers websiteUrl", o.websiteUrl);
    if (o.requires !== undefined) {
      if (!Array.isArray(o.requires) || o.requires.length > 8 || !o.requires.every((r) => typeof r === "string" && REQUIRE_NAME.test(r))) throw new Error("mcpServers requires must be up to 8 names");
      out.requires = o.requires as string[];
    }
    return out;
  });
}

export const DEFAULT_CONFIG: AeonConfig = { providers: ["exaone"], dataConsent: [], fallbackAllowed: false };

const KNOWN_PROVIDERS = new Set(["exaone", "ollama", "llamacpp", "openai-compatible"]);
const STRING_FIELDS = ["ollamaModel", "ollamaBaseUrl", "openaiCompatBaseUrl", "openaiCompatModel"] as const;

export function parseConfig(raw: unknown): AeonConfig {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) throw new Error("config must be a JSON object");
  const o = raw as Record<string, unknown>;
  const allowed = new Set(["providers", "dataConsent", "fallbackAllowed", "mcpServers", ...STRING_FIELDS]);
  for (const k of Object.keys(o)) if (!allowed.has(k)) throw new Error(`unknown config field: ${k}`);

  const list = (k: string, v: unknown): string[] => {
    if (!Array.isArray(v) || !v.every((x) => typeof x === "string")) throw new Error(`${k} must be an array of strings`);
    return v as string[];
  };
  const providers = o.providers === undefined ? DEFAULT_CONFIG.providers : list("providers", o.providers);
  for (const p of providers) if (!KNOWN_PROVIDERS.has(p)) throw new Error(`unknown provider: ${p}`);
  const dataConsent = o.dataConsent === undefined ? [] : list("dataConsent", o.dataConsent);
  for (const p of dataConsent) if (!KNOWN_PROVIDERS.has(p)) throw new Error(`unknown provider in dataConsent: ${p}`);
  if (o.fallbackAllowed !== undefined && typeof o.fallbackAllowed !== "boolean") throw new Error("fallbackAllowed must be true or false");

  const cfg: AeonConfig = { providers, dataConsent, fallbackAllowed: o.fallbackAllowed === true };
  for (const f of STRING_FIELDS) {
    const v = o[f];
    if (v === undefined) continue;
    if (typeof v !== "string") throw new Error(`${f} must be a string`);
    cfg[f] = v;
  }
  if (o.mcpServers !== undefined) {
    const servers = parseMcpServers(o.mcpServers);
    if (servers.length > 0) cfg.mcpServers = servers;
  }
  return cfg;
}
