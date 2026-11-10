/**
 * Verification Engine. "The model said it succeeded" is never evidence.
 * A task is `verified` only if it has at least one criterion and every criterion passed
 * against the real world (files, HTTP). No criteria => `unverified`.
 */
export type CriterionSource = "user" | "model";

export type VerificationCriterion =
  | { kind: "file_exists"; path: string; source?: CriterionSource }
  | { kind: "file_contains"; path: string; text: string; source?: CriterionSource }
  | { kind: "file_sha256"; path: string; sha256: string; source?: CriterionSource }
  | { kind: "http_status"; url: string; status: number; source?: CriterionSource };

export interface Evidence {
  criterion: VerificationCriterion;
  passed: boolean;
  observed: string;
  at: number;
}

export type VerificationStatus = "verified" | "failed" | "unverified" | "user_confirmed";

export interface VerificationResult {
  status: VerificationStatus;
  evidence: Evidence[];
  /** True if every criterion was user-supplied (not merely model-proposed). */
  userDefined: boolean;
  checkedAt: number;
  note?: string;
}

/** Real-world access used by the verifier. Implemented by the runtime (Node, Workers, ...). */
export interface VerifierEnv {
  fileExists(path: string): Promise<boolean>;
  readText(path: string): Promise<string | null>;
  sha256File(path: string): Promise<string | null>;
  httpStatus(url: string): Promise<number>;
}

export async function verify(
  criteria: VerificationCriterion[],
  env: VerifierEnv,
  now: number,
): Promise<VerificationResult> {
  if (criteria.length === 0) {
    return {
      status: "unverified",
      evidence: [],
      userDefined: false,
      checkedAt: now,
      note: "No success criteria were defined, so completion was not checked.",
    };
  }
  const evidence: Evidence[] = [];
  for (const c of criteria) evidence.push(await check(c, env, now));
  const allPassed = evidence.every((e) => e.passed);
  return {
    status: allPassed ? "verified" : "failed",
    evidence,
    userDefined: criteria.every((c) => (c.source ?? "user") === "user"),
    checkedAt: now,
  };
}

async function check(c: VerificationCriterion, env: VerifierEnv, now: number): Promise<Evidence> {
  try {
    switch (c.kind) {
      case "file_exists": {
        const ok = await env.fileExists(c.path);
        return { criterion: c, passed: ok, observed: ok ? "file exists" : "file missing", at: now };
      }
      case "file_contains": {
        const text = await env.readText(c.path);
        if (text === null) return { criterion: c, passed: false, observed: "file missing", at: now };
        const ok = text.includes(c.text);
        return { criterion: c, passed: ok, observed: ok ? "text found" : "text not found", at: now };
      }
      case "file_sha256": {
        const h = await env.sha256File(c.path);
        if (h === null) return { criterion: c, passed: false, observed: "file missing", at: now };
        const ok = h.toLowerCase() === c.sha256.toLowerCase();
        return { criterion: c, passed: ok, observed: `sha256=${h}`, at: now };
      }
      case "http_status": {
        const s = await env.httpStatus(c.url);
        return { criterion: c, passed: s === c.status, observed: `status=${s}`, at: now };
      }
    }
  } catch (e) {
    // A check that cannot run is a failed check, never a pass.
    return { criterion: c, passed: false, observed: `check error: ${(e as Error).message}`, at: now };
  }
}

const KINDS = new Set(["file_exists", "file_contains", "file_sha256", "http_status"]);

/** Validate untrusted (model- or user-supplied) criteria JSON. Returns null if malformed. */
export function parseCriterion(raw: unknown, source: CriterionSource): VerificationCriterion | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  if (typeof o.kind !== "string" || !KINDS.has(o.kind)) return null;
  switch (o.kind) {
    case "file_exists":
      return typeof o.path === "string" ? { kind: "file_exists", path: o.path, source } : null;
    case "file_contains":
      return typeof o.path === "string" && typeof o.text === "string"
        ? { kind: "file_contains", path: o.path, text: o.text, source }
        : null;
    case "file_sha256":
      return typeof o.path === "string" && typeof o.sha256 === "string" && /^[0-9a-fA-F]{64}$/.test(o.sha256)
        ? { kind: "file_sha256", path: o.path, sha256: o.sha256, source }
        : null;
    case "http_status":
      // A model-chosen URL would be fetched outside the policy engine (SSRF / data exfiltration path).
      if (source === "model") return null;
      return typeof o.url === "string" && typeof o.status === "number"
        ? { kind: "http_status", url: o.url, status: o.status, source }
        : null;
  }
  return null;
}
