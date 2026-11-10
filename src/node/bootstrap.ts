import { loadConfig } from "./config-file.ts";
import { createRuntime } from "./runtime.ts";
import { defaultSecretStore } from "./secret-backends.ts";
import type { SecretStore } from "../core/secrets.ts";

export interface BootOverrides {
  providers?: string[];
  dataConsent?: string[];
  fallbackAllowed?: boolean;
  yesConsent?: boolean;
}

/**
 * Builds a runtime from saved settings + the platform secret store (decision D11).
 * Environment variables win; stored secrets fill in only what the chosen providers need.
 */
export async function bootRuntime(cfgDir: string, workspace: string, ov: BootOverrides = {}, secrets?: SecretStore, lenient = false) {
  const saved = loadConfig(cfgDir);
  const providers = ov.providers ?? saved.providers;
  const dataConsent = ov.yesConsent ? providers : (ov.dataConsent ?? saved.dataConsent);
  const fallbackAllowed = ov.fallbackAllowed ?? saved.fallbackAllowed;

  const store = secrets ?? (await defaultSecretStore({ dir: cfgDir }));
  const env: Record<string, string | undefined> = { ...process.env };
  const need: Record<string, string> = { exaone: "EXAONE_API_KEY", "openai-compatible": "OPENAI_COMPAT_API_KEY" };
  for (const p of providers) {
    const name = need[p];
    if (name && !env[name]) {
      const v = await store.get(name);
      if (v) env[name] = v;
    }
  }
  const fromCfg: [string, string | undefined][] = [
    ["OLLAMA_MODEL", saved.ollamaModel],
    ["OLLAMA_BASE_URL", saved.ollamaBaseUrl],
    ["OPENAI_COMPAT_BASE_URL", saved.openaiCompatBaseUrl],
    ["OPENAI_COMPAT_MODEL", saved.openaiCompatModel],
  ];
  for (const [k, v] of fromCfg) if (!env[k] && v) env[k] = v;

  try {
    return { runtime: createRuntime({ workspace, providers, fallbackAllowed, dataConsent, env }), secrets: store, problem: null as string | null };
  } catch (e) {
    // The server must still start without a key or model, otherwise the UI could never be used to enter one.
    if (!lenient) throw e;
    return { runtime: createRuntime({ workspace, providers: [], providerOverrides: [], fallbackAllowed: false, dataConsent: [], env }), secrets: store, problem: (e as Error).message };
  }
}
