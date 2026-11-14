import assert from "node:assert/strict";
import { join } from "node:path";
import { describe, it } from "node:test";
import { DAILY_RUN_CAP, decide, LATE_WINDOW_MS, lastDueAt, MAX_ROUTINES, nextDueAt, parseSchedule, RoutineStore, type Routine, type Schedule } from "../src/node/routines.ts";
import { tempDir } from "./helpers.ts";

// Local times, so the tests mean the same thing in any time zone. 2026-11-16 is a Monday.
const L = (day: number, h: number, m = 0) => new Date(2026, 10, day, h, m, 0, 0).getTime();
const MON = 16;
const daily: Schedule = { repeat: "daily", time: "09:00" };
const weekdays: Schedule = { repeat: "weekdays", time: "09:00" };
const monday: Schedule = { repeat: "weekly", time: "18:30", day: 1 };

describe("schedules", () => {
  it("knows when a schedule was last due and when it is next due, for daily, weekday and weekly routines", () => {
    assert.equal(lastDueAt(daily, L(MON, 10)), L(MON, 9), "today's time has passed");
    assert.equal(lastDueAt(daily, L(MON, 8)), L(MON - 1, 9), "today's time has not come: yesterday's");
    assert.equal(nextDueAt(daily, L(MON, 8)), L(MON, 9));
    assert.equal(nextDueAt(daily, L(MON, 10)), L(MON + 1, 9));
    assert.equal(lastDueAt(weekdays, L(MON, 10)), L(MON, 9));
    assert.equal(lastDueAt(weekdays, L(MON, 8)), L(MON - 3, 9), "before nine on Monday: Friday's");
    assert.equal(nextDueAt(weekdays, L(20, 10)), L(23, 9), "after nine on Friday: Monday's");
    assert.equal(lastDueAt(monday, L(MON, 19)), L(MON, 18, 30));
    assert.equal(lastDueAt(monday, L(MON + 3, 12)), L(MON, 18, 30), "Thursday: Monday's");
    assert.equal(nextDueAt(monday, L(MON, 19)), L(MON + 7, 18, 30));
  });

  it("accepts only real schedules", () => {
    assert.deepEqual(parseSchedule({ repeat: "weekly", time: "07:05", day: 0 }), { repeat: "weekly", time: "07:05", day: 0 });
    assert.deepEqual(parseSchedule({ repeat: "daily", time: "23:59", day: 3 }), { repeat: "daily", time: "23:59" }, "a day means nothing for a daily routine and is dropped");
    for (const bad of [null, "daily", { repeat: "monthly", time: "09:00" }, { repeat: "daily", time: "9:00" }, { repeat: "daily", time: "24:00" }, { repeat: "daily", time: "09:60" }, { repeat: "weekly", time: "09:00" }, { repeat: "weekly", time: "09:00", day: 7 }, { repeat: "weekly", time: "09:00", day: 1.5 }]) {
      assert.equal(typeof parseSchedule(bad), "string", JSON.stringify(bad));
    }
  });
});

const routine = (over: Partial<Routine> = {}): Routine => ({ id: "rtn_0123456789", title: "Sum up my meeting notes", schedule: weekdays, enabled: true, createdAt: L(MON - 7, 12), handledDue: 0, ...over });

describe("what to do about a routine", () => {
  it("runs it when its time has come, once, and not again for the same time", () => {
    const d = decide(routine(), L(MON, 9, 1));
    assert.deepEqual(d, { run: true, due: L(MON, 9) });
    assert.deepEqual(decide(routine({ handledDue: L(MON, 9) }), L(MON, 9, 30)), { run: false });
  });

  it("does not run what was due before the routine existed, or while it is switched off", () => {
    assert.deepEqual(decide(routine({ createdAt: L(MON, 10) }), L(MON, 11)), { run: false }, "made at ten, the nine o'clock run is not made up");
    assert.deepEqual(decide(routine({ enabled: false }), L(MON, 9, 1)), { run: false });
  });

  it("after downtime makes at most one late run, and only within three hours; later than that the time is skipped", () => {
    const soon = decide(routine(), L(MON, 9) + LATE_WINDOW_MS);
    assert.deepEqual(soon, { run: true, due: L(MON, 9) }, "just inside the window");
    const late = decide(routine(), L(MON, 9) + LATE_WINDOW_MS + 1);
    assert.deepEqual(late, { run: false, skipDue: L(MON, 9) }, "too late: skipped, and the next time counts");
    // Off for four days: one decision about the most recent time only, never a burst.
    assert.deepEqual(decide(routine({ handledDue: L(MON - 3, 9) }), L(MON + 1, 9, 5)), { run: true, due: L(MON + 1, 9) });
  });
});

describe("the store and its timer", () => {
  const setup = () => {
    const t = tempDir();
    const file = join(t.dir, "routines.json");
    return { ...t, file, store: () => new RoutineStore(file) };
  };

  it("makes, changes and deletes routines, with checks; a changed time or switching it back on starts from now", () => {
    const s = setup();
    const store = s.store();
    const r = store.create("  Sum up\nmy meeting notes ", weekdays, L(MON, 8));
    assert.deepEqual([r.title, r.enabled, r.handledDue], ["Sum up my meeting notes", true, 0]);
    assert.throws(() => store.create("x", weekdays, 1), /title/);
    assert.throws(() => store.create("A fine title", { repeat: "daily", time: "nine" }, 1), /time/);
    const moved = store.update(r.id, { schedule: { repeat: "daily", time: "07:00" } }, L(MON, 8))!;
    assert.equal(moved.handledDue, L(MON, 7), "07:00 today has passed: it is not run late because the owner changed the time");
    const off = store.update(r.id, { enabled: false }, L(MON, 8))!;
    const on = store.update(r.id, { enabled: true }, L(MON + 1, 12))!;
    assert.deepEqual([off.enabled, on.enabled, on.handledDue], [false, true, L(MON + 1, 7)]);
    assert.throws(() => store.update(r.id, { enabled: "yes" }, 1), /true or false/);
    assert.equal(store.update("rtn_ffffffffff", { enabled: false }, 1), null);
    assert.equal(store.remove(r.id), true);
    assert.equal(store.remove(r.id), false);
    for (let i = 0; i < MAX_ROUTINES; i++) store.create(`Routine number ${i}`, daily, 1);
    assert.throws(() => store.create("One too many", daily, 1), /at most/);
    s.cleanup();
  });

  it("submits the goal as an ordinary task when due, once, and remembers it across a restart", async () => {
    const s = setup();
    const r = s.store().create("Sum up my meeting notes", weekdays, L(MON - 7, 12));
    const goals: string[] = [];
    const submit = async (goal: string) => (goals.push(goal), { id: `t${goals.length}` });
    assert.equal(await s.store().tick(L(MON, 8), submit), 0, "not yet");
    assert.equal(await s.store().tick(L(MON, 9, 2), submit), 1);
    assert.equal(await s.store().tick(L(MON, 9, 3), submit), 0, "the same time is not run twice");
    assert.deepEqual(goals, ["Sum up my meeting notes"]);
    const saved = s.store().list()[0]!;
    assert.deepEqual([saved.lastTaskId, saved.handledDue, saved.lastRunAt], ["t1", L(MON, 9), L(MON, 9, 2)]);
    assert.equal(await s.store().tick(L(MON + 1, 9, 2), submit), 1, "next weekday");
    assert.equal(r.id, s.store().list()[0]!.id);
    s.cleanup();
  });

  it("a time that is skipped as too late is not run later; a failing submit is tried again until the window closes", async () => {
    const s = setup();
    s.store().create("Sum up my meeting notes", weekdays, L(MON - 7, 12));
    let fail = true;
    const goals: string[] = [];
    const submit = async (goal: string) => {
      if (fail) throw new Error("engine busy");
      goals.push(goal);
      return { id: "t" };
    };
    assert.equal(await s.store().tick(L(MON, 9, 1), submit), 0);
    assert.equal(await s.store().tick(L(MON, 9, 2), submit), 0, "still due");
    fail = false;
    assert.equal(await s.store().tick(L(MON, 9, 3), submit), 1, "made when the engine is back, inside the window");
    // Off the whole of Tuesday morning: the nine o'clock time is skipped, not made up at noon.
    assert.equal(await s.store().tick(L(MON + 1, 12, 30), submit), 0);
    assert.equal(s.store().list()[0]!.handledDue, L(MON + 1, 9), "skipped time is recorded");
    assert.equal(await s.store().tick(L(MON + 1, 12, 31), submit), 0);
    assert.equal(goals.length, 1);
    s.cleanup();
  });

  it(`makes at most ${DAILY_RUN_CAP} tasks a day in all, counted in the store, and runNow counts too`, async () => {
    const s = setup();
    for (let i = 0; i < 12; i++) s.store().create(`Routine ${String.fromCharCode(65 + i)} goal`, daily, L(MON - 1, 12));
    let n = 0;
    const submit = async () => ({ id: `t${++n}` });
    assert.equal(await s.store().tick(L(MON, 9, 1), submit), DAILY_RUN_CAP);
    assert.equal(await new RoutineStore(s.file).tick(L(MON, 9, 2), submit), 0, "a new instance (a restart) sees the same count");
    assert.equal(s.store().runsToday(L(MON, 10)), DAILY_RUN_CAP);
    assert.equal(await s.store().runNow(s.store().list()[11]!.id, L(MON, 10), submit), null, "the limit is reached");
    assert.equal(await s.store().tick(L(MON + 1, 9, 1), submit), DAILY_RUN_CAP, "the next day starts again");
    s.cleanup();
  });

  it("an older or damaged file reads as no routines", () => {
    const s = setup();
    assert.deepEqual(s.store().list(), []);
    s.cleanup();
  });
});
