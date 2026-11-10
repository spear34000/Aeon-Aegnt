/**
 * Secret redaction. Applied to everything that is logged, audited, or shown in errors.
 * Two layers: exact known secret values (registered at runtime) and generic token patterns.
 */
const PATTERNS: RegExp[] = [
  /Bearer\s+[A-Za-z0-9._~+/=-]{8,}/gi,
  /\bsk-[A-Za-z0-9_-]{8,}/g,
  /\b(?:api[_-]?key|token|secret|password)\s*[=:]\s*["']?[^\s"',;]{6,}/gi,
];

export class Redactor {
  private secrets = new Set<string>();

  /** Register a secret value. Very short values are ignored (would shred normal text). */
  register(secret: string | undefined): void {
    if (secret && secret.length >= 6) this.secrets.add(secret);
  }

  redact(text: string): string {
    let out = text;
    for (const s of this.secrets) out = out.split(s).join("[REDACTED]");
    for (const p of PATTERNS) out = out.replace(p, "[REDACTED]");
    return out;
  }

  /** Deep-redact any JSON-like value. */
  redactValue<T>(value: T): T {
    if (typeof value === "string") return this.redact(value) as T;
    if (Array.isArray(value)) return value.map((v) => this.redactValue(v)) as T;
    if (value && typeof value === "object") {
      const out: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
        out[k] = this.redactValue(v);
      }
      return out as T;
    }
    return value;
  }
}
