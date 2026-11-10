import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ManualClock } from "../src/core/clock.ts";
import { MemoryStore } from "../src/core/memory-store.ts";
import { Redactor } from "../src/core/redact.ts";
import type { VerifierEnv } from "../src/core/verify.ts";
import { WorkflowEngine, type RunContext, type RunOutcome, type TaskRunner } from "../src/core/workflow.ts";
import type { Task } from "../src/core/types.ts";

const env = (files: Record<string, string> = {}): VerifierEnv => ({
  fileExists: async (p) => p in files,
  readText: async (p) => files[p] ?? null,
  sha256File: async () => null,
  httpStatus: async () => 200,
});

function mk(runner: TaskRunner, over: { store?: MemoryStore; clock?: ManualClock; holder?: string; files?: Record<string, string> } = {}) {
  const store = over.store ?? new MemoryStore();
  const clock = over.clock ?? new ManualClock();
  const engine = new WorkflowEngine({
    store,
    clock,
    runner,
    verifierEnv: env(over.files),
    redactor: new Redactor(),
    holderId: over.holder ?? "A",
    leaseTtlMs: 10_000,
    retryBaseMs: 1000,
  });
  return { store, clock, engine };
}

const done = (r = "ok"): TaskRunner => ({ run: async () => ({ kind: "done", result: r }) });

describe("WorkflowEngine", () => {
  it("runs a due task to COMPLETED and records it as unverified when no criteria exist", async () => {
    const { engine, store } = mk(done());
    const t = await engine.submit({ goal: "x" });
    const s = await engine.runTask(t.id);
    assert.deepEqual(s, { taskId: t.id, result: "ran", status: "COMPLETED" });
    const saved = (await store.getTask(t.id))!;
    assert.equal(saved.verification?.status, "unverified");
    assert.equal(saved.lease, null);
  });

  it("marks the task FAILED when the runner claims success but a user criterion fails", async () => {
    const { engine, store } = mk(done("I wrote the file!"));
    const t = await engine.submit({ goal: "x", criteria: [{ kind: "file_exists", path: "out.txt" }] });
    await engine.runTask(t.id);
    const saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "FAILED");
    assert.equal(saved.verification?.status, "failed");
    assert.match(saved.error ?? "", /verification failed/);
  });

  it("marks COMPLETED+verified only when every criterion passes against reality", async () => {
    const { engine, store } = mk(done(), { files: { "out.txt": "hello world" } });
    const t = await engine.submit({
      goal: "x",
      criteria: [
        { kind: "file_exists", path: "out.txt" },
        { kind: "file_contains", path: "out.txt", text: "hello" },
      ],
    });
    await engine.runTask(t.id);
    const saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "COMPLETED");
    assert.equal(saved.verification?.status, "verified");
    assert.equal(saved.verification?.evidence.length, 2);
  });

  it("does not run scheduled tasks early; runs them when due (scheduled execution)", async () => {
    const { engine, store, clock } = mk(done());
    const t = await engine.submit({ goal: "later", runAfter: clock.now() + 60_000 });
    assert.deepEqual(await engine.tick(), []);
    assert.equal((await store.getTask(t.id))!.status, "PENDING");
    clock.advance(60_000);
    const out = await engine.tick();
    assert.equal(out.length, 1);
    assert.equal((await store.getTask(t.id))!.status, "COMPLETED");
  });

  it("retries transient failures with exponential backoff, then fails after maxAttempts", async () => {
    let calls = 0;
    const runner: TaskRunner = {
      run: async (): Promise<RunOutcome> => {
        calls++;
        return { kind: "fail", error: "network down", retryable: true };
      },
    };
    const { engine, store, clock } = mk(runner);
    const t = await engine.submit({ goal: "x", maxAttempts: 3 });

    await engine.runTask(t.id);
    let saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "RETRY_WAIT");
    assert.equal(saved.runAfter, clock.now() + 1000);
    assert.equal((await engine.runTask(t.id)).result, "not_claimable", "too early to retry");

    clock.advance(1000);
    await engine.runTask(t.id);
    saved = (await store.getTask(t.id))!;
    assert.equal(saved.runAfter, clock.now() + 2000, "backoff doubles");

    clock.advance(2000);
    await engine.runTask(t.id);
    saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "FAILED");
    assert.equal(calls, 3);
  });

  it("does not retry non-retryable failures", async () => {
    const { engine, store } = mk({ run: async () => ({ kind: "fail", error: "bad key", retryable: false }) });
    const t = await engine.submit({ goal: "x" });
    await engine.runTask(t.id);
    assert.equal((await store.getTask(t.id))!.status, "FAILED");
  });

  it("converts a crashing runner into a retryable failure", async () => {
    const { engine, store } = mk({
      run: async () => {
        throw new Error("kaboom");
      },
    });
    const t = await engine.submit({ goal: "x" });
    await engine.runTask(t.id);
    const saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "RETRY_WAIT");
    assert.match(saved.error ?? "", /kaboom/);
  });

  it("two runtimes racing for the same task: exactly one runs it (duplicate prevention)", async () => {
    const store = new MemoryStore();
    const clock = new ManualClock();
    let runs = 0;
    const runner: TaskRunner = {
      run: async () => {
        runs++;
        await new Promise((r) => setTimeout(r, 20));
        return { kind: "done", result: "ok" };
      },
    };
    const a = mk(runner, { store, clock, holder: "cloud" }).engine;
    const b = mk(runner, { store, clock, holder: "laptop" }).engine;
    const t = await a.submit({ goal: "x" });
    const results = await Promise.all([a.runTask(t.id), b.runTask(t.id)]);
    assert.equal(runs, 1);
    assert.deepEqual(results.map((r) => r.result).sort(), ["not_claimable", "ran"]);
  });

  it("recovers a task whose holder died (lease expiry) and rejects the zombie's later writes", async () => {
    const store = new MemoryStore();
    const clock = new ManualClock();
    // Holder A claims, then "dies" (never releases, never heartbeats).
    const first = mk(done(), { store, clock, holder: "A" });
    const t = await first.engine.submit({ goal: "x" });
    const zombie = (await store.claim(t.id, "A", 10_000, clock.now()))!;

    const second = mk(done("recovered"), { store, clock, holder: "B" });
    assert.equal((await second.engine.runTask(t.id)).result, "not_claimable", "lease still live");

    clock.advance(10_001);
    assert.equal((await second.engine.tick()).length, 1);
    const saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "COMPLETED");
    assert.equal(saved.result, "recovered");

    // The zombie wakes up and tries to write with its old token.
    await assert.rejects(store.commit(t.id, zombie.token, { note: "zombie" }, clock.now()), /stale/i);
    assert.notEqual((await store.getTask(t.id))!.note, "zombie");
  });

  it("a runner that loses its lease mid-run changes nothing and reports lease_lost", async () => {
    const store = new MemoryStore();
    const clock = new ManualClock();
    const slow: TaskRunner = {
      run: async (_t: Task, ctx: RunContext) => {
        clock.advance(20_000); // our lease (10s) expires while we work
        await store.claim(_t.id, "B", 10_000, clock.now()); // someone else takes over
        await ctx.checkpoint({ note: "should be rejected" });
        return { kind: "done", result: "never" };
      },
    };
    const { engine } = mk(slow, { store, clock });
    const t = await engine.submit({ goal: "x" });
    const s = await engine.runTask(t.id);
    assert.equal(s.result, "lease_lost");
    const saved = (await store.getTask(t.id))!;
    assert.equal(saved.status, "RUNNING", "the new holder owns it");
    assert.notEqual(saved.note, "should be rejected");
  });

  it("cancels a pending task immediately and a running task at the next step boundary", async () => {
    const store = new MemoryStore();
    const runner: TaskRunner = {
      run: async (t, ctx) => {
        await engine.cancel(t.id); // user hits cancel while we are running
        const fresh = await ctx.reload();
        return fresh.cancelRequested ? { kind: "cancelled" } : { kind: "done", result: "ok" };
      },
    };
    const { engine } = mk(runner, { store });
    const pending = await engine.submit({ goal: "p" });
    assert.equal((await engine.cancel(pending.id)).status, "CANCELLED");
    assert.equal((await engine.runTask(pending.id)).result, "not_claimable");

    const running = await engine.submit({ goal: "r" });
    await engine.runTask(running.id);
    assert.equal((await store.getTask(running.id))!.status, "CANCELLED");
  });

  it("a cancel request on a crashed (expired-lease) task is honoured on recovery without running it", async () => {
    const store = new MemoryStore();
    const clock = new ManualClock();
    let ran = false;
    const { engine } = mk({ run: async () => ((ran = true), { kind: "done", result: "x" }) }, { store, clock });
    const t = await engine.submit({ goal: "x" });
    await store.claim(t.id, "dead", 1000, clock.now());
    await engine.cancel(t.id); // lease live => flagged only
    assert.equal((await store.getTask(t.id))!.cancelRequested, true);
    clock.advance(2000);
    await engine.tick();
    assert.equal((await store.getTask(t.id))!.status, "CANCELLED");
    assert.equal(ran, false);
  });

  it("quota waits resume at the given time", async () => {
    let n = 0;
    const runner: TaskRunner = {
      run: async (): Promise<RunOutcome> =>
        ++n === 1 ? { kind: "wait_quota", until: clock.now() + 3_600_000, note: "limit" } : { kind: "done", result: "ok" },
    };
    const { engine, store, clock } = mk(runner);
    const t = await engine.submit({ goal: "x" });
    await engine.runTask(t.id);
    assert.equal((await store.getTask(t.id))!.status, "WAITING_FOR_QUOTA");
    assert.deepEqual(await engine.tick(), []);
    clock.advance(3_600_000);
    await engine.tick();
    assert.equal((await store.getTask(t.id))!.status, "COMPLETED");
  });

  it("user confirmation is recorded separately from automated verification", async () => {
    const { engine, store } = mk(done());
    const t = await engine.submit({ goal: "x" });
    await engine.runTask(t.id);
    assert.equal((await store.getTask(t.id))!.verification?.status, "unverified");
    await engine.confirmByUser(t.id, "looks right");
    assert.equal((await store.getTask(t.id))!.verification?.status, "user_confirmed");
    assert.ok((await store.listEvents(t.id)).some((e) => e.type === "user_confirmed"));
  });
});
