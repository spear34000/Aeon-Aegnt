import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { DEFAULT_CONFIG, parseConfig, type AeonConfig } from "../core/config.ts";

export function configPath(dir: string): string {
  return join(dir, "config.json");
}

export function loadConfig(dir: string): AeonConfig {
  const p = configPath(dir);
  if (!existsSync(p)) return { ...DEFAULT_CONFIG };
  try {
    return parseConfig(JSON.parse(readFileSync(p, "utf8")));
  } catch (e) {
    throw new Error(`Invalid config at ${p}: ${(e as Error).message}`);
  }
}

export function saveConfig(dir: string, cfg: AeonConfig): void {
  mkdirSync(dir, { recursive: true });
  const p = configPath(dir);
  const tmp = `${p}.tmp-${process.pid}`;
  writeFileSync(tmp, JSON.stringify(parseConfig(cfg), null, 2));
  renameSync(tmp, p);
}
