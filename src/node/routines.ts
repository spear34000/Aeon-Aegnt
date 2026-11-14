import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { clean, dayKey } from "./feed-store.ts";

/**
 * Routines: things the owner wants done again and again ("every weekday at 9, sum up my meeting notes"). A routine is a goal
 * and a time; when the time comes the server submits an ordinary task, so everything that applies to a task applies here:
 * approvals still ask, nothing is allowed that a task is not. Only the owner makes or changes a routine (the machine or an
 * admin device). Conservative on purpose:
 *  - local time of the computer running Aeon;
 *  - a run missed while the computer was off is not made up in a burst: at most one late run, and only within three hours of the
 *    time it was due; after that it is skipped and the next time counts;
 *  - at most ten runs a day across all routines, counted in the store so a restart does not reset it;
 *  - no notification: the task is in the task list when the owner looks;
 *  - on the phone-only mode a routine runs only while the app process is alive.
 */

export type Repeat = "daily" | "weekdays" | "weekly";

export interface Schedule {
  repeat: Repeat;
  /** "HH:MM", 24 hours, local time of the computer. */
  time: string;
  /** For "weekly": 0 (Sunday) to 6 (Saturday). */
  day?: number;
}

export interface Routine {
  id: string;
  /** The goal of each task this routine makes, in the owner's words. */
  title: string;
  schedule: Schedule;
  enabled: boolean;
  createdAt: number;
  /** The scheduled time (ms) that was last dealt with, whether it ran or was skipped as too late. */
  handledDue: number;
  lastRunAt?: number;
  lastTaskId?: string;
}

export const MAX_ROUTINES = 20;
export const DAILY_RUN_CAP = 10;
export const LATE_WINDOW_MS = 3 * 3_600_000;
export const TITLE_MAX = 300;

interface RoutineFile {
  version: 1;
  routines: Routine[];
  /** Runs made by routines, by local day (the last 14). */
  runs: Record<string, number>;
}

const HHMM = /^([01]\d|2[0-3]):([0-5]\d)$/;

/** A schedule as the owner may send it, or a reason it is not one. */
export function parseSchedule(raw: unknown): Schedule | string {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) return "schedule must be an object";
  const o = raw as Record<string, unknown>;
  if (o.repeat !== "daily" && o.repeat !== "weekdays" && o.repeat !== "weekly") return 'repeat must be "daily", "weekdays" or "weekly"';
  if (typeof o.time !== "string" || !HHMM.test(o.time)) return 'time must be "HH:MM" (24 hours)';
  if (o.repeat === "weekly") {
    if (typeof o.day !== "number" || !Number.isInteger(o.day) || o.day < 0 || o.day > 6) return "day must be a whole number from 0 (Sunday) to 6 (Saturday)";
    return { repeat: "weekly", time: o.time, day: o.day };
  }
  return { repeat: o.repeat, time: o.time };
}

const at = (d: Date, time: string): Date => {
  const [h, m] = time.split(":").map(Number) as [number, number];
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m, 0, 0);
};

const matches = (s: Schedule, d: Date): boolean => (s.repeat === "daily" ? true : s.repeat === "weekdays" ? d.getDay() >= 1 && d.getDay() <= 5 : d.getDay() === s.day);

/** The most recent time this schedule was due at or before [now] (ms), looking back at most eight days. */
export function lastDueAt(s: Schedule, now: number): number {
  const base = new Date(now);
  for (let back = 0; back <= 8; back++) {
    const day = new Date(base.getFullYear(), base.getMonth(), base.getDate() - back);
    if (!matches(s, day)) continue;
    const t = at(day, s.time).getTime();
    if (t <= now) return t;
  }
  return 0;
}

/** The next time this schedule is due after [now] (ms). */
export function nextDueAt(s: Schedule, now: number): number {
  const base = new Date(now);
  for (let ahead = 0; ahead <= 8; ahead++) {
    const day = new Date(base.getFullYear(), base.getMonth(), base.getDate() + ahead);
    if (!matches(s, day)) continue;
    const t = at(day, s.time).getTime();
    if (t > now) return t;
  }
  return 0;
}

export type RoutineDecision = { run: true; due: number } | { run: false; skipDue?: number };

/** What to do about one routine at [now]: run it, skip a time that is too late to run, or nothing. Pure. */
export function decide(r: Routine, now: number): RoutineDecision {
  if (!r.enabled) return { run: false };
  const due = lastDueAt(r.schedule, now);
  if (due === 0 || due < r.createdAt || due <= r.handledDue) return { run: false };
  if (now - due > LATE_WINDOW_MS) return { run: false, skipDue: due };
  return { run: true, due };
}

export class RoutineStore {
  private readonly file: string;

  constructor(file: string) {
    this.file = file;
  }

  private read(): RoutineFile {
    if (!existsSync(this.file)) return { version: 1, routines: [], runs: {} };
    try {
      const j = JSON.parse(readFileSync(this.file, "utf8")) as Partial<RoutineFile>;
      if (j?.version !== 1 || !Array.isArray(j.routines)) return { version: 1, routines: [], runs: {} };
      const routines = j.routines.filter((r) => r && typeof r.id === "string" && typeof r.title === "string" && typeof parseSchedule(r.schedule) !== "string");
      return { version: 1, routines, runs: j.runs ?? {} };
    } catch {
      return { version: 1, routines: [], runs: {} };
    }
  }

  private write(f: RoutineFile): void {
    mkdirSync(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp-${process.pid}`;
    writeFileSync(tmp, JSON.stringify(f), { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  list(): Routine[] {
    return this.read().routines;
  }

  runsToday(now: number): number {
    return this.read().runs[dayKey(now)] ?? 0;
  }

  /** Makes a routine. Throws with a reason when the title or the schedule is not acceptable or there are too many. */
  create(title: unknown, schedule: unknown, now: number): Routine {
    const t = clean(title, TITLE_MAX);
    if (t.length < 3) throw new Error("title must be a sentence of 3 to 300 characters");
    const s = parseSchedule(schedule);
    if (typeof s === "string") throw new Error(s);
    const f = this.read();
    if (f.routines.length >= MAX_ROUTINES) throw new Error(`at most ${MAX_ROUTINES} routines`);
    // What was due before the routine existed is not run: it starts with the next scheduled time.
    const r: Routine = { id: `rtn_${randomBytes(5).toString("hex")}`, title: t, schedule: s, enabled: true, createdAt: now, handledDue: 0 };
    f.routines.push(r);
    this.write(f);
    return r;
  }

  update(id: string, patch: { title?: unknown; schedule?: unknown; enabled?: unknown }, now: number): Routine | null {
    const f = this.read();
    const r = f.routines.find((x) => x.id === id);
    if (!r) return null;
    if (patch.title !== undefined) {
      const t = clean(patch.title, TITLE_MAX);
      if (t.length < 3) throw new Error("title must be a sentence of 3 to 300 characters");
      r.title = t;
    }
    if (patch.schedule !== undefined) {
      const s = parseSchedule(patch.schedule);
      if (typeof s === "string") throw new Error(s);
      r.schedule = s;
      // A changed time starts from now: what was due under the old time is not run.
      r.handledDue = lastDueAt(s, now);
    }
    if (patch.enabled !== undefined) {
      if (typeof patch.enabled !== "boolean") throw new Error("enabled must be true or false");
      if (patch.enabled && !r.enabled) r.handledDue = lastDueAt(r.schedule, now);
      r.enabled = patch.enabled;
    }
    this.write(f);
    return r;
  }

  remove(id: string): boolean {
    const f = this.read();
    const rest = f.routines.filter((r) => r.id !== id);
    if (rest.length === f.routines.length) return false;
    f.routines = rest;
    this.write(f);
    return true;
  }

  /**
   * One pass of the timer: for each routine that is due, [submit] its goal as a task (at most one run per routine, and at most
   * ten runs a day in all). Returns how many tasks were made. A failing submit leaves the routine due for the next pass until its
   * late window closes.
   */
  async tick(now: number, submit: (goal: string) => Promise<{ id: string }>): Promise<number> {
    let made = 0;
    for (const r of this.read().routines) {
      const d = decide(r, now);
      if (!d.run && d.skipDue === undefined) continue;
      if (!d.run) {
        this.mark(r.id, d.skipDue!, undefined, undefined);
        continue;
      }
      if (this.runsToday(now) >= DAILY_RUN_CAP) continue;
      try {
        const task = await submit(r.title);
        this.mark(r.id, d.due, now, task.id);
        made++;
      } catch {
        /* due again at the next pass, until the window closes */
      }
    }
    return made;
  }

  /** Runs a routine now, outside its schedule (the owner asked). Counts against the day's limit. Null when there is no such routine or the limit is reached. */
  async runNow(id: string, now: number, submit: (goal: string) => Promise<{ id: string }>): Promise<{ id: string } | null> {
    const r = this.read().routines.find((x) => x.id === id);
    if (!r || this.runsToday(now) >= DAILY_RUN_CAP) return null;
    const task = await submit(r.title);
    this.mark(id, r.handledDue, now, task.id);
    return task;
  }

  private mark(id: string, due: number, ranAt: number | undefined, taskId: string | undefined): void {
    const f = this.read();
    const r = f.routines.find((x) => x.id === id);
    if (!r) return;
    r.handledDue = Math.max(r.handledDue, due);
    if (ranAt !== undefined) {
      r.lastRunAt = ranAt;
      if (taskId) r.lastTaskId = taskId;
      const k = dayKey(ranAt);
      f.runs[k] = (f.runs[k] ?? 0) + 1;
      for (const key of Object.keys(f.runs)) if (ranAt - Date.parse(`${key}T12:00:00`) > 14 * 86_400_000) delete f.runs[key];
    }
    this.write(f);
  }
}
