import { homedir } from "node:os";
import { join } from "node:path";

/**
 * Per-user configuration directory following each platform's convention.
 * `AEON_CONFIG_DIR` overrides it (tests, portable installs).
 */
export function configDir(
  platform: NodeJS.Platform = process.platform,
  env: Record<string, string | undefined> = process.env,
  home: string = homedir(),
): string {
  if (env.AEON_CONFIG_DIR) return env.AEON_CONFIG_DIR;
  if (platform === "win32") return join(env.APPDATA || join(home, "AppData", "Roaming"), "Aeon");
  if (platform === "darwin") return join(home, "Library", "Application Support", "Aeon");
  return join(env.XDG_CONFIG_HOME || join(home, ".config"), "aeon");
}
