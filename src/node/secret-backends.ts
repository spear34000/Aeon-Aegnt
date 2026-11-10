import { spawn } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { assertSecretName, ChainSecretStore, EnvSecretStore, type SecretStore } from "../core/secrets.ts";
import { configDir } from "./platform-paths.ts";

export interface ProcessRunner {
  /** Never uses a shell. `stdin` carries secrets so they never appear in argv / process listings. */
  run(cmd: string, args: string[], stdin?: string): Promise<{ code: number; stdout: string; stderr: string }>;
}

export const processRunner: ProcessRunner = {
  run(cmd, args, stdin) {
    return new Promise((resolve) => {
      const p = spawn(cmd, args, { windowsHide: true, stdio: ["pipe", "pipe", "pipe"] });
      let stdout = "";
      let stderr = "";
      p.stdout.on("data", (d) => (stdout += d));
      p.stderr.on("data", (d) => (stderr += d));
      p.on("error", (e) => resolve({ code: 127, stdout, stderr: e.message }));
      p.on("close", (code) => resolve({ code: code ?? 1, stdout, stderr }));
      p.stdin.on("error", () => {});
      p.stdin.end(stdin ?? "");
    });
  },
};

function atomicWrite(path: string, data: string, mode: number): void {
  const tmp = `${path}.tmp-${process.pid}`;
  writeFileSync(tmp, data, { mode });
  renameSync(tmp, path);
}

/**
 * Windows: each secret is encrypted with DPAPI (CurrentUser scope, app-specific entropy) and stored
 * as base64 under %APPDATA%\Aeon\secrets. Only the same Windows user can decrypt it. Calls the
 * built-in Windows PowerShell / .NET; the secret travels via stdin as base64 (never argv).
 */
export class DpapiSecretStore implements SecretStore {
  readonly kind = "Windows DPAPI (current user)";
  private readonly dir: string;
  private readonly runner: ProcessRunner;
  constructor(dir: string, runner: ProcessRunner = processRunner) {
    this.dir = dir;
    this.runner = runner;
  }

  private static script(op: "Protect" | "Unprotect"): string {
    return [
      "$ErrorActionPreference='Stop'",
      "Add-Type -AssemblyName System.Security",
      "$in=[Console]::In.ReadToEnd().Trim()",
      "$b=[Convert]::FromBase64String($in)",
      "$e=[Text.Encoding]::UTF8.GetBytes('aeon-agent-v1')",
      `$o=[Security.Cryptography.ProtectedData]::${op}($b,$e,[Security.Cryptography.DataProtectionScope]::CurrentUser)`,
      "[Console]::Out.Write([Convert]::ToBase64String($o))",
    ].join(";");
  }

  private async ps(op: "Protect" | "Unprotect", base64In: string): Promise<string> {
    const r = await this.runner.run(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", DpapiSecretStore.script(op)],
      base64In,
    );
    if (r.code !== 0) throw new Error(`DPAPI ${op} failed (exit ${r.code})`); // stderr omitted on purpose
    return r.stdout.trim();
  }

  private file(name: string): string {
    return join(this.dir, `${name}.dpapi`);
  }

  async set(name: string, value: string): Promise<void> {
    assertSecretName(name);
    mkdirSync(this.dir, { recursive: true });
    const protectedB64 = await this.ps("Protect", Buffer.from(value, "utf8").toString("base64"));
    atomicWrite(this.file(name), protectedB64, 0o600);
  }

  async get(name: string): Promise<string | null> {
    assertSecretName(name);
    if (!existsSync(this.file(name))) return null;
    const out = await this.ps("Unprotect", readFileSync(this.file(name), "utf8"));
    return Buffer.from(out, "base64").toString("utf8");
  }

  async delete(name: string): Promise<void> {
    assertSecretName(name);
    rmSync(this.file(name), { force: true });
  }

  async list(): Promise<string[]> {
    if (!existsSync(this.dir)) return [];
    return readdirSync(this.dir).filter((f) => f.endsWith(".dpapi")).map((f) => f.slice(0, -6)).sort();
  }
}

/** Linux: freedesktop Secret Service through the `secret-tool` CLI (GNOME Keyring, KWallet, ...). */
export class SecretToolStore implements SecretStore {
  readonly kind = "Secret Service (secret-tool)";
  private readonly runner: ProcessRunner;
  constructor(runner: ProcessRunner = processRunner) {
    this.runner = runner;
  }

  static async available(runner: ProcessRunner = processRunner): Promise<boolean> {
    // `secret-tool` has no --version on every distro; an empty lookup exits non-zero but runs.
    const r = await runner.run("secret-tool", ["lookup", "service", "aeon", "account", "__probe__"]);
    return r.code !== 127;
  }

  async set(name: string, value: string): Promise<void> {
    assertSecretName(name);
    const r = await this.runner.run("secret-tool", ["store", "--label", `Aeon ${name}`, "service", "aeon", "account", name], value);
    if (r.code !== 0) throw new Error(`secret-tool store failed (exit ${r.code}). Is a keyring daemon running?`);
  }

  async get(name: string): Promise<string | null> {
    assertSecretName(name);
    const r = await this.runner.run("secret-tool", ["lookup", "service", "aeon", "account", name]);
    return r.code === 0 && r.stdout.length > 0 ? r.stdout : null;
  }

  async delete(name: string): Promise<void> {
    assertSecretName(name);
    await this.runner.run("secret-tool", ["clear", "service", "aeon", "account", name]);
  }

  async list(): Promise<string[]> {
    const r = await this.runner.run("secret-tool", ["search", "--all", "service", "aeon"]);
    const names = [...(r.stdout + "\n" + r.stderr).matchAll(/attribute\.account\s*=\s*(\S+)/g)].map((m) => m[1]!);
    return [...new Set(names)].sort();
  }
}

/** Fallback when no keyring exists (headless Linux): plaintext JSON with mode 0600 in the user's config dir. */
export class FileSecretStore implements SecretStore {
  readonly kind = "file with 0600 permissions (not encrypted)";
  private readonly path: string;
  constructor(dir: string) {
    mkdirSync(dir, { recursive: true });
    this.path = join(dir, "secrets.json");
  }
  private read(): Record<string, string> {
    return existsSync(this.path) ? (JSON.parse(readFileSync(this.path, "utf8")) as Record<string, string>) : {};
  }
  private write(m: Record<string, string>): void {
    atomicWrite(this.path, JSON.stringify(m), 0o600);
    if (process.platform !== "win32") chmodSync(this.path, 0o600);
  }
  async get(name: string): Promise<string | null> {
    return this.read()[name] ?? null;
  }
  async set(name: string, value: string): Promise<void> {
    assertSecretName(name);
    this.write({ ...this.read(), [name]: value });
  }
  async delete(name: string): Promise<void> {
    const m = this.read();
    delete m[name];
    this.write(m);
  }
  async list(): Promise<string[]> {
    return Object.keys(this.read()).sort();
  }
}

/**
 * Platform-conventional store (decision D11). Environment variables always win.
 *  - Windows: DPAPI  - Linux: Secret Service, else 0600 file  - macOS: environment only (CLI-only, no UI yet)
 */
export async function defaultSecretStore(
  opts: { platform?: NodeJS.Platform; env?: Record<string, string | undefined>; dir?: string; runner?: ProcessRunner } = {},
): Promise<SecretStore> {
  const platform = opts.platform ?? process.platform;
  const env = opts.env ?? process.env;
  const dir = opts.dir ?? configDir(platform, env);
  const runner = opts.runner ?? processRunner;
  const envStore = new EnvSecretStore(env);
  if (platform === "win32") {
    const s = new DpapiSecretStore(join(dir, "secrets"), runner);
    return new ChainSecretStore([envStore, s], s);
  }
  if (platform === "darwin") return new ChainSecretStore([envStore], null);
  const persistent = (await SecretToolStore.available(runner)) ? new SecretToolStore(runner) : new FileSecretStore(dir);
  return new ChainSecretStore([envStore, persistent], persistent);
}
