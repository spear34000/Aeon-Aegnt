import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { ManualClock } from "../src/core/clock.ts";
import { MemoryStore } from "../src/core/memory-store.ts";
import { MockProvider } from "../src/core/models/mock.ts";
import type { Tool } from "../src/core/tools.ts";
import { SqliteStore } from "../src/node/sqlite-store.ts";
import { finishReply, harness, planReply, tempDir, toolReply } from "./helpers.ts";

const fixture = (name: string): string => join(import.meta.dirname, "fixtures", name);

describe("Restart / crash recovery (real processes, real SQLite file)", () => {
  it("a process killed mid-task (exit 137) is recovered by a new process from the last checkpoint", async () => {
    const { dir, cleanup } = tempDir();
    const dbPath = join(dir, "state.db");

    const child = spawnSync(process.execPath, [fixture("crash-child.ts"), dir, dbPath], { encoding: "utf8" });
    assert.equal(child.status, 137, `child should have been killed: ${child.stderr}`);
    const taskId = child.stdout.trim().split("\n")[0]!;

    // What the dead process left behind:
    const store = new SqliteStore(dbPath);
    const left = (await store.getTask(taskId))!;
    assert.equal(left.status, "RUNNING", "no clean shutdown happened");
    assert.equal(left.steps.length, 1, "the finished step was checkpointed before the crash");
    assert.equal(existsSync(join(dir, "a.txt")), true);
    assert.equal(left.plan.length, 2, "plan survived too");

    // A fresh runtime (new process in real life) one lease-TTL later picks the task up.
    const model = new MockProvider("m", [
      toolReply("fs.write", { path: "b.txt", content: "two" }),
      finishReply("wrote both files"),
    ]);
    const h = harness({
      providers: [model],
      store,
      workspace: dir,
      clock: new ManualClock(Date.now() + 400_000),
      holderId: "recovery-process",
    });
    assert.deepEqual(await h.engine.tick().then((r) => r.map((x) => x.result)), ["ran"]);

    const done = (await store.getTask(taskId))!;
    assert.equal(done.status, "COMPLETED");
    assert.equal(done.verification?.status, "verified", "b.txt really exists");
    assert.equal(readFileSync(join(dir, "b.txt"), "utf8"), "two");
    assert.equal(done.steps.length, 2);
    assert.match(model.calls[0]!.messages.at(-1)!.content, /Step 0 OK tool=fs\.write/, "model was shown the pre-crash history");
    assert.equal(model.calls.length, 2, "no re-planning after restart");
    assert.ok((await store.listEvents(taskId)).some((e) => e.type === "task_claimed" && e.detail.holder === "recovery-process"));
    store.close();
    cleanup();
  });

  it("two OS processes racing to claim the same task: exactly one wins", async () => {
    const { dir, cleanup } = tempDir();
    const dbPath = join(dir, "state.db");
    const store = new SqliteStore(dbPath);
    const h = harness({ providers: [new MockProvider("m", [])], store, workspace: dir });
    const t = await h.engine.submit({ goal: "race" });
    store.close();

    const startAt = String(Date.now() + 700);
    const run = (holder: string) =>
      new Promise<string>((resolve) => {
        const p = spawn(process.execPath, [fixture("claim-child.ts"), dbPath, t.id, holder, startAt]);
        let out = "";
        p.stdout.on("data", (d) => (out += d));
        p.on("close", () => resolve(out.trim()));
      });
    const results = await Promise.all([run("cloud"), run("laptop")]);
    assert.equal(results.filter((r) => r.startsWith("CLAIMED")).length, 1, `got: ${results.join(" | ")}`);
    assert.equal(results.filter((r) => r === "NO").length, 1);
    cleanup();
  });

  it("a side effect executed just before a crash is NOT repeated when the step is replayed (effect journal)", async () => {
    let executions = 0;
    const counting: Tool = {
      name: "test.count",
      description: "counts executions",
      argsHint: "{}",
      assess: () => ({ risk: "write", summary: "count", resources: [] }),
      run: async () => {
        executions++;
        return { ok: true, output: "counted" };
      },
    };

    // A store that "crashes" exactly once: right after the effect was journaled, before the step checkpoint.
    class CrashOnce extends MemoryStore {
      armed = true;
      override async commit(...a: Parameters<MemoryStore["commit"]>) {
        const patch = a[2];
        if (this.armed && patch.steps && patch.steps.length === 1) {
          this.armed = false;
          throw new Error("simulated crash before checkpoint");
        }
        return super.commit(...a);
      }
    }
    const store = new CrashOnce();
    const model = new MockProvider("m", [
      planReply(),
      toolReply("test.count", {}),
      // If the agent re-asked the model about step 0 after the crash, this reply would be consumed there
      // and step 0 would be lost. It must be consumed by step 1 instead.
      finishReply("ok"),
    ]);
    const h = harness({ providers: [model], store });
    h.tools.register(counting);

    const t = await h.engine.submit({ goal: "count once" });
    await h.engine.runTask(t.id); // crashes after executing the effect
    assert.equal(executions, 1);
    assert.equal((await store.getTask(t.id))!.status, "RETRY_WAIT");
    assert.equal((await store.getTask(t.id))!.steps.length, 0, "checkpoint never landed");
    assert.equal((await store.getTask(t.id))!.pendingAction?.tool, "test.count", "the decision itself was persisted first");

    h.clock.advance(60_000);
    await h.engine.tick();
    assert.equal(executions, 1, "replayed step reused the journaled result instead of executing again");
    const done = (await store.getTask(t.id))!;
    assert.equal(done.steps[0]?.tool, "test.count", "step 0 is the replayed persisted action");
    assert.equal(done.steps[0]?.output, "counted");
    assert.equal(model.calls.length, 3, "plan + decide(step 0) + decide(step 1): the model was not re-asked about step 0");
    assert.equal((await store.getTask(t.id))!.status, "COMPLETED");
    assert.ok((await store.listEvents(t.id)).some((e) => e.type === "effect_reused"));
    h.cleanup();
  });
});
