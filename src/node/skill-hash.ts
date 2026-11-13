import { createHash } from "node:crypto";

/** Text (valid UTF-8 without NUL bytes) is hashed with LF line ends, so a checkout on Windows hashes like one elsewhere. */
function normalised(bytes: Buffer): Buffer {
  if (bytes.includes(0) || !bytes.includes(13)) return bytes;
  const text = bytes.toString("utf8");
  if (Buffer.byteLength(text) !== bytes.length) return bytes; // not valid UTF-8: binary
  return Buffer.from(text.replace(/\r\n/g, "\n"), "utf8");
}

/**
 * The content hash of a skill, the same idea as the lock file of vercel-labs/skills: SHA-256 over every file sorted by
 * the bytes of its path, each as path, length and content. It covers SKILL.md and the supporting files, so changing a
 * script or a reference changes the hash. Returns hex.
 */
export function skillHash(entries: { path: string; bytes: Buffer }[]): string {
  const h = createHash("sha256");
  const sorted = [...entries].sort((a, b) => Buffer.compare(Buffer.from(a.path, "utf8"), Buffer.from(b.path, "utf8")));
  for (const e of sorted) {
    const content = normalised(e.bytes);
    h.update(Buffer.from(e.path, "utf8"));
    h.update("\0");
    h.update(String(content.length));
    h.update("\0");
    h.update(content);
    h.update("\0");
  }
  return h.digest("hex");
}

/** What changed between two versions of a skill, by file (for the owner to read before an update). */
export function diffEntries(before: { path: string; bytes: Buffer }[], after: { path: string; bytes: Buffer }[]): { added: string[]; removed: string[]; changed: string[] } {
  const a = new Map(before.map((e) => [e.path, normalised(e.bytes)]));
  const b = new Map(after.map((e) => [e.path, normalised(e.bytes)]));
  return {
    added: [...b.keys()].filter((p) => !a.has(p)).sort(),
    removed: [...a.keys()].filter((p) => !b.has(p)).sort(),
    changed: [...b.keys()].filter((p) => a.has(p) && !a.get(p)!.equals(b.get(p)!)).sort(),
  };
}
