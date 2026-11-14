import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

/**
 * What the feed keeps, in one JSON file next to the settings: the written posts, the owner's brief and limits, how much was
 * made today, the reactions, the taste summary and the order the owner chose. Plain data, written atomically; the engine's
 * Store interface is not involved, so the three Store implementations do not have to know about it.
 */

export interface PostSource {
  title: string;
  outlet: string;
  url: string;
  publishedAt: number;
}

export interface FeedPost {
  /** Twelve hex characters: the id of the first source it cites; also the name of its picture. */
  id: string;
  kicker: string;
  title: string;
  /** Empty for a post that is only a headline (no model, or the model could not be used). */
  body: string;
  /** The interest it belongs to. */
  category: string;
  sources: PostSource[];
  /** Why it is shown. Built from the brief and the interest label, never from private facts. */
  reason: string;
  /** False: the title is the outlet's headline and nothing was written. */
  written: boolean;
  image: boolean;
  createdAt: number;
  /** End of a day (ms) when the sources state a deadline; verified against their text. */
  deadline?: number;
  /** The reader can or must do something by the deadline. */
  actionable?: boolean;
  seenAt?: number;
  liked?: boolean;
}

export interface FeedSettings {
  /** What the feed is: one plain prompt in the owner's words (what, in which tone, what to avoid). */
  brief: string;
  auto: boolean;
  intervalMinutes: number;
  dailyRuns: number;
  dailyPosts: number;
}

export const DEFAULT_BRIEF = "내 관심사의 새 소식을 사실만 직접적으로 짧게 정리해 줘. 낚시성 표현, 추측, 과장은 쓰지 마. 읽는 데 10초 안 걸리게.";
export const BRIEF_MAX = 600;
export const DEFAULT_SETTINGS: FeedSettings = { brief: DEFAULT_BRIEF, auto: true, intervalMinutes: 360, dailyRuns: 4, dailyPosts: 8 };
export const LIMITS = { intervalMinutes: [60, 1440], dailyRuns: [1, 12], dailyPosts: [1, 30] } as const;

export interface DayLedger {
  runs: number;
  posts: number;
  /** Article pages opened (each about a megabyte for a Google link). */
  lookups: number;
}

export interface RunReport {
  at: number;
  status: "made" | "nothing" | "skipped";
  /** Why nothing was written: a code the app can show. */
  reason?: string;
  message?: string;
  made: number;
  candidates: number;
  lookups: number;
  /** "written" by a model; "headlines" when there was no usable model. */
  mode: "written" | "headlines";
  rejected: { title: string; why: string }[];
}

export interface Reaction {
  at: number;
  kind: "like" | "dismiss" | "open";
  category: string;
  kicker: string;
  title: string;
}

export interface Taste {
  text: string;
  updatedAt: number;
  /** "owner": written or edited by the owner, never overwritten by the automatic summary. */
  source: "auto" | "owner";
}

export interface FeedFile {
  version: 1;
  posts: FeedPost[];
  settings: FeedSettings;
  /** By local day (YYYY-MM-DD), the last 14 days. */
  ledger: Record<string, DayLedger>;
  reactions: Reaction[];
  taste: Taste | null;
  /** Likes and removals since the taste summary was last made, to know when enough new ones have come. */
  tasteBasis: number;
  /** How many posts of each category were opened. Only counts: no title, no time. */
  opens: Record<string, number>;
  /** The order the owner chose, kept for that day only. */
  order: { day: string; ids: string[] } | null;
  lastRunAt: number;
  lastRun: RunReport | null;
}

export const MAX_POSTS = 300;
export const MAX_REACTIONS = 100;

export const emptyFile = (): FeedFile => ({ version: 1, posts: [], settings: { ...DEFAULT_SETTINGS }, ledger: {}, reactions: [], taste: null, tasteBasis: 0, opens: {}, order: null, lastRunAt: 0, lastRun: null });

/** The local calendar day of a time, as YYYY-MM-DD: the unit the daily limits and the day's manual order are counted in. */
export function dayKey(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export const clean = (s: unknown, max: number): string => (typeof s === "string" ? s.replace(/[\u0000-\u001f<>]/g, " ").replace(/\s+/g, " ").trim().slice(0, max) : "");

/** Settings as the owner may send them: only known fields, numbers inside their limits, the brief flattened and cut. Throws on a wrong type. */
export function mergeSettings(base: FeedSettings, input: unknown): FeedSettings {
  if (input === null || typeof input !== "object" || Array.isArray(input)) throw new Error("settings must be an object");
  const o = input as Record<string, unknown>;
  const out = { ...base };
  if (o.brief !== undefined) {
    if (typeof o.brief !== "string") throw new Error("brief must be text");
    const b = clean(o.brief, BRIEF_MAX);
    out.brief = b.length >= 5 ? b : DEFAULT_BRIEF;
  }
  if (o.auto !== undefined) {
    if (typeof o.auto !== "boolean") throw new Error("auto must be true or false");
    out.auto = o.auto;
  }
  for (const k of ["intervalMinutes", "dailyRuns", "dailyPosts"] as const) {
    if (o[k] === undefined) continue;
    const v = o[k];
    const [lo, hi] = LIMITS[k];
    if (typeof v !== "number" || !Number.isInteger(v) || v < lo || v > hi) throw new Error(`${k} must be a whole number from ${lo} to ${hi}`);
    out[k] = v;
  }
  return out;
}

export class FeedStore {
  private readonly file: string;

  constructor(file: string) {
    this.file = file;
  }

  read(): FeedFile {
    if (!existsSync(this.file)) return emptyFile();
    try {
      const j = JSON.parse(readFileSync(this.file, "utf8")) as Partial<FeedFile>;
      if (j?.version !== 1 || !Array.isArray(j.posts)) return emptyFile();
      const base = emptyFile();
      let settings = base.settings;
      try {
        settings = mergeSettings(base.settings, j.settings ?? {});
      } catch {
        settings = base.settings;
      }
      return { ...base, ...j, settings, posts: j.posts.filter((p) => p && typeof p.id === "string" && typeof p.title === "string" && Array.isArray(p.sources)), ledger: j.ledger ?? {}, reactions: Array.isArray(j.reactions) ? j.reactions : [] } as FeedFile;
    } catch {
      return emptyFile();
    }
  }

  write(data: FeedFile): void {
    mkdirSync(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp-${process.pid}`;
    writeFileSync(tmp, JSON.stringify(data), { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  /** Reads, lets [change] edit the copy, writes it back. */
  update<T>(change: (f: FeedFile) => T): T {
    const f = this.read();
    const out = change(f);
    this.write(f);
    return out;
  }
}
