import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { StaleLeaseError } from "../src/core/errors.ts";
import type { Store } from "../src/core/store.ts";
import type { Approval, Task } from "../src/core/types.ts";

export function mkTask(id: string, over: Partial<Task> = {}): Task {
  return {
    id,
    goal: "g",
    status: "PENDING",
    createdAt: 1000,
    updatedAt: 1000,
    runAfter: 1000,
    attempt: 0,
    maxAttempts: 3,
    maxSteps: 10,
    plan: [],
    criteria: [],
    steps: [],
    lease: null,
    fence: 0,
    cancelRequested: false,
    ...over,
  };
}

const mkApproval = (id: string, taskId: string, expiresAt: number): Approval => ({
  id,
  taskId,
  actionKey: "k1",
  tool: "fs.delete",
  summary: "delete x",
  risk: "delete",
  status: "pending",
  createdAt: 1000,
  expiresAt,
});

/** Behavioural contract every Store implementation must satisfy (decision D2: lease + fencing). */
export function runStoreConformance(name: string, make: () => Promise<{ store: Store; cleanup: () => void }>): void {
  describe(`Store conformance: ${name}`, () => {
    it("allows exactly one claimant while a lease is live (duplicate-run prevention)", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("t1"));
      const [a, b] = await Promise.all([store.claim("t1", "A", 1000, 2000), store.claim("t1", "B", 1000, 2000)]);
      assert.equal([a, b].filter(Boolean).length, 1);
      cleanup();
    });

    it("issues strictly increasing fencing tokens and re-claims after lease expiry", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("t1"));
      const first = await store.claim("t1", "A", 1000, 2000);
      assert.equal(first?.token, 1);
      assert.equal(await store.claim("t1", "B", 1000, 2500), null, "lease still live");
      const second = await store.claim("t1", "B", 1000, 3001);
      assert.equal(second?.token, 2);
      cleanup();
    });

    it("rejects writes from a stale holder after takeover (fencing)", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("t1"));
      const a = (await store.claim("t1", "A", 1000, 2000))!;
      const b = (await store.claim("t1", "B", 1000, 3500))!;
      await assert.rejects(store.commit("t1", a.token, { note: "stale" }, 3600), StaleLeaseError);
      await assert.rejects(store.putEffect("t1", "k", "v", a.token, 3600), StaleLeaseError);
      await assert.rejects(store.heartbeat("t1", a.token, 1000, 3600), StaleLeaseError);
      await assert.rejects(store.release("t1", a.token, { status: "FAILED" }, 3600), StaleLeaseError);
      const t = await store.commit("t1", b.token, { note: "fresh" }, 3600);
      assert.equal(t.note, "fresh");
      cleanup();
    });

    it("rejects commits after the holder's own lease expired", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("t1"));
      const a = (await store.claim("t1", "A", 1000, 2000))!;
      await assert.rejects(store.commit("t1", a.token, { note: "late" }, 3000), StaleLeaseError);
      cleanup();
    });

    it("heartbeat extends the lease and release frees it", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("t1"));
      const a = (await store.claim("t1", "A", 1000, 2000))!;
      await store.heartbeat("t1", a.token, 1000, 2900);
      assert.equal(await store.claim("t1", "B", 1000, 3500), null);
      await store.release("t1", a.token, { status: "PENDING" }, 3500);
      assert.ok(await store.claim("t1", "B", 1000, 3500));
      cleanup();
    });

    it("respects runAfter (scheduled tasks) and only lists due tasks as runnable", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("later", { runAfter: 5000 }));
      await store.createTask(mkTask("now", { runAfter: 1000 }));
      assert.equal(await store.claim("later", "A", 1000, 4000), null);
      assert.deepEqual((await store.findRunnable(4000)).map((t) => t.id), ["now"]);
      assert.deepEqual((await store.findRunnable(5000)).map((t) => t.id).sort(), ["later", "now"]);
      cleanup();
    });

    it("lists RUNNING tasks whose lease expired as runnable (crash recovery)", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("t1"));
      await store.claim("t1", "A", 1000, 2000);
      assert.deepEqual(await store.findRunnable(2500), []);
      assert.deepEqual((await store.findRunnable(3001)).map((t) => t.id), ["t1"]);
      cleanup();
    });

    it("does not make waiting-for-approval or terminal tasks runnable", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("w", { status: "WAITING_FOR_APPROVAL" }));
      await store.createTask(mkTask("d", { status: "COMPLETED" }));
      assert.deepEqual(await store.findRunnable(9999999), []);
      cleanup();
    });

    it("persists effect journal entries", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("t1"));
      const a = (await store.claim("t1", "A", 1000, 2000))!;
      assert.equal(await store.getEffect("t1", "k"), null);
      await store.putEffect("t1", "k", "v", a.token, 2100);
      assert.equal(await store.getEffect("t1", "k"), "v");
      cleanup();
    });

    it("approvals: decide once, expire, and never resurrect an expired approval", async () => {
      const { store, cleanup } = await make();
      await store.createTask(mkTask("t1"));
      await store.createApproval(mkApproval("a1", "t1", 5000));
      const ok = await store.decideApproval("a1", "approved", 4000);
      assert.equal(ok.status, "approved");
      await assert.rejects(store.decideApproval("a1", "denied", 4100), /already approved/);

      await store.createApproval({ ...mkApproval("a2", "t1", 5000), actionKey: "k2" });
      const expired = await store.expireApprovals(6000);
      assert.deepEqual(expired.map((a) => a.id), ["a2"]);
      await assert.rejects(store.decideApproval("a2", "approved", 6100), /already expired/);
      assert.equal((await store.findApproval("t1", "k2"))?.status, "expired");

      await store.createApproval({ ...mkApproval("a3", "t1", 5000), actionKey: "k3" });
      await assert.rejects(store.decideApproval("a3", "approved", 5000), /expired/);
      cleanup();
    });

    it("stores audit events and usage", async () => {
      const { store, cleanup } = await make();
      await store.addEvent({ at: 1, taskId: "t1", type: "x", detail: { a: 1 } });
      await store.addEvent({ at: 2, taskId: null, type: "y", detail: {} });
      assert.equal((await store.listEvents("t1")).length, 1);
      assert.equal((await store.listEvents()).length, 2);
      await store.addUsage({ at: 10, provider: "p", model: "m", taskId: "t1", inputTokens: 1, outputTokens: 2, reasoningTokens: 3, ok: true });
      assert.equal((await store.listUsage(5)).length, 1);
      assert.equal((await store.listUsage(11)).length, 0);
      cleanup();
    });
  });
}
