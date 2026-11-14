import assert from "node:assert/strict";
import { readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { MemoryStore } from "../src/core/memory-store.ts";
import { FileStore } from "../src/node/file-store.ts";
import { SqliteStore } from "../src/node/sqlite-store.ts";
import { tempDir } from "./helpers.ts";
import { runStoreConformance } from "./store-conformance.ts";

runStoreConformance("MemoryStore", async () => ({ store: new MemoryStore(), cleanup: () => {} }));

runStoreConformance("SqliteStore", async () => {
  const { dir, cleanup } = tempDir();
  const store = new SqliteStore(join(dir, "t.db"));
  return {
    store,
    cleanup: () => {
      store.close();
      cleanup();
    },
  };
});

runStoreConformance("FileStore", async () => {
  const { dir, cleanup } = tempDir();
  const store = new FileStore(join(dir, "t.json"));
  return { store, cleanup: () => { store.flush(); cleanup(); } };
});

describe("FileStore persistence", () => {
  it("survives a restart: tasks, approvals, effects and usage come back as they were", async () => {
    const { dir, cleanup } = tempDir();
    const file = join(dir, "s.json");
    const a = new FileStore(file);
    await a.createTask({ id: "t1", goal: "g", status: "PENDING", createdAt: 1, updatedAt: 1, runAfter: 0, plan: [], steps: [], criteria: [], maxSteps: 5, lease: null, fencing: 0, attempts: 0 } as never);
    await a.createApproval({ id: "appr_1", taskId: "t1", actionKey: "k", tool: "fs.delete", summary: "s", risk: "delete", resources: [], irreversible: true, status: "pending", createdAt: 1, expiresAt: 99 });
    await a.addUsage({ at: 5, provider: "p", model: "m", inputTokens: 1, outputTokens: 2, reasoningTokens: 0, ok: true } as never);
    a.flush();
    const b = new FileStore(file);
    assert.equal((await b.getTask("t1"))?.goal, "g");
    assert.equal((await b.getApproval("appr_1"))?.tool, "fs.delete");
    assert.equal((await b.listUsage()).length, 1);
    cleanup();
  });

  it("a damaged file is kept aside and the store starts empty instead of refusing to start", async () => {
    const { dir, cleanup } = tempDir();
    const file = join(dir, "s.json");
    writeFileSync(file, "{ not json");
    const s = new FileStore(file);
    assert.deepEqual(await s.listTasks(), []);
    assert.ok(readdirSync(dir).some((f) => f.startsWith("s.json.corrupt-")));
    cleanup();
  });
});
