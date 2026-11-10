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
}

export const DEFAULT_CONFIG: AeonConfig = { providers: ["exaone"], dataConsent: [], fallbackAllowed: false };

const KNOWN_PROVIDERS = new Set(["exaone", "ollama", "llamacpp", "openai-compatible"]);
const STRING_FIELDS = ["ollamaModel", "ollamaBaseUrl", "openaiCompatBaseUrl", "openaiCompatModel"] as const;

export function parseConfig(raw: unknown): AeonConfig {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) throw new Error("config must be a JSON object");
  const o = raw as Record<string, unknown>;
  const allowed = new Set(["providers", "dataConsent", "fallbackAllowed", ...STRING_FIELDS]);
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
  return cfg;
}
