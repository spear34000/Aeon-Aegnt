import { DatabaseSync } from "node:sqlite";
import { NotFoundError } from "../core/errors.ts";
import type { Store } from "../core/store.ts";
import { applyPatch, assertLease, isRunnable, tryClaim } from "../core/task-ops.ts";
import type {
  Approval,
  ApprovalStatus,
  AuditEvent,
  ClaimResult,
  Task,
  TaskPatch,
  UsageRecord,
} from "../core/types.ts";

const SCHEMA = `
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  status TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE TABLE IF NOT EXISTS effects (
  task_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
  PRIMARY KEY (task_id, key)
);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, task_id TEXT, type TEXT NOT NULL, detail TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS approvals (
  id TEXT PRIMARY KEY, task_id TEXT NOT NULL, action_key TEXT NOT NULL, status TEXT NOT NULL,
  created_at INTEGER NOT NULL, data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_approvals_lookup ON approvals(task_id, action_key);
CREATE TABLE IF NOT EXISTS usage (
  id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, data TEXT NOT NULL
);
`;

/**
 * SQLite-backed Store (Node's built-in `node:sqlite`, no native build step).
 * Read-modify-write sequences run inside BEGIN IMMEDIATE so that two processes sharing the same
 * file cannot both claim a task. Task state is the shared pure logic in core/task-ops.ts.
 */
export class SqliteStore implements Store {
  private readonly db: DatabaseSync;

  constructor(path: string) {
    this.db = new DatabaseSync(path);
    this.db.exec("PRAGMA busy_timeout = 5000;");
    this.db.exec(SCHEMA);
  }

  close(): void {
    this.db.close();
  }

  private tx<T>(fn: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const out = fn();
      this.db.exec("COMMIT");
      return out;
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
  }

  private load(id: string): Task {
    const row = this.db.prepare("SELECT data FROM tasks WHERE id = ?").get(id) as { data: string } | undefined;
    if (!row) throw new NotFoundError("Task", id);
    return JSON.parse(row.data) as Task;
  }

  private save(t: Task): void {
    this.db.prepare("UPDATE tasks SET status = ?, data = ? WHERE id = ?").run(t.status, JSON.stringify(t), t.id);
  }

  async createTask(task: Task): Promise<void> {
    this.db
      .prepare("INSERT INTO tasks (id, status, created_at, data) VALUES (?, ?, ?, ?)")
      .run(task.id, task.status, task.createdAt, JSON.stringify(task));
  }

  async getTask(id: string): Promise<Task | null> {
    const row = this.db.prepare("SELECT data FROM tasks WHERE id = ?").get(id) as { data: string } | undefined;
    return row ? (JSON.parse(row.data) as Task) : null;
  }

  async listTasks(filter?: { status?: Task["status"][] }): Promise<Task[]> {
    const rows = this.db.prepare("SELECT data FROM tasks ORDER BY created_at, id").all() as { data: string }[];
    const all = rows.map((r) => JSON.parse(r.data) as Task);
    return filter?.status ? all.filter((t) => filter.status!.includes(t.status)) : all;
  }

  async claim(id: string, holder: string, ttlMs: number, now: number): Promise<ClaimResult | null> {
    return this.tx(() => {
      const t = this.load(id);
      const token = tryClaim(t, holder, ttlMs, now);
      if (token === null) return null;
      this.save(t);
      return { task: structuredClone(t), token };
    });
  }

  async heartbeat(id: string, token: number, ttlMs: number, now: number): Promise<void> {
    this.tx(() => {
      const t = this.load(id);
      assertLease(t, token, now);
      t.lease!.expiresAt = now + ttlMs;
      this.save(t);
    });
  }

  async commit(id: string, token: number, patch: TaskPatch, now: number): Promise<Task> {
    return this.tx(() => {
      const t = this.load(id);
      assertLease(t, token, now);
      applyPatch(t, patch, now);
      this.save(t);
      return t;
    });
  }

  async release(id: string, token: number, patch: TaskPatch, now: number): Promise<Task> {
    return this.tx(() => {
      const t = this.load(id);
      assertLease(t, token, now);
      applyPatch(t, patch, now);
      t.lease = null;
      this.save(t);
      return t;
    });
  }

  async update(id: string, patch: TaskPatch, now: number): Promise<Task> {
    return this.tx(() => {
      const t = this.load(id);
      applyPatch(t, patch, now);
      this.save(t);
      return t;
    });
  }

  async findRunnable(now: number): Promise<Task[]> {
    const rows = this.db
      .prepare("SELECT data FROM tasks WHERE status IN ('PENDING','RETRY_WAIT','WAITING_FOR_QUOTA','RUNNING')")
      .all() as { data: string }[];
    return rows
      .map((r) => JSON.parse(r.data) as Task)
      .filter((t) => isRunnable(t, now))
      .sort((a, b) => a.runAfter - b.runAfter || a.createdAt - b.createdAt);
  }

  async getEffect(taskId: string, key: string): Promise<string | null> {
    const row = this.db.prepare("SELECT value FROM effects WHERE task_id = ? AND key = ?").get(taskId, key) as
      | { value: string }
      | undefined;
    return row?.value ?? null;
  }

  async putEffect(taskId: string, key: string, value: string, token: number, now: number): Promise<void> {
    this.tx(() => {
      assertLease(this.load(taskId), token, now);
      this.db.prepare("INSERT OR REPLACE INTO effects (task_id, key, value) VALUES (?, ?, ?)").run(taskId, key, value);
    });
  }

  async addEvent(event: AuditEvent): Promise<void> {
    this.db
      .prepare("INSERT INTO events (at, task_id, type, detail) VALUES (?, ?, ?, ?)")
      .run(event.at, event.taskId, event.type, JSON.stringify(event.detail));
  }

  async listEvents(taskId?: string): Promise<AuditEvent[]> {
    const rows = (
      taskId
        ? this.db.prepare("SELECT * FROM events WHERE task_id = ? ORDER BY id").all(taskId)
        : this.db.prepare("SELECT * FROM events ORDER BY id").all()
    ) as { id: number; at: number; task_id: string | null; type: string; detail: string }[];
    return rows.map((r) => ({ id: r.id, at: r.at, taskId: r.task_id, type: r.type, detail: JSON.parse(r.detail) }));
  }

  async createApproval(a: Approval): Promise<void> {
    this.db
      .prepare("INSERT INTO approvals (id, task_id, action_key, status, created_at, data) VALUES (?, ?, ?, ?, ?, ?)")
      .run(a.id, a.taskId, a.actionKey, a.status, a.createdAt, JSON.stringify(a));
  }

  private loadApproval(id: string): Approval | null {
    const row = this.db.prepare("SELECT data FROM approvals WHERE id = ?").get(id) as { data: string } | undefined;
    return row ? (JSON.parse(row.data) as Approval) : null;
  }

  private saveApproval(a: Approval): void {
    this.db.prepare("UPDATE approvals SET status = ?, data = ? WHERE id = ?").run(a.status, JSON.stringify(a), a.id);
  }

  async getApproval(id: string): Promise<Approval | null> {
    return this.loadApproval(id);
  }

  async findApproval(taskId: string, actionKey: string): Promise<Approval | null> {
    const row = this.db
      .prepare("SELECT data FROM approvals WHERE task_id = ? AND action_key = ? ORDER BY created_at DESC, rowid DESC LIMIT 1")
      .get(taskId, actionKey) as { data: string } | undefined;
    return row ? (JSON.parse(row.data) as Approval) : null;
  }

  async decideApproval(
    id: string,
    status: Extract<ApprovalStatus, "approved" | "denied">,
    now: number,
  ): Promise<Approval> {
    return this.tx(() => {
      const a = this.loadApproval(id);
      if (!a) throw new NotFoundError("Approval", id);
      if (a.status !== "pending") throw new Error(`Approval ${id} is already ${a.status}`);
      if (a.expiresAt <= now) throw new Error(`Approval ${id} has expired`);
      a.status = status;
      a.decidedAt = now;
      this.saveApproval(a);
      return a;
    });
  }

  async expireApprovals(now: number): Promise<Approval[]> {
    return this.tx(() => {
      const rows = this.db.prepare("SELECT data FROM approvals WHERE status = 'pending'").all() as { data: string }[];
      const out: Approval[] = [];
      for (const r of rows) {
        const a = JSON.parse(r.data) as Approval;
        if (a.expiresAt <= now) {
          a.status = "expired";
          this.saveApproval(a);
          out.push(a);
        }
      }
      return out;
    });
  }

  async listApprovals(status?: ApprovalStatus): Promise<Approval[]> {
    const rows = (
      status
        ? this.db.prepare("SELECT data FROM approvals WHERE status = ? ORDER BY created_at, rowid").all(status)
        : this.db.prepare("SELECT data FROM approvals ORDER BY created_at, rowid").all()
    ) as { data: string }[];
    return rows.map((r) => JSON.parse(r.data) as Approval);
  }

  async addUsage(r: UsageRecord): Promise<void> {
    this.db.prepare("INSERT INTO usage (at, data) VALUES (?, ?)").run(r.at, JSON.stringify(r));
  }

  async listUsage(sinceMs = 0): Promise<UsageRecord[]> {
    const rows = this.db.prepare("SELECT data FROM usage WHERE at >= ? ORDER BY id").all(sinceMs) as { data: string }[];
    return rows.map((r) => JSON.parse(r.data) as UsageRecord);
  }
}
