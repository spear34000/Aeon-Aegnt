import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createServer } from "node:http";
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { parseConfig } from "../src/core/config.ts";
import { ChainSecretStore, EnvSecretStore, MemorySecretStore, assertSecretName } from "../src/core/secrets.ts";
import { loadConfig, saveConfig } from "../src/node/config-file.ts";
import { configDir } from "../src/node/platform-paths.ts";
import { DpapiSecretStore, FileSecretStore, SecretToolStore, defaultSecretStore, type ProcessRunner } from "../src/node/secret-backends.ts";
import { tempDir } from "./helpers.ts";

const NL = String.fromCharCode(10);
const SECRET = "exa_SUPER_secret_value_ünïcödé_12345";
const isWin = process.platform === "win32";

describe("secret names and chain semantics", () => {
  it("accepts UPPER_SNAKE_CASE only (blocks path tricks used as file names)", () => {
    assertSecretName("EXAONE_API_KEY");
    for (const bad of ["", "a", "lower_case", "../EVIL", "A/B", "A B", "X".repeat(70)]) assert.throws(() => assertSecretName(bad), /Invalid secret name/);
  });

  it("environment variables win over the persistent store and are read-only", async () => {
    const persistent = new MemorySecretStore();
    await persistent.set("EXAONE_API_KEY", "from-store");
    const chain = new ChainSecretStore([new EnvSecretStore({ EXAONE_API_KEY: "from-env" }), persistent], persistent);
    assert.equal(await chain.get("EXAONE_API_KEY"), "from-env");
    await assert.rejects(new EnvSecretStore({}).set(), /read-only/);
    await persistent.set("OTHER_API_KEY", "x");
    assert.deepEqual(await chain.list(), ["EXAONE_API_KEY", "OTHER_API_KEY"]);
  });

  it("macOS has no writable store (CLI-only, env vars) per D11", async () => {
    const store = await defaultSecretStore({ platform: "darwin", env: { EXAONE_API_KEY: "k" } });
    assert.equal(await store.get("EXAONE_API_KEY"), "k");
    await assert.rejects(store.set("EXAONE_API_KEY", "x"), /No writable secret store/);
  });
});

describe("Windows DPAPI store (REAL PowerShell/DPAPI on this machine)", { skip: isWin ? false : "Windows only" }, () => {
  it("round-trips a unicode secret, stores no plaintext, and lists names only", async () => {
    const { dir, cleanup } = tempDir();
    const store = new DpapiSecretStore(join(dir, "secrets"));
    await store.set("EXAONE_API_KEY", SECRET);
    assert.equal(await store.get("EXAONE_API_KEY"), SECRET);
    assert.deepEqual(await store.list(), ["EXAONE_API_KEY"]);

    const raw = readFileSync(join(dir, "secrets", "EXAONE_API_KEY.dpapi"), "utf8");
    assert.ok(!raw.includes("SUPER") && !raw.includes(SECRET), "ciphertext must not contain the secret");
    assert.ok(!Buffer.from(raw, "base64").toString("utf8").includes("SUPER"), "must be encrypted, not just base64");

    await store.delete("EXAONE_API_KEY");
    assert.equal(await store.get("EXAONE_API_KEY"), null);
    cleanup();
  });

  it("returns null for unknown names and surfaces failure for a corrupted blob without leaking content", async () => {
    const { dir, cleanup } = tempDir();
    const store = new DpapiSecretStore(join(dir, "secrets"));
    assert.equal(await store.get("NOPE_API_KEY"), null);
    await store.set("A_API_KEY", "v");
    writeFileSync(join(dir, "secrets", "A_API_KEY.dpapi"), "AAAA-not-a-valid-blob");
    await assert.rejects(store.get("A_API_KEY"), (e: unknown) => /DPAPI Unprotect failed/.test((e as Error).message));
    cleanup();
  });
});

describe("Linux stores (fake process runner; real Secret Service is not available on this PC)", () => {
  const fakeRunner = () => {
    const calls: { cmd: string; args: string[]; stdin?: string }[] = [];
    const db = new Map<string, string>();
    const runner: ProcessRunner = {
      async run(cmd, args, stdin) {
        calls.push({ cmd, args, ...(stdin !== undefined ? { stdin } : {}) });
        const account = args[args.indexOf("account") + 1]!;
        if (args[0] === "store") return db.set(account, stdin ?? ""), { code: 0, stdout: "", stderr: "" };
        if (args[0] === "lookup") return db.has(account) ? { code: 0, stdout: db.get(account)!, stderr: "" } : { code: 1, stdout: "", stderr: "" };
        if (args[0] === "clear") return db.delete(account), { code: 0, stdout: "", stderr: "" };
        if (args[0] === "search") return { code: 0, stdout: [...db.keys()].map((k) => `attribute.account = ${k}`).join("\n"), stderr: "" };
        return { code: 127, stdout: "", stderr: "" };
      },
    };
    return { runner, calls };
  };

  it("passes the secret over stdin, never in argv", async () => {
    const { runner, calls } = fakeRunner();
    const s = new SecretToolStore(runner);
    await s.set("EXAONE_API_KEY", SECRET);
    assert.equal(await s.get("EXAONE_API_KEY"), SECRET);
    assert.deepEqual(await s.list(), ["EXAONE_API_KEY"]);
    for (const c of calls) assert.ok(!c.args.join(" ").includes("SUPER"), `secret in argv: ${c.args.join(" ")}`);
    assert.equal(calls[0]!.stdin, SECRET);
    await s.delete("EXAONE_API_KEY");
    assert.equal(await s.get("EXAONE_API_KEY"), null);
  });

  it("linux default: Secret Service when present, else a 0600 file", async () => {
    const { dir, cleanup } = tempDir();
    const withTool = await defaultSecretStore({ platform: "linux", env: {}, dir, runner: fakeRunner().runner });
    assert.match(withTool.kind, /Secret Service/);

    const noTool: ProcessRunner = { run: async () => ({ code: 127, stdout: "", stderr: "not found" }) };
    const fallback = await defaultSecretStore({ platform: "linux", env: {}, dir, runner: noTool });
    assert.match(fallback.kind, /0600/);
    await fallback.set("A_API_KEY", "value");
    assert.equal(await fallback.get("A_API_KEY"), "value");
    cleanup();
  });

  it("file fallback keeps mode 0600 on POSIX systems", { skip: isWin ? "POSIX permissions only" : false }, async () => {
    const { dir, cleanup } = tempDir();
    const f = new FileSecretStore(dir);
    await f.set("A_API_KEY", "v");
    assert.equal(statSync(join(dir, "secrets.json")).mode & 0o777, 0o600);
    cleanup();
  });
});

describe("config + platform paths", () => {
  it("uses each platform's convention and honours AEON_CONFIG_DIR", () => {
    assert.equal(configDir("win32", { APPDATA: "C:\\Users\\u\\AppData\\Roaming" }, "C:\\Users\\u"), join("C:\\Users\\u\\AppData\\Roaming", "Aeon"));
    assert.equal(configDir("linux", { XDG_CONFIG_HOME: "/x/cfg" }, "/home/u"), join("/x/cfg", "aeon"));
    assert.equal(configDir("linux", {}, "/home/u"), join("/home/u", ".config", "aeon"));
    assert.equal(configDir("darwin", {}, "/Users/u"), join("/Users/u", "Library", "Application Support", "Aeon"));
    assert.equal(configDir("win32", { AEON_CONFIG_DIR: "/custom" }), "/custom");
  });

  it("parses strictly: unknown fields, unknown providers and wrong types are rejected", () => {
    assert.deepEqual(parseConfig({ providers: ["ollama"], dataConsent: [], fallbackAllowed: true, ollamaModel: "m" }).ollamaModel, "m");
    assert.throws(() => parseConfig({ dataconsent: ["exaone"] }), /unknown config field/);
    assert.throws(() => parseConfig({ providers: ["evil"] }), /unknown provider/);
    assert.throws(() => parseConfig({ fallbackAllowed: "yes" }), /true or false/);
    assert.throws(() => parseConfig([]), /JSON object/);
  });

  it("config file round-trips and never accepts a secret field", () => {
    const { dir, cleanup } = tempDir();
    assert.deepEqual(loadConfig(dir).providers, ["exaone"]);
    saveConfig(dir, { providers: ["ollama", "exaone"], dataConsent: ["exaone"], fallbackAllowed: true });
    assert.equal(loadConfig(dir).fallbackAllowed, true);
    assert.throws(() => parseConfig({ providers: ["exaone"], apiKey: "sk-123456" }), /unknown config field: apiKey/);
    writeFileSync(join(dir, "config.json"), "{ not json");
    assert.throws(() => loadConfig(dir), /Invalid config/);
    cleanup();
  });
});

describe("CLI secrets/config (real process, isolated AEON_CONFIG_DIR)", () => {
  const cli = join(import.meta.dirname, "..", "src", "node", "cli.ts");
  const run = (args: string[], cfgDir: string, input?: string, env: Record<string, string> = {}) =>
    spawnSync(process.execPath, [cli, ...args], {
      encoding: "utf8",
      ...(input !== undefined ? { input } : {}),
      env: { ...process.env, EXAONE_API_KEY: "", AEON_CONFIG_DIR: cfgDir, ...env },
    });

  it("stores a secret from stdin, lists names only, never prints the value, and uses it for a run", { skip: isWin ? false : "uses DPAPI; Linux path covered by fake-runner tests" }, () => {
    const { dir, cleanup } = tempDir();
    const cfg = join(dir, "cfg");
    const ws = join(dir, "ws");

    const set = run(["secrets", "set", "EXAONE_API_KEY"], cfg, `${SECRET}\n`);
    assert.equal(set.status, 0, set.stderr);
    assert.ok(!set.stdout.includes("SUPER") && !set.stderr.includes("SUPER"));
    assert.match(set.stdout, /DPAPI/);

    const list = run(["secrets", "list"], cfg);
    assert.match(list.stdout, /EXAONE_API_KEY/);
    assert.ok(!list.stdout.includes("SUPER"));

    // Nothing in the config dir contains the plaintext.
    const files = (d: string): string[] => readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? files(join(d, e.name)) : [join(d, e.name)]));
    for (const f of files(cfg)) assert.ok(!readFileSync(f, "utf8").includes("SUPER"), `plaintext found in ${f}`);

    cleanup();
  });

  it("the stored secret is what the runtime really sends as the Bearer token (local fake server)", { skip: isWin ? false : "uses DPAPI" }, async () => {
    const { dir, cleanup } = tempDir();
    const cfg = join(dir, "cfg");
    mkdirSync(join(dir, "ws"));
    const seen: string[] = [];
    const server = createServer((req, res) => {
      seen.push(String(req.headers.authorization));
      res.writeHead(401).end("nope");
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;
    assert.equal(run(["secrets", "set", "OPENAI_COMPAT_API_KEY"], cfg, `${SECRET}${NL}`).status, 0);
    assert.equal(run(["config", "set", "providers=openai-compatible", "consent=openai-compatible", `openaiCompatBaseUrl=http://127.0.0.1:${port}/v1`, "openaiCompatModel=m"], cfg).status, 0);
    const child = spawn(process.execPath, [cli, "run", "hi", "--workspace", join(dir, "ws")], { env: { ...process.env, AEON_CONFIG_DIR: cfg, EXAONE_API_KEY: "", OPENAI_COMPAT_API_KEY: "" } });
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (out += d));
    await new Promise((r) => child.on("close", r));
    server.close();
    assert.deepEqual(seen.slice(0, 1), [`Bearer ${SECRET}`], `the DPAPI-stored key reached the provider; CLI said: ${out}`);
    assert.ok(!out.includes("SUPER"), "secret must not be printed by the CLI");
    cleanup();
  });

  it("secrets can be deleted", { skip: isWin ? false : "uses DPAPI" }, () => {
    const { dir, cleanup } = tempDir();
    const cfg = join(dir, "cfg");
    assert.equal(run(["secrets", "set", "EXAONE_API_KEY"], cfg, "x-value-123" + NL).status, 0);
    assert.equal(run(["secrets", "delete", "EXAONE_API_KEY"], cfg).status, 0);
    assert.ok(!run(["secrets", "list"], cfg).stdout.includes("EXAONE_API_KEY"));
    cleanup();
  });

  it("config set validates and persists; bad values are rejected", () => {
    const { dir, cleanup } = tempDir();
    const ok = run(["config", "set", "providers=ollama", "consent=", "fallback=true", "ollamaModel=m1"], dir);
    assert.equal(ok.status, 0, ok.stderr);
    assert.equal(existsSync(join(dir, "config.json")), true);
    assert.equal(JSON.parse(run(["config", "show"], dir).stdout).ollamaModel, "m1");
    const bad = run(["config", "set", "providers=skynet"], dir);
    assert.equal(bad.status, 1);
    assert.match(bad.stderr, /unknown provider/);
    const badName = run(["secrets", "set", "../evil"], dir, "x\n");
    assert.equal(badName.status, 1);
    cleanup();
  });
});
