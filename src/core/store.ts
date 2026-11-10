import type {
  Approval,
  ApprovalStatus,
  AuditEvent,
  ClaimResult,
  Task,
  TaskPatch,
  UsageRecord,
} from "./types.ts";

/**
 * Persistence boundary. Every implementation (in-memory, SQLite, later D1 / Durable Objects)
 * must pass the shared conformance suite in test/store-conformance.ts.
 *
 * Fencing rules (decision D2): `claim` issues a strictly increasing token. `commit`, `release`,
 * `putEffect` and `heartbeat` must be rejected with StaleLeaseError unless the token is the
 * task's *current* lease token and the lease has not expired at `now`.
 */
export interface Store {
  createTask(task: Task): Promise<void>;
  getTask(id: string): Promise<Task | null>;
  listTasks(filter?: { status?: Task["status"][] }): Promise<Task[]>;

  /** Claim if unleased, lease expired, and runAfter <= now and the status is claimable. */
  claim(id: string, holder: string, ttlMs: number, now: number): Promise<ClaimResult | null>;
  heartbeat(id: string, token: number, ttlMs: number, now: number): Promise<void>;
  /** Fenced update; keeps the lease. */
  commit(id: string, token: number, patch: TaskPatch, now: number): Promise<Task>;
  /** Fenced update that also drops the lease. */
  release(id: string, token: number, patch: TaskPatch, now: number): Promise<Task>;
  /** Unfenced, for user actions (cancel request, approval resume). Never touches the lease. */
  update(id: string, patch: TaskPatch, now: number): Promise<Task>;
  /** Tasks that could be claimed at `now` (pending/retry/quota due, or running with expired lease). */
  findRunnable(now: number): Promise<Task[]>;

  /** Side-effect journal used for at-least-once + dedupe of step effects. */
  getEffect(taskId: string, key: string): Promise<string | null>;
  putEffect(taskId: string, key: string, value: string, token: number, now: number): Promise<void>;

  addEvent(event: AuditEvent): Promise<void>;
  listEvents(taskId?: string): Promise<AuditEvent[]>;

  createApproval(approval: Approval): Promise<void>;
  getApproval(id: string): Promise<Approval | null>;
  findApproval(taskId: string, actionKey: string): Promise<Approval | null>;
  decideApproval(id: string, status: Extract<ApprovalStatus, "approved" | "denied">, now: number): Promise<Approval>;
  /** Marks pending approvals past their deadline as expired and returns them. */
  expireApprovals(now: number): Promise<Approval[]>;
  listApprovals(status?: ApprovalStatus): Promise<Approval[]>;

  addUsage(record: UsageRecord): Promise<void>;
  listUsage(sinceMs?: number): Promise<UsageRecord[]>;
}

export const CLAIMABLE_STATUSES = ["PENDING", "RETRY_WAIT", "WAITING_FOR_QUOTA"] as const;
