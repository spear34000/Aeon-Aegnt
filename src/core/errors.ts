/** Thrown when a write is attempted with a lease/fencing token that is no longer current. */
export class StaleLeaseError extends Error {
  readonly taskId: string;
  constructor(taskId: string, detail = "stale lease or fencing token") {
    super(`Task ${taskId}: ${detail}`);
    this.name = "StaleLeaseError";
    this.taskId = taskId;
  }
}

export class NotFoundError extends Error {
  constructor(what: string, id: string) {
    super(`${what} not found: ${id}`);
    this.name = "NotFoundError";
  }
}

export type ModelErrorKind =
  | "rate_limited"
  | "quota_exhausted"
  | "auth"
  | "bad_request"
  | "server"
  | "network"
  | "timeout"
  | "unavailable"
  | "invalid_response"
  | "truncated" // output hit max_tokens (often all spent on reasoning)
  | "consent_required";

/** Normalised model-provider failure. `retryAfterMs` is only set when the provider told us. */
export class ModelError extends Error {
  readonly kind: ModelErrorKind;
  readonly provider: string;
  readonly retryAfterMs: number | undefined;
  readonly status: number | undefined;
  constructor(
    kind: ModelErrorKind,
    provider: string,
    message: string,
    opts: { retryAfterMs?: number; status?: number } = {},
  ) {
    super(message);
    this.name = "ModelError";
    this.kind = kind;
    this.provider = provider;
    this.retryAfterMs = opts.retryAfterMs;
    this.status = opts.status;
  }
  /** Worth retrying later without changing anything? */
  get retryable(): boolean {
    return (
      this.kind === "rate_limited" ||
      this.kind === "server" ||
      this.kind === "network" ||
      this.kind === "timeout" ||
      this.kind === "unavailable" ||
      // A reply that is not the expected JSON (a half-sent body, an error page from a proxy) is nearly always a passing fault.
      this.kind === "invalid_response"
    );
  }
}

export class PolicyDeniedError extends Error {
  readonly reason: string;
  constructor(reason: string) {
    super(`Policy denied: ${reason}`);
    this.name = "PolicyDeniedError";
    this.reason = reason;
  }
}
