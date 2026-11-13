import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { MemoryStore, type StoreState } from "../core/memory-store.ts";

/**
 * A Store that keeps everything in memory and saves it as one JSON file after every change (written to a temporary
 * file and renamed, so a crash never leaves half a file). It exists for places without `node:sqlite` (Node before 22,
 * such as the Node 18 that runs inside the Android app); the desktop uses SqliteStore. It behaves exactly like
 * MemoryStore, which is what the store conformance tests check. Fine for one owner's tasks; not for millions of events.
 */
export class FileStore extends MemoryStore {
  private readonly path: string;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private dirty = false;

  constructor(path: string) {
    super();
    this.path = path;
    this.load();
    // A change is written soon, and anything still waiting is written when the process is asked to stop.
    process.once("exit", () => this.flush());
  }

  private load(): void {
    if (!existsSync(this.path)) return;
    try {
      const s = JSON.parse(readFileSync(this.path, "utf8")) as StoreState;
      if (!Array.isArray(s.tasks) || !Array.isArray(s.effects) || !Array.isArray(s.events) || !Array.isArray(s.approvals) || !Array.isArray(s.usage)) throw new Error("not a store file");
      this.importState(s);
    } catch {
      // Keep the unreadable file for inspection and start empty rather than refusing to start.
      try {
        renameSync(this.path, `${this.path}.corrupt-${Date.now()}`);
      } catch {
        /* nothing more can be done */
      }
    }
  }

  protected override changed(): void {
    this.dirty = true;
    if (this.timer) return;
    this.timer = setTimeout(() => this.flush(), 150);
    this.timer.unref?.();
  }

  /** Writes now if anything changed since the last write. */
  flush(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (!this.dirty) return;
    this.dirty = false;
    mkdirSync(dirname(this.path), { recursive: true });
    const tmp = `${this.path}.tmp-${process.pid}`;
    writeFileSync(tmp, JSON.stringify(this.exportState()), "utf8");
    renameSync(tmp, this.path);
  }
}
