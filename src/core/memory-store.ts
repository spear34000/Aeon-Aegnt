import { NotFoundError } from "./errors.ts";
import type { Store } from "./store.ts";
import { applyPatch, assertLease, isRunnable, tryClaim } from "./task-ops.ts";
import type {
  Approval,
  ApprovalStatus,
  AuditEvent,
  ClaimResult,
  Task,
  TaskPatch,
  UsageRecord,
} from "./types.ts";

/** The data of a store, for one that saves it as a file. */
export interface StoreState {
  tasks: Task[];
  effects: [string, string][];
  events: AuditEvent[];
  approvals: Approval[];
  usage: UsageRecord[];
  eventSeq: number;
}

/** Reference implementation of Store. Values are cloned on the way in and out to mimic persistence. */
export class MemoryStore implements Store {
  private tasks = new Map<string, Task>();
  private effects = new Map<string, string>();
  private events: AuditEvent[] = [];
  private approvals = new Map<string, Approval>();
  private usage: UsageRecord[] = [];
  private eventSeq = 0;

  /** Called after every change. A store that keeps its data somewhere else overrides this. */
  protected changed(): void {}

  /** Everything the store holds, as plain JSON-safe data (for a store that saves it to a file). */
  protected exportState(): StoreState {
    return {
      tasks: [...this.tasks.values()],
      effects: [...this.effects.entries()],
      events: this.events,
      approvals: [...this.approvals.values()],
      usage: this.usage,
      eventSeq: this.eventSeq,
    };
  }

  protected importState(s: StoreState): void {
    this.tasks = new Map(s.tasks.map((t) => [t.id, t]));
    this.effects = new Map(s.effects);
    this.events = s.events;
    this.approvals = new Map(s.approvals.map((a) => [a.id, a]));
    this.usage = s.usage;
    this.eventSeq = s.eventSeq;
  }

  async createTask(task: Task): Promise<void> {
    if (this.tasks.has(task.id)) throw new Error(`Task already exists: ${task.id}`);
    this.tasks.set(task.id, structuredClone(task));
    this.changed();
  }

  async getTask(id: string): Promise<Task | null> {
    const t = this.tasks.get(id);
    return t ? structuredClone(t) : null;
  }

  async listTasks(filter?: { status?: Task["status"][] }): Promise<Task[]> {
    const all = [...this.tasks.values()].sort((a, b) => a.createdAt - b.createdAt);
    const out = filter?.status ? all.filter((t) => filter.status!.includes(t.status)) : all;
    return out.map((t) => structuredClone(t));
  }

  private must(id: string): Task {
    const t = this.tasks.get(id);
    if (!t) throw new NotFoundError("Task", id);
    return t;
  }

  async claim(id: string, holder: string, ttlMs: number, now: number): Promise<ClaimResult | null> {
    const t = this.must(id);
    const token = tryClaim(t, holder, ttlMs, now);
    if (token !== null) this.changed();
    return token === null ? null : { task: structuredClone(t), token };
  }

  async heartbeat(id: string, token: number, ttlMs: number, now: number): Promise<void> {
    const t = this.must(id);
    assertLease(t, token, now);
    t.lease!.expiresAt = now + ttlMs;
    this.changed();
  }

  async commit(id: string, token: number, patch: TaskPatch, now: number): Promise<Task> {
    const t = this.must(id);
    assertLease(t, token, now);
    applyPatch(t, patch, now);
    this.changed();
    return structuredClone(t);
  }

  async release(id: string, token: number, patch: TaskPatch, now: number): Promise<Task> {
    const t = this.must(id);
    assertLease(t, token, now);
    applyPatch(t, patch, now);
    t.lease = null;
    this.changed();
    return structuredClone(t);
  }

  async update(id: string, patch: TaskPatch, now: number): Promise<Task> {
    const t = this.must(id);
    applyPatch(t, patch, now);
    this.changed();
    return structuredClone(t);
  }

  async findRunnable(now: number): Promise<Task[]> {
    return [...this.tasks.values()]
      .filter((t) => isRunnable(t, now))
      .sort((a, b) => a.runAfter - b.runAfter || a.createdAt - b.createdAt)
      .map((t) => structuredClone(t));
  }

  async getEffect(taskId: string, key: string): Promise<string | null> {
    return this.effects.get(`${taskId}\u0000${key}`) ?? null;
  }

  async putEffect(taskId: string, key: string, value: string, token: number, now: number): Promise<void> {
    const t = this.must(taskId);
    assertLease(t, token, now);
    this.effects.set(`${taskId}\u0000${key}`, value);
    this.changed();
  }

  async addEvent(event: AuditEvent): Promise<void> {
    this.events.push({ ...structuredClone(event), id: ++this.eventSeq });
    this.changed();
  }

  async listEvents(taskId?: string): Promise<AuditEvent[]> {
    const e = taskId ? this.events.filter((x) => x.taskId === taskId) : this.events;
    return e.map((x) => structuredClone(x));
  }

  async createApproval(approval: Approval): Promise<void> {
    this.approvals.set(approval.id, structuredClone(approval));
    this.changed();
  }

  async getApproval(id: string): Promise<Approval | null> {
    const a = this.approvals.get(id);
    return a ? structuredClone(a) : null;
  }

  async findApproval(taskId: string, actionKey: string): Promise<Approval | null> {
    const matches = [...this.approvals.values()]
      .filter((a) => a.taskId === taskId && a.actionKey === actionKey)
      .sort((a, b) => b.createdAt - a.createdAt);
    return matches[0] ? structuredClone(matches[0]) : null;
  }

  async decideApproval(
    id: string,
    status: Extract<ApprovalStatus, "approved" | "denied">,
    now: number,
    answer?: string,
  ): Promise<Approval> {
    const a = this.approvals.get(id);
    if (!a) throw new NotFoundError("Approval", id);
    // Only a still-valid pending approval can be decided; late approvals do not resurrect.
    if (a.status !== "pending") throw new Error(`Approval ${id} is already ${a.status}`);
    if (a.expiresAt <= now) throw new Error(`Approval ${id} has expired`);
    a.status = status;
    a.decidedAt = now;
    if (answer !== undefined && a.kind === "question") a.answer = answer;
    this.changed();
    return structuredClone(a);
  }

  async expireApprovals(now: number): Promise<Approval[]> {
    const out: Approval[] = [];
    for (const a of this.approvals.values()) {
      if (a.status === "pending" && a.expiresAt <= now) {
        a.status = "expired";
        out.push(structuredClone(a));
      }
    }
    if (out.length > 0) this.changed();
    return out;
  }

  async listApprovals(status?: ApprovalStatus): Promise<Approval[]> {
    const all = [...this.approvals.values()].sort((a, b) => a.createdAt - b.createdAt);
    return (status ? all.filter((a) => a.status === status) : all).map((a) => structuredClone(a));
  }

  async addUsage(record: UsageRecord): Promise<void> {
    this.usage.push(structuredClone(record));
    this.changed();
  }

  async listUsage(sinceMs = 0): Promise<UsageRecord[]> {
    return this.usage.filter((u) => u.at >= sinceMs).map((u) => structuredClone(u));
  }
}
