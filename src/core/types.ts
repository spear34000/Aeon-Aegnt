import type { VerificationCriterion, VerificationResult } from "./verify.ts";

export type TaskStatus =
  | "PENDING" // waiting to be claimed (possibly scheduled via runAfter)
  | "RUNNING" // a runtime holds the lease
  | "RETRY_WAIT" // failed transiently, will retry at runAfter
  | "WAITING_FOR_APPROVAL" // blocked on a user decision
  | "WAITING_FOR_QUOTA" // model quota/rate limit; resumes at runAfter
  | "WAITING_FOR_DEVICE" // needs a local device that is offline (used from Phase 4)
  | "COMPLETED" // finished; see `verification` for whether the result was actually checked
  | "FAILED"
  | "CANCELLED";

export const TERMINAL: ReadonlySet<TaskStatus> = new Set(["COMPLETED", "FAILED", "CANCELLED"]);

/**
 * The action the agent has decided on but not yet finished. Persisted BEFORE approval or execution,
 * so that after an approval, crash or restart the exact same action is resumed instead of asking
 * the (non-deterministic) model to decide again.
 */
export interface PendingAction {
  index: number;
  tool: string;
  args: unknown;
  actionKey: string;
}

/** One executed (or attempted) action, checkpointed so a restart can continue from here. */
export interface StepRecord {
  index: number;
  tool: string;
  args: unknown;
  /** Untrusted data returned by the tool (already redacted). */
  output: string;
  ok: boolean;
  at: number;
  /** True if the tool actually ran (its output was shown to the model), even when it reported failure. Denied/blocked steps are false. */
  executed?: boolean;
}

export interface Lease {
  holder: string;
  /** Monotonic fencing token; strictly increases on every claim. */
  token: number;
  expiresAt: number;
}

export interface Task {
  id: string;
  goal: string;
  status: TaskStatus;
  createdAt: number;
  updatedAt: number;
  /** Earliest time the task may be (re)claimed. */
  runAfter: number;
  attempt: number;
  maxAttempts: number;
  maxSteps: number;
  plan: string[];
  criteria: VerificationCriterion[];
  steps: StepRecord[];
  lease: Lease | null;
  /** Highest fencing token ever issued for this task. */
  fence: number;
  cancelRequested: boolean;
  result?: string;
  error?: string;
  /** Free-form human-readable status detail (e.g. which approval is pending). */
  note?: string;
  verification?: VerificationResult;
  /** Id of the approval the task is blocked on. */
  pendingApprovalId?: string;
  pendingAction?: PendingAction;
  /** Remembered facts about the owner were shown to the model during this task. Once true it stays true, even if the memory changes later: the model has seen them. */
  sawOwnerFacts?: boolean;
}

export type TaskPatch = Partial<
  Pick<
    Task,
    | "status"
    | "runAfter"
    | "attempt"
    | "plan"
    | "criteria"
    | "steps"
    | "result"
    | "error"
    | "note"
    | "verification"
    | "pendingApprovalId"
    | "pendingAction"
    | "cancelRequested"
    | "sawOwnerFacts"
  >
>;

export interface AuditEvent {
  id?: number;
  at: number;
  taskId: string | null;
  type: string;
  detail: Record<string, unknown>;
}

export type ApprovalStatus = "pending" | "approved" | "denied" | "expired";

export interface Approval {
  id: string;
  taskId: string;
  /** Hash of (taskId, tool, canonical args). An approval only authorises exactly this action. */
  actionKey: string;
  tool: string;
  summary: string;
  risk: string;
  /** Paths or URLs the action touches, shown on the card (first 3, plus a count). */
  resources: string[];
  irreversible: boolean;
  /** Longer text to read before deciding, shown behind a "details" control. */
  detail?: string;
  /** "question": the agent needs an answer in words (`summary` is the question, `resources` are suggested answers), not a yes or no. */
  kind?: "approval" | "question";
  /** The owner's answer to a question. */
  answer?: string;
  status: ApprovalStatus;
  createdAt: number;
  expiresAt: number;
  decidedAt?: number;
}

export interface UsageRecord {
  at: number;
  provider: string;
  model: string;
  taskId: string | null;
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  ok: boolean;
}

export interface ClaimResult {
  task: Task;
  token: number;
}
