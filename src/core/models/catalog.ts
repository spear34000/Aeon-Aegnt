import { isLoopbackUrl, type AeonConfig, type EndpointConfig } from "../config.ts";

/**
 * Provider catalogue. The list of providers and models is data (models.dev, the catalogue opencode uses),
 * read at run time. What lives in code is only how to talk to them: every provider here is reached through
 * its OpenAI-compatible `/chat/completions` endpoint.
 */
export interface CatalogModel {
  id: string;
  name: string;
  /** The model accepts a reasoning-effort setting. */
  reasoning: boolean;
  /** The model accepts files or images as input. Aeon sends text only today. */
  attachment: boolean;
  context: number | null;
  released: string | null;
}

export interface CatalogProvider {
  id: string;
  name: string;
  baseUrl: string;
  /** Name of the secret holding the API key, or null when none is needed. */
  keyName: string | null;
  doc: string | null;
  /** Runs on this machine: prompts do not leave it, and no consent is asked. */
  local: boolean;
  /** Reasoning levels this provider understands. */
  efforts: string[];
  models: CatalogModel[];
}

/**
 * OpenAI-compatible base URLs of providers that models.dev lists with a dedicated SDK instead of a URL.
 * Each answered 401/400 to an unauthenticated POST /chat/completions on 2026-11-11, i.e. the route exists.
 * None of them was exercised with a real key.
 */
const KNOWN_ENDPOINTS: Record<string, string> = {
  openai: "https://api.openai.com/v1",
  anthropic: "https://api.anthropic.com/v1",
  google: "https://generativelanguage.googleapis.com/v1beta/openai",
  groq: "https://api.groq.com/openai/v1",
  mistral: "https://api.mistral.ai/v1",
  xai: "https://api.x.ai/v1",
  togetherai: "https://api.together.xyz/v1",
  cerebras: "https://api.cerebras.ai/v1",
  perplexity: "https://api.perplexity.ai",
  cohere: "https://api.cohere.ai/compatibility/v1",
  deepinfra: "https://api.deepinfra.com/v1/openai",
};

const EFFORTS = ["low", "medium", "high"];
/** Letters, digits and hyphens only, so the key name derived below is unique per id. */
const PROVIDER_ID = /^[a-z0-9][a-z0-9-]{0,47}$/;

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

/** Providers Aeon has its own code path for. They are listed even when the catalogue cannot be fetched. */
export function builtinProviders(): CatalogProvider[] {
  return [
    {
      id: "exaone",
      name: "LG EXAONE",
      baseUrl: "https://api.exaone.ai/v1",
      keyName: "EXAONE_API_KEY",
      doc: "https://docs.exaone.ai",
      local: false,
      efforts: ["low", "high", "max"],
      models: [{ id: "k-exaone_v2.5", name: "K-EXAONE 2.5", reasoning: true, attachment: false, context: 524_288, released: null }],
    },
    { id: "ollama", name: "Ollama", baseUrl: "http://localhost:11434/v1", keyName: null, doc: "https://ollama.com", local: true, efforts: [], models: [] },
    { id: "llamacpp", name: "llama.cpp", baseUrl: "http://localhost:8080/v1", keyName: null, doc: "https://github.com/ggml-org/llama.cpp", local: true, efforts: [], models: [] },
  ];
}

type Json = Record<string, unknown>;
const obj = (v: unknown): Json | null => (v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Json) : null);
const str = (v: unknown, max: number): string | null => (typeof v === "string" && v.trim() ? v.trim().slice(0, max) : null);

function usableUrl(v: unknown): string | null {
  const s = str(v, 300);
  if (!s || s.includes("${")) return null; // templated URLs need values Aeon does not ask for
  try {
    const u = new URL(s);
    if (u.username || u.password) return null;
    if (u.protocol === "https:" || (u.protocol === "http:" && isLoopbackUrl(s))) return s.replace(/\/+$/, "");
  } catch {
    /* not a URL */
  }
  return null;
}

/** Maps the models.dev document to the providers Aeon can reach. Unusable entries are dropped, not guessed. */
export function catalogFromModelsDev(raw: unknown): CatalogProvider[] {
  const root = obj(raw);
  if (!root) return [];
  const builtin = new Set(builtinProviders().map((p) => p.id));
  const out: CatalogProvider[] = [];
  for (const [key, value] of Object.entries(root)) {
    const p = obj(value);
    const id = str(p?.id, 64) ?? key;
    if (!p || !PROVIDER_ID.test(id) || builtin.has(id) || id === "openai-compatible") continue;
    const baseUrl = KNOWN_ENDPOINTS[id] ?? (p.npm === "@ai-sdk/openai-compatible" || p.npm === "@openrouter/ai-sdk-provider" ? usableUrl(p.api) : null);
    if (!baseUrl) continue;
    const local = isLoopbackUrl(baseUrl);
    const models: CatalogModel[] = [];
    for (const [mid, mv] of Object.entries(obj(p.models) ?? {})) {
      const m = obj(mv);
      if (!m || m.status === "deprecated") continue;
      const outputs = obj(m.modalities)?.output;
      if (Array.isArray(outputs) && !outputs.includes("text")) continue;
      const modelId = str(m.id, 200) ?? mid;
      const context = obj(m.limit)?.context;
      models.push({
        id: modelId,
        name: str(m.name, 120) ?? modelId,
        reasoning: m.reasoning === true,
        attachment: m.attachment === true,
        context: typeof context === "number" && context > 0 ? context : null,
        released: str(m.release_date, 10),
      });
    }
    models.sort((a, b) => (b.released ?? "").localeCompare(a.released ?? "") || a.name.localeCompare(b.name));
    out.push({ id, name: str(p.name, 80) ?? id, baseUrl, keyName: local ? null : keyNameFor(id), doc: usableUrl(p.doc), local, efforts: EFFORTS, models: models.slice(0, 120) });
  }
  return out.sort((a, b) => a.name.localeCompare(b.name));
}

function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

export interface CurrentModel {
  provider: string;
  providerName: string;
  model: string;
  reasoningEffort: string | null;
}

/** The model a new task would use: the first entry of `providers`. */
export function currentModel(cfg: AeonConfig, catalog: CatalogProvider[]): CurrentModel | null {
  const id = cfg.providers[0];
  if (!id) return null;
  const known = catalog.find((p) => p.id === id);
  const ep = (cfg.endpoints ?? []).find((e) => e.id === id);
  if (id === "exaone") return { provider: id, providerName: known?.name ?? id, model: "k-exaone_v2.5", reasoningEffort: cfg.exaoneReasoning ?? "low" };
  if (id === "ollama") return { provider: id, providerName: known?.name ?? id, model: cfg.ollamaModel ?? "", reasoningEffort: null };
  if (id === "llamacpp") return { provider: id, providerName: known?.name ?? id, model: "default", reasoningEffort: null };
  if (id === "openai-compatible") return { provider: id, providerName: "OpenAI-compatible", model: cfg.openaiCompatModel ?? "", reasoningEffort: null };
  if (!ep) return null;
  return { provider: id, providerName: ep.name ?? known?.name ?? id, model: ep.model, reasoningEffort: ep.reasoningEffort ?? null };
}

export interface ModelPick {
  provider: string;
  model: string;
  reasoningEffort?: string;
  /** The owner agreed that prompts go to this provider. Required the first time for a provider that is not local. */
  consent?: boolean;
  /** Only for a provider that is not in the catalogue. `needsKey: false` is for services that take no key. */
  custom?: { name?: string; baseUrl: string; needsKey?: boolean };
}

/**
 * Returns the settings with the picked model first in line. Throws with a plain reason when the pick is not
 * allowed (unknown provider, missing consent, effort the provider does not have).
 */
export function selectModel(cfg: AeonConfig, pick: ModelPick, catalog: CatalogProvider[]): AeonConfig {
  const known = catalog.find((p) => p.id === pick.provider);
  if (!known && !pick.custom) throw new Error(`unknown provider: ${pick.provider}`);
  if (!known && !PROVIDER_ID.test(pick.provider)) throw new Error("a provider id uses lower-case letters, digits and hyphens");
  if (typeof pick.model !== "string" || !pick.model.trim()) throw new Error("model is required");
  const model = pick.model.trim();
  const next: AeonConfig = { ...cfg, endpoints: [...(cfg.endpoints ?? [])] };
  const efforts = known ? known.efforts : EFFORTS;
  const effort = pick.reasoningEffort;
  if (effort !== undefined && !efforts.includes(effort)) throw new Error(`reasoningEffort must be one of: ${efforts.join(", ") || "(none for this provider)"}`);

  let local: boolean;
  if (pick.provider === "exaone") {
    local = false;
    if (effort) next.exaoneReasoning = effort as "low" | "high" | "max";
  } else if (pick.provider === "ollama") {
    local = true;
    next.ollamaModel = model;
  } else if (pick.provider === "llamacpp") {
    local = true;
  } else {
    const baseUrl = (known?.baseUrl ?? pick.custom!.baseUrl).replace(/\/+$/, "");
    local = isLoopbackUrl(baseUrl);
    const keyName = known ? known.keyName : local || pick.custom!.needsKey === false ? null : keyNameFor(pick.provider);
    const name = known?.name ?? pick.custom!.name;
    // The stored key follows the provider id, so a new address for the same id must be agreed to again:
    // otherwise a changed catalogue entry could move an existing key to another host without anyone noticing.
    const before = (cfg.endpoints ?? []).find((e) => e.id === pick.provider);
    if (before && before.baseUrl !== baseUrl && !local && pick.consent !== true) {
      throw new Error(`consent required: the address of this provider changed from ${hostOf(before.baseUrl)} to ${hostOf(baseUrl)}`);
    }
    const ep: EndpointConfig = { id: pick.provider, baseUrl, model, ...(name ? { name } : {}), ...(keyName ? { keyName } : {}), ...(effort ? { reasoningEffort: effort as "low" | "medium" | "high" } : {}) };
    next.endpoints = [...next.endpoints!.filter((e) => e.id !== pick.provider), ep];
  }
  if (!local && !cfg.dataConsent.includes(pick.provider)) {
    if (pick.consent !== true) throw new Error("consent required: prompts will be sent to this provider");
    next.dataConsent = [...cfg.dataConsent, pick.provider];
  }
  next.providers = [pick.provider, ...cfg.providers.filter((p) => p !== pick.provider)];
  if (next.endpoints!.length === 0) delete next.endpoints;
  return next;
}
