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
  /** OpenAI-compatible model services the owner chose (from the provider catalogue or typed by hand). */
  endpoints?: EndpointConfig[];
  /** Reasoning effort for the built-in EXAONE provider. */
  exaoneReasoning?: "low" | "high" | "max";
  /** How the agent addresses the owner. Wording only; it never changes what the agent may do. */
  profile?: Profile;
}

export interface EndpointConfig {
  /** Provider id used in `providers` and `dataConsent`, e.g. "openai", "groq", or a name the owner typed. */
  id: string;
  name?: string;
  /** https, or http for a model server on this machine (localhost). */
  baseUrl: string;
  model: string;
  /** Name of the secret that holds the API key. The key itself is never stored here. */
  keyName?: string;
  /** Sent as `reasoning_effort` only for models that support it. */
  reasoningEffort?: "low" | "medium" | "high";
}

export interface Profile {
  /** What the agent calls the owner. */
  userName?: string;
  agentName?: string;
  tone?: "polite" | "casual";
  detail?: "brief" | "detailed";
  language?: "auto" | "ko" | "en";
  /** Free notes about the owner that the agent may use when wording answers. */
  about?: string;
}

const KNOWN_PROVIDERS = new Set<string>(["exaone", "ollama", "llamacpp", "openai-compatible"]);
const ENDPOINT_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/;
/**
 * The secret that holds a provider's key is named by Aeon from the provider id, never by the catalogue.
 * The catalogue is data from the network: if it could name the secret, an entry could ask for another
 * provider's key and have it sent to its own address.
 *
 * Names live in their own namespace (`AEON_EP_<ID>_KEY`), so they can never equal a secret used for anything
 * else (EXAONE_API_KEY, OPENAI_COMPAT_API_KEY, or a key the owner keeps in the environment for other tools).
 * Ids contain only [a-z0-9-], and "-" becomes "_", so two ids never share a name.
 */
export function keyNameFor(id: string): string {
  return `AEON_EP_${id.toUpperCase().replace(/-/g, "_")}_KEY`;
}


/** True for a URL that points at this machine. Such model servers are not "external". */
export function isLoopbackUrl(url: string): boolean {
  try {
    const h = new URL(url).hostname.toLowerCase();
    return h === "localhost" || h === "127.0.0.1" || h === "[::1]" || h === "::1";
  } catch {
    return false;
  }
}

function modelUrl(v: unknown): string {
  if (typeof v !== "string" || v.length > 500) throw new Error("endpoint baseUrl must be a URL");
  let u: URL;
  try {
    u = new URL(v);
  } catch {
    throw new Error("endpoint baseUrl must be a URL");
  }
  if (u.username || u.password) throw new Error("endpoint baseUrl must not contain credentials");
  if (u.protocol !== "https:" && !(u.protocol === "http:" && isLoopbackUrl(v))) throw new Error("endpoint baseUrl must be https (http is allowed for localhost only)");
  return v.replace(/\/+$/, "");
}

export function parseEndpoints(v: unknown): EndpointConfig[] {
  if (!Array.isArray(v) || v.length > 32) throw new Error("endpoints must be an array (max 32)");
  const seen = new Set<string>();
  return v.map((raw) => {
    if (raw === null || typeof raw !== "object" || Array.isArray(raw)) throw new Error("endpoints entries must be objects");
    const o = raw as Record<string, unknown>;
    for (const k of Object.keys(o)) if (!["id", "name", "baseUrl", "model", "keyName", "reasoningEffort"].includes(k)) throw new Error(`unknown endpoints field: ${k}`);
    if (typeof o.id !== "string" || !ENDPOINT_ID.test(o.id)) throw new Error("endpoint id must be lower-case letters, digits, . _ -");
    if (KNOWN_PROVIDERS.has(o.id)) throw new Error(`endpoint id "${o.id}" is a built-in provider`);
    if (seen.has(o.id)) throw new Error(`duplicate endpoint id: ${o.id}`);
    seen.add(o.id);
    if (typeof o.model !== "string" || o.model.length === 0 || o.model.length > 200 || /[\u0000-\u001f]/.test(o.model)) throw new Error("endpoint model must be 1-200 characters");
    const out: EndpointConfig = { id: o.id, baseUrl: modelUrl(o.baseUrl), model: o.model };
    if (o.name !== undefined) {
      if (typeof o.name !== "string" || o.name.length === 0 || o.name.length > 80) throw new Error("endpoint name must be 1-80 characters");
      out.name = o.name;
    }
    if (o.keyName !== undefined) {
      // A client cannot choose which stored secret an address receives: the name must be the one derived from the id.
      if (o.keyName !== keyNameFor(o.id)) throw new Error(`endpoint keyName must be ${keyNameFor(o.id)}`);
      out.keyName = o.keyName;
    }
    if (o.reasoningEffort !== undefined) {
      if (o.reasoningEffort !== "low" && o.reasoningEffort !== "medium" && o.reasoningEffort !== "high") throw new Error("endpoint reasoningEffort must be low, medium or high");
      out.reasoningEffort = o.reasoningEffort;
    }
    return out;
  });
}

function oneLine(field: string, v: unknown, max: number): string {
  if (typeof v !== "string" || v.length > max || /[\u0000-\u001f]/.test(v)) throw new Error(`profile ${field} must be one line of at most ${max} characters`);
  return v.trim();
}

export function parseProfile(v: unknown): Profile {
  if (v === null || typeof v !== "object" || Array.isArray(v)) throw new Error("profile must be an object");
  const o = v as Record<string, unknown>;
  for (const k of Object.keys(o)) if (!["userName", "agentName", "tone", "detail", "language", "about"].includes(k)) throw new Error(`unknown profile field: ${k}`);
  const out: Profile = {};
  if (o.userName !== undefined) out.userName = oneLine("userName", o.userName, 40);
  if (o.agentName !== undefined) out.agentName = oneLine("agentName", o.agentName, 40);
  if (o.tone !== undefined) {
    if (o.tone !== "polite" && o.tone !== "casual") throw new Error("profile tone must be polite or casual");
    out.tone = o.tone;
  }
  if (o.detail !== undefined) {
    if (o.detail !== "brief" && o.detail !== "detailed") throw new Error("profile detail must be brief or detailed");
    out.detail = o.detail;
  }
  if (o.language !== undefined) {
    if (o.language !== "auto" && o.language !== "ko" && o.language !== "en") throw new Error("profile language must be auto, ko or en");
    out.language = o.language;
  }
  if (o.about !== undefined) {
    if (typeof o.about !== "string" || o.about.length > 1000) throw new Error("profile about must be at most 1000 characters");
    out.about = o.about.trim();
  }
  for (const k of ["userName", "agentName", "about"] as const) if (out[k] === "") delete out[k];
  return out;
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

/** Providers with their own code path. Everything else must be listed in `endpoints`. */
export const BUILTIN_PROVIDERS = ["exaone", "ollama", "llamacpp", "openai-compatible"] as const;
const STRING_FIELDS = ["ollamaModel", "ollamaBaseUrl", "openaiCompatBaseUrl", "openaiCompatModel"] as const;

export function parseConfig(raw: unknown): AeonConfig {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) throw new Error("config must be a JSON object");
  const o = raw as Record<string, unknown>;
  const allowed = new Set(["providers", "dataConsent", "fallbackAllowed", "mcpServers", "endpoints", "exaoneReasoning", "profile", ...STRING_FIELDS]);
  for (const k of Object.keys(o)) if (!allowed.has(k)) throw new Error(`unknown config field: ${k}`);

  const list = (k: string, v: unknown): string[] => {
    if (!Array.isArray(v) || !v.every((x) => typeof x === "string")) throw new Error(`${k} must be an array of strings`);
    return v as string[];
  };
  const endpoints = o.endpoints === undefined ? [] : parseEndpoints(o.endpoints);
  const known = new Set<string>([...KNOWN_PROVIDERS, ...endpoints.map((e) => e.id)]);
  const providers = o.providers === undefined ? DEFAULT_CONFIG.providers : list("providers", o.providers);
  for (const p of providers) if (!known.has(p)) throw new Error(`unknown provider: ${p}`);
  const dataConsent = o.dataConsent === undefined ? [] : list("dataConsent", o.dataConsent);
  for (const p of dataConsent) if (!known.has(p)) throw new Error(`unknown provider in dataConsent: ${p}`);
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
  if (endpoints.length > 0) cfg.endpoints = endpoints;
  if (o.exaoneReasoning !== undefined) {
    if (o.exaoneReasoning !== "low" && o.exaoneReasoning !== "high" && o.exaoneReasoning !== "max") throw new Error("exaoneReasoning must be low, high or max");
    cfg.exaoneReasoning = o.exaoneReasoning;
  }
  if (o.profile !== undefined) {
    const profile = parseProfile(o.profile);
    if (Object.keys(profile).length > 0) cfg.profile = profile;
  }
  return cfg;
}
