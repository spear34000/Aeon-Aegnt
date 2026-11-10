import { StaleLeaseError } from "./errors.ts";
import { CLAIMABLE_STATUSES } from "./store.ts";
import type { Task, TaskPatch } from "./types.ts";

/** Pure task-state transitions shared by every Store implementation, so they cannot diverge. */

export function leaseFree(t: Task, now: number): boolean {
  return !t.lease || t.lease.expiresAt <= now;
}

export function isRunnable(t: Task, now: number): boolean {
  if (t.runAfter > now) return false;
  if ((CLAIMABLE_STATUSES as readonly string[]).includes(t.status)) return leaseFree(t, now);
  return t.status === "RUNNING" && leaseFree(t, now);
}

/** Mutates `t` and returns the new token, or null if the task cannot be claimed at `now`. */
export function tryClaim(t: Task, holder: string, ttlMs: number, now: number): number | null {
  if (!isRunnable(t, now)) return null;
  t.fence += 1;
  t.lease = { holder, token: t.fence, expiresAt: now + ttlMs };
  t.status = "RUNNING";
  t.updatedAt = now;
  return t.fence;
}

export function assertLease(t: Task, token: number, now: number): void {
  if (!t.lease || t.lease.token !== token || t.lease.expiresAt <= now) throw new StaleLeaseError(t.id);
}

export function applyPatch(t: Task, patch: TaskPatch, now: number): void {
  const rec = t as unknown as Record<string, unknown>;
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined) delete rec[k];
    else rec[k] = structuredClone(v);
  }
  t.updatedAt = now;
}
