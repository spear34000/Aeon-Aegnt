import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { ManualClock } from "../src/core/clock.ts";
import { MemoryStore } from "../src/core/memory-store.ts";
import { createRuntime } from "../src/node/runtime.ts";
import { SqliteStore } from "../src/node/sqlite-store.ts";
import { finishReply, planReply, tempDir, toolReply } from "./helpers.ts";

const KEY = "exa_live_SUPERSECRET_0123456789";

describe("Secret exposure (EXAONE provider wired through createRuntime, MOCKED transport)", () => {
  it("the API key appears nowhere in persisted state, audit logs, errors, or outbound prompts", async () => {
    const { dir, cleanup } = tempDir();
    // A workspace file that happens to contain the secret (e.g. a leaked .env copy).
    writeFileSync(join(dir, "leaky.txt"), `EXAONE_API_KEY=${KEY}\nAuthorization: Bearer ${KEY}\n`);

    const replies = [planReply(), toolReply("fs.read", { path: "leaky.txt" }), finishReply(`I saw ${KEY}`)];
    const requests: string[] = [];
    const fetchImpl = (async (_u: string | URL, init?: RequestInit) => {
      requests.push(String(init?.body));
      const content = replies.shift()!;
      return new Response(
        JSON.stringify({ choices: [{ message: { content } }], usage: { prompt_tokens: 1, completion_tokens: 1 } }),
        { status: 200 },
      );
    }) as typeof fetch;

    const store = new MemoryStore();
    const rt = createRuntime({
      workspace: dir,
      providers: ["exaone"],
      fallbackAllowed: false,
      dataConsent: ["exaone"],
      env: { EXAONE_API_KEY: KEY },
      fetchImpl,
      store,
      clock: new ManualClock(),
    });
    const t = await rt.engine.submit({ goal: "read leaky.txt" });
    await rt.engine.runTask(t.id);

    const dump = JSON.stringify({
      tasks: await store.listTasks(),
      events: await store.listEvents(),
      usage: await store.listUsage(),
      approvals: await store.listApprovals(),
    });
    assert.ok(!dump.includes(KEY), "key leaked into persisted state");
    assert.ok(!dump.includes("SUPERSECRET"), "partial key leaked");
    // Step 2's prompt included the file contents; it must already be redacted before leaving the machine.
    assert.ok(requests.length >= 3);
    assert.ok(!requests.slice(1).some((r) => r.includes(KEY)), "key was sent back to the model");
    cleanup();
  });

  it("a failing provider call does not put the key in the task error", async () => {
    const { dir, cleanup } = tempDir();
    const fetchImpl = (async () => new Response(`bad key ${KEY}`, { status: 401 })) as typeof fetch;
    const store = new MemoryStore();
    const rt = createRuntime({
      workspace: dir,
      providers: ["exaone"],
      fallbackAllowed: false,
      dataConsent: ["exaone"],
      env: { EXAONE_API_KEY: KEY },
      fetchImpl,
      store,
    });
    const t = await rt.engine.submit({ goal: "x" });
    await rt.engine.runTask(t.id);
    const saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "FAILED");
    assert.ok(!JSON.stringify(await store.listEvents()).includes(KEY));
    assert.ok(!(saved.error ?? "").includes(KEY));
    cleanup();
  });

  it("the runtime refuses to call an external provider without the owner's data-transfer consent", async () => {
    const { dir, cleanup } = tempDir();
    let called = false;
    const fetchImpl = (async () => ((called = true), new Response("{}"))) as typeof fetch;
    const store = new MemoryStore();
    const rt = createRuntime({
      workspace: dir,
      providers: ["exaone"],
      fallbackAllowed: false,
      dataConsent: [], // no consent
      env: { EXAONE_API_KEY: KEY },
      fetchImpl,
      store,
    });
    const t = await rt.engine.submit({ goal: "private data" });
    await rt.engine.runTask(t.id);
    assert.equal(called, false, "no network call may happen without consent");
    const saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "FAILED", "consent is not something a retry can fix, so it is not retried");
    assert.match(saved.error ?? "", /--consent exaone/, "the error tells the owner exactly what to do");
    cleanup();
  });
});

describe("CLI smoke (real process)", () => {
  const cli = join(import.meta.dirname, "..", "src", "node", "cli.ts");
  const run = (args: string[], cwd: string, env: Record<string, string> = {}) =>
    spawnSync(process.execPath, [cli, ...args], { cwd, encoding: "utf8", env: { ...process.env, EXAONE_API_KEY: "", ...env } });

  it("prints help, lists an empty task table, and reports a clear error when the key is missing", () => {
    const { dir, cleanup } = tempDir();
    assert.match(run(["--help"], dir).stdout, /continuity-first personal agent/);

    const miss = run(["run", "hello", "--workspace", dir], dir);
    assert.equal(miss.status, 1);
    assert.match(miss.stderr, /EXAONE_API_KEY/);

    const list = run(["list", "--workspace", dir, "--providers", "ollama"], dir, { OLLAMA_MODEL: "x" });
    assert.equal(list.status, 0, list.stderr);
    assert.equal(list.stdout.trim(), "");
    cleanup();
  });

  it("submits a scheduled task into the SQLite file without running it", async () => {
    const { dir, cleanup } = tempDir();
    const at = new Date(Date.now() + 3_600_000).toISOString();
    const r = run(["run", "later job", "--at", at, "--workspace", dir, "--providers", "ollama", "--check", "file_exists:out.txt"], dir, { OLLAMA_MODEL: "x" });
    assert.equal(r.status, 0, r.stderr);
    const store = new SqliteStore(join(dir, ".aeon", "aeon.db"));
    const [task] = await store.listTasks();
    assert.equal(task!.status, "PENDING");
    assert.equal(task!.criteria[0]!.kind, "file_exists");
    assert.ok(task!.runAfter > Date.now());
    store.close();
    cleanup();
  });
});
