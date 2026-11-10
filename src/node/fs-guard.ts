import { existsSync, realpathSync } from "node:fs";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";

export type Resolved = { ok: true; abs: string } | { ok: false; violation: string };

/** Directories inside the workspace that tools may never touch (our own state and VCS data). */
const PROTECTED = [".aeon", ".git"];

/**
 * Confines file access to the workspace root. Handles `..` traversal, absolute paths,
 * Windows drive switches, and symlinks that point outside. Protection of `.aeon` / `.git` is decided on
 * the CANONICAL path (8.3 short names expanded, links resolved), and Windows aliasing tricks
 * (trailing dots/spaces, `::$DATA` streams, `~1` short names) are rejected outright.
 */
export class WorkspaceGuard {
  readonly root: string;
  constructor(root: string) {
    this.root = realpathSync.native(resolve(root));
  }

  resolve(p: unknown, opts: { allowProtected?: boolean } = {}): Resolved {
    if (typeof p !== "string" || p.length === 0) return { ok: false, violation: "path must be a non-empty string" };
    if (p.includes("\0")) return { ok: false, violation: "path contains NUL" };
    const abs = resolve(this.root, p);
    if (!this.inside(abs)) return { ok: false, violation: `path escapes workspace: ${p}` };

    if (!opts.allowProtected && process.platform === "win32") {
      for (const seg of relative(this.root, abs).split(sep).filter(Boolean)) {
        if (/[. ]$/.test(seg) || seg.includes(":") || /~\d/.test(seg)) {
          return { ok: false, violation: `unsafe path segment (Windows aliasing): ${seg}` };
        }
      }
    }

    // Canonicalise: real location of the closest existing ancestor + the not-yet-existing tail.
    let probe = abs;
    while (!existsSync(probe)) {
      const parent = dirname(probe);
      if (parent === probe) break;
      probe = parent;
    }
    let real: string;
    try {
      real = realpathSync.native(probe);
    } catch {
      return { ok: false, violation: `cannot resolve path: ${p}` };
    }
    const canonical = join(real, relative(probe, abs));
    if (!this.inside(canonical)) return { ok: false, violation: `path resolves outside workspace via link: ${p}` };

    if (!opts.allowProtected) {
      const first = relative(this.root, canonical).split(sep)[0]?.toLowerCase();
      if (first && PROTECTED.includes(first)) return { ok: false, violation: `protected location: ${first}` };
    }
    return { ok: true, abs: canonical };
  }

  private inside(abs: string): boolean {
    const rel = relative(this.root, abs);
    return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
  }
}
