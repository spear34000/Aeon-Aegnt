import { existsSync, realpathSync } from "node:fs";
import { dirname, isAbsolute, relative, resolve, sep } from "node:path";

export type Resolved = { ok: true; abs: string } | { ok: false; violation: string };

/** Directories inside the workspace that tools may never touch (our own state and VCS data). */
const PROTECTED = [".aeon", ".git"];

/**
 * Confines file access to the workspace root. Handles `..` traversal, absolute paths,
 * Windows drive switches, and symlinks that point outside (via realpath of the nearest
 * existing ancestor).
 */
export class WorkspaceGuard {
  readonly root: string;
  constructor(root: string) {
    this.root = realpathSync(resolve(root));
  }

  resolve(p: unknown, opts: { allowProtected?: boolean } = {}): Resolved {
    if (typeof p !== "string" || p.length === 0) return { ok: false, violation: "path must be a non-empty string" };
    if (p.includes("\0")) return { ok: false, violation: "path contains NUL" };
    const abs = resolve(this.root, p);
    if (!this.inside(abs)) return { ok: false, violation: `path escapes workspace: ${p}` };

    // Symlink check: the real location of the closest existing ancestor must also be inside.
    let probe = abs;
    while (!existsSync(probe)) {
      const parent = dirname(probe);
      if (parent === probe) break;
      probe = parent;
    }
    let real: string;
    try {
      real = realpathSync(probe);
    } catch {
      return { ok: false, violation: `cannot resolve path: ${p}` };
    }
    if (!this.inside(real)) return { ok: false, violation: `path resolves outside workspace via link: ${p}` };

    if (!opts.allowProtected) {
      const rel = relative(this.root, abs).split(sep)[0]?.toLowerCase();
      if (rel && PROTECTED.includes(rel)) return { ok: false, violation: `protected location: ${rel}` };
    }
    return { ok: true, abs };
  }

  private inside(abs: string): boolean {
    const rel = relative(this.root, abs);
    return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
  }
}
