import type { Clock } from "./clock.ts";
import { StaleLeaseError } from "./errors.ts";
import { newId } from "./ids.ts";
import type { Redactor } from "./redact.ts";
import type { Store } from "./store.ts";
import type { Approval, Task, TaskPatch } from "./types.ts";
import { TERMINAL } from "./types.ts";
import { verify, type VerificationCriterion, type VerifierEnv } from "./verify.ts";

/** What a runner reports back to the engine after one run attempt. */
export type RunOutcome =
  | { kind: "done"; result: string }
  | { kind: "waiting_approval"; approvalId: string; note: string }
  | { kind: "wait_quota"; until: number; note: string }
  | { kind: "fail"; error: string; retryable: boolean; retryAfterMs?: number }
  | { kind: "cancelled" };

export interface RunContext {
  readonly token: number;
  readonly holder: string;
  /** Persist progress. Fenced: throws StaleLeaseError if this runner lost its lease. */
  checkpoint(patch: TaskPatch): Promise<Task>;
  /** Extend the lease. Throws StaleLeaseError if it was lost. */
  heartbeat(): Promise<void>;
  /**
   * Run a slow operation (model call, tool call) while renewing the lease in the background.
   * Throws StaleLeaseError afterwards if the lease was lost during the call.
   */
  keepAlive<T>(fn: () => Promise<T>): Promise<T>;
  /** Re-read the task (to observe cancel requests made by the user). */
  reload(): Promise<Task>;
  audit(type: string, detail?: Record<string, unknown>): Promise<void>;
  getEffect(key: string): Promise<string | null>;
  putEffect(key: string, value: string): Promise<void>;
}

export interface TaskRunner {
  run(task: Task, ctx: RunContext): Promise<RunOutcome>;
}

export interface EngineConfig {
  store: Store;
  clock: Clock;
  runner: TaskRunner;
  verifierEnv: VerifierEnv;
  redactor: Redactor;
  /** Unique id of this runtime instance (cloud, local-pc, ...). */
  holderId: string;
  leaseTtlMs?: number;
  retryBaseMs?: number;
  retryMaxMs?: number;
}

export interface SubmitOptions {
  goal: string;
  criteria?: VerificationCriterion[];
  runAfter?: number;
  maxAttempts?: number;
  maxSteps?: number;
}

export type RunSummary =
  | { taskId: string; result: "not_claimable" }
  | { taskId: string; result: "lease_lost" }
  | { taskId: string; result: "ran"; status: Task["status"] };

/**
 * Persistent Workflow Engine: leases + fencing, retries, scheduling, cancellation, approvals.
 * Guarantee (honest version): within the Store, at most one holder can write task state for a
 * given fencing token, and a stale holder's writes are rejected. Side effects outside the Store
 * are at-least-once; use the effect journal and idempotency keys to dedupe.
 */
export class WorkflowEngine {
  private readonly cfg: EngineConfig;
  private readonly ttl: number;
  constructor(cfg: EngineConfig) {
    this.cfg = cfg;
    this.ttl = cfg.leaseTtlMs ?? 300_000; // longer than any single model/tool timeout
  }

  async submit(opts: SubmitOptions): Promise<Task> {
    const now = this.cfg.clock.now();
    const task: Task = {
      id: newId("task"),
      goal: opts.goal,
      status: "PENDING",
      createdAt: now,
      updatedAt: now,
      runAfter: opts.runAfter ?? now,
      attempt: 0,
      maxAttempts: opts.maxAttempts ?? 3,
      maxSteps: opts.maxSteps ?? 20,
      plan: [],
      criteria: (opts.criteria ?? []).map((c) => ({ ...c, source: c.source ?? "user" })),
      steps: [],
      lease: null,
      fence: 0,
      cancelRequested: false,
    };
    await this.cfg.store.createTask(task);
    await this.audit(task.id, "task_submitted", { goal: task.goal, runAfter: task.runAfter });
    return task;
  }

  /** Claim and run a single task. Safe to call concurrently from several runtimes. */
  async runTask(taskId: string): Promise<RunSummary> {
    const { store, clock } = this.cfg;
    const claim = await store.claim(taskId, this.cfg.holderId, this.ttl, clock.now());
    if (!claim) return { taskId, result: "not_claimable" };
    const { token } = claim;
    let task = claim.task;
    await this.audit(taskId, "task_claimed", { holder: this.cfg.holderId, token, attempt: task.attempt });

    try {
      if (task.cancelRequested) return await this.finish(taskId, token, { status: "CANCELLED", note: "cancelled before run" });

      task = await store.commit(taskId, token, { attempt: task.attempt + 1 }, clock.now());
      const ctx = this.makeContext(taskId, token);
      let outcome: RunOutcome;
      try {
        outcome = await this.cfg.runner.run(task, ctx);
      } catch (e) {
        if (e instanceof StaleLeaseError) throw e;
        const msg = this.cfg.redactor.redact((e as Error).message);
        outcome = { kind: "fail", error: `runner crashed: ${msg}`, retryable: true };
      }
      return await this.apply(task, token, outcome);
    } catch (e) {
      if (e instanceof StaleLeaseError) {
        await this.audit(taskId, "lease_lost", { holder: this.cfg.holderId, token });
        return { taskId, result: "lease_lost" };
      }
      throw e;
    }
  }

  private async apply(task: Task, token: number, outcome: RunOutcome): Promise<RunSummary> {
    const { clock } = this.cfg;
    switch (outcome.kind) {
      case "done": {
        const fresh = await this.cfg.store.getTask(task.id);
        const criteria = fresh?.criteria ?? task.criteria;
        const verification = await verify(criteria, this.cfg.verifierEnv, clock.now());
        if (verification.status === "failed") {
          return this.finish(task.id, token, {
            status: "FAILED",
            result: outcome.result,
            error: "The agent reported success but verification failed.",
            verification,
          });
        }
        return this.finish(task.id, token, { status: "COMPLETED", result: outcome.result, verification });
      }
      case "waiting_approval":
        return this.finish(task.id, token, {
          status: "WAITING_FOR_APPROVAL",
          pendingApprovalId: outcome.approvalId,
          note: outcome.note,
        });
      case "wait_quota":
        return this.finish(task.id, token, {
          status: "WAITING_FOR_QUOTA",
          runAfter: outcome.until,
          note: outcome.note,
        });
      case "cancelled":
        return this.finish(task.id, token, { status: "CANCELLED", note: "cancelled by user" });
      case "fail": {
        const error = this.cfg.redactor.redact(outcome.error);
        if (outcome.retryable && task.attempt < task.maxAttempts) {
          const base = this.cfg.retryBaseMs ?? 5_000;
          const max = this.cfg.retryMaxMs ?? 300_000;
          const backoff = outcome.retryAfterMs ?? Math.min(max, base * 2 ** (task.attempt - 1));
          return this.finish(task.id, token, {
            status: "RETRY_WAIT",
            runAfter: clock.now() + backoff,
            error,
            note: `retry ${task.attempt}/${task.maxAttempts}`,
          });
        }
        return this.finish(task.id, token, { status: "FAILED", error });
      }
    }
  }

  private async finish(taskId: string, token: number, patch: TaskPatch): Promise<RunSummary> {
    const t = await this.cfg.store.release(taskId, token, patch, this.cfg.clock.now());
    await this.audit(taskId, "task_state", { status: t.status, note: t.note, error: t.error });
    return { taskId, result: "ran", status: t.status };
  }

  /** One scheduler pass: expire approvals, then run everything that is due. */
  async tick(): Promise<RunSummary[]> {
    await this.sweepApprovals();
    const due = await this.cfg.store.findRunnable(this.cfg.clock.now());
    const out: RunSummary[] = [];
    for (const t of due) out.push(await this.runTask(t.id));
    return out;
  }

  /** Expired approvals count as denied; the blocked task becomes runnable again and sees the denial. */
  async sweepApprovals(): Promise<void> {
    const { store, clock } = this.cfg;
    const expired = await store.expireApprovals(clock.now());
    for (const a of expired) {
      await this.audit(a.taskId, "approval_expired", { approvalId: a.id, tool: a.tool });
      await this.resumeIfBlocked(a);
    }
  }

  /** [answer] is required to approve a question (the owner's words, cut to 1000 characters) and ignored for anything else. */
  async decideApproval(approvalId: string, decision: "approved" | "denied", answer?: string): Promise<Approval> {
    const { store, clock } = this.cfg;
    const before = await store.getApproval(approvalId);
    const text = answer?.replace(/[\u0000-\u0008\u000b-\u001f]/g, " ").trim().slice(0, 1000);
    if (before?.kind === "question" && decision === "approved" && !text) throw new Error("an answer is needed");
    const a = await store.decideApproval(approvalId, decision, clock.now(), before?.kind === "question" ? text : undefined);
    await this.audit(a.taskId, `approval_${decision}`, { approvalId, tool: a.tool, summary: a.summary });
    await this.resumeIfBlocked(a);
    return a;
  }

  private async resumeIfBlocked(a: Approval): Promise<void> {
    const t = await this.cfg.store.getTask(a.taskId);
    if (t && t.status === "WAITING_FOR_APPROVAL" && t.pendingApprovalId === a.id) {
      await this.cfg.store.update(
        t.id,
        { status: "PENDING", runAfter: this.cfg.clock.now(), pendingApprovalId: undefined },
        this.cfg.clock.now(),
      );
    }
  }

  async cancel(taskId: string): Promise<Task> {
    const { store, clock } = this.cfg;
    const t = await store.getTask(taskId);
    if (!t || TERMINAL.has(t.status)) return t as Task;
    const leaseLive = t.lease && t.lease.expiresAt > clock.now();
    if (t.status === "RUNNING" && leaseLive) {
      await this.audit(taskId, "cancel_requested", {});
      return store.update(taskId, { cancelRequested: true }, clock.now());
    }
    await this.audit(taskId, "task_cancelled", {});
    return store.update(taskId, { status: "CANCELLED", cancelRequested: true, pendingApprovalId: undefined }, clock.now());
  }

  /** Re-run verification against the real world (also records the result). */
  async reverify(taskId: string): Promise<Task> {
    const { store, clock } = this.cfg;
    const t = await store.getTask(taskId);
    if (!t) throw new Error(`Task not found: ${taskId}`);
    const verification = await verify(t.criteria, this.cfg.verifierEnv, clock.now());
    await this.audit(taskId, "reverified", { status: verification.status });
    return store.update(taskId, { verification }, clock.now());
  }

  /** The owner personally confirms the result; recorded separately from automated verification. */
  async confirmByUser(taskId: string, note?: string): Promise<Task> {
    const { store, clock } = this.cfg;
    const t = await store.getTask(taskId);
    if (!t?.verification) throw new Error("Task has no verification record to confirm");
    const verification = { ...t.verification, status: "user_confirmed" as const, ...(note ? { note } : {}) };
    await this.audit(taskId, "user_confirmed", { note });
    return store.update(taskId, { verification }, clock.now());
  }

  private makeContext(taskId: string, token: number): RunContext {
    const { store, clock } = this.cfg;
    const audit = (type: string, detail: Record<string, unknown> = {}) => this.audit(taskId, type, detail);
    return {
      token,
      holder: this.cfg.holderId,
      checkpoint: async (patch) => {
        const t = await store.commit(taskId, token, patch, clock.now());
        await store.heartbeat(taskId, token, this.ttl, clock.now());
        return t;
      },
      heartbeat: () => store.heartbeat(taskId, token, this.ttl, clock.now()),
      keepAlive: async <T>(fn: () => Promise<T>): Promise<T> => {
        let lost: unknown = null;
        const timer = setInterval(() => {
          store.heartbeat(taskId, token, this.ttl, clock.now()).catch((e) => {
            if (e instanceof StaleLeaseError) lost = e;
          });
        }, Math.max(50, Math.floor(this.ttl / 3)));
        try {
          const out = await fn();
          if (lost) throw lost;
          await store.heartbeat(taskId, token, this.ttl, clock.now());
          return out;
        } finally {
          clearInterval(timer);
        }
      },
      reload: async () => (await store.getTask(taskId)) as Task,
      audit,
      getEffect: (key) => store.getEffect(taskId, key),
      putEffect: (key, value) => store.putEffect(taskId, key, value, token, clock.now()),
    };
  }

  private async audit(taskId: string | null, type: string, detail: Record<string, unknown> = {}): Promise<void> {
    await this.cfg.store.addEvent({
      at: this.cfg.clock.now(),
      taskId,
      type,
      detail: this.cfg.redactor.redactValue(detail),
    });
  }
}
