import { createHash, randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { looksLikeInjection } from "../core/untrusted.ts";
import { NOT_WORD_OR_SPACE } from "../core/word-chars.ts";
import { dice } from "./feed-text.ts";

/**
 * What the owner wants the agent to remember across tasks, kept as plain text the owner can read, edit and delete.
 * Design, from the sources read for it (docs/research/2026-11-15-memory.md):
 *  - Generative Agents: a memory stream retrieved by recency, importance and relevance, the three scores added.
 *  - Mem0 / LangMem: what a task leaves behind is a few *candidates*, compared with what is already kept before anything is
 *    added (here: skipped if the same, offered as a correction if close), and formed in the background of the task, not in it.
 *  - Zep: a fact that changes is not silently overwritten: the older wording stays visible as history; retrieval is hybrid
 *    (a BM25-style term score plus recency and importance) and avoids returning the same thing twice (a diversity step).
 *  - MemoryBank: a memory that is used is reinforced and one that is not fades; here fading only lowers its rank and, after
 *    ninety unused days, puts it in a tidy-up list for the owner. Nothing is deleted by fading.
 *  - CoALA: reading and writing are separate actions; writing is the risky one, and deleting and editing are as much part of
 *    memory as adding. All writes by the agent go through the owner.
 * Nothing the agent proposes is kept without the owner reading the exact text, because what is kept steers every later task.
 */

export type MemoryKind = "preference" | "fact";

export type MemorySource =
  /** The owner typed it (or edited it). */
  | { type: "owner" }
  /** The agent proposed it during a task and the owner approved the exact text. */
  | { type: "agent"; taskId: string };

export interface MemoryItem {
  id: string;
  text: string;
  kind: MemoryKind;
  source: MemorySource;
  createdAt: number;
  lastUsedAt?: number;
  uses: number;
  /** After this time the memory is not used any more (a plan for next month, say). It stays in the list as past for 30 days. */
  expiresAt?: number;
  /** Earlier wordings of this memory, newest first, at most three. Deleted with the memory. */
  history?: { text: string; at: number }[];
}

/** Something a task suggests remembering. It waits for the owner and is not used by any task until accepted. */
export interface MemoryProposal {
  id: string;
  text: string;
  kind: MemoryKind;
  taskId: string;
  createdAt: number;
  /** A kept memory this one looks like a newer wording of: accepting it corrects that memory instead of adding another. */
  replaces?: string;
}

export const MEMORY_TEXT_MAX = 300;
export const MEMORY_MAX_ITEMS = 200;
export const MAX_PROPOSALS = 10;
const MAX_IGNORED = 100;
const DAY_MS = 86_400_000;
/// How long a memory that ran out stays in the list before it is removed.
export const PAST_KEEP_DAYS = 30;
const SAME = 0.85;
const CLOSE = 0.5;
const REDUNDANT = 0.7;

/** Words that say nothing about a topic: "the" in a goal must not make every memory with "the" look relevant. */
const STOP = new Set(
  "a an and are as at be but by can do for from has have how i if in into is it its my me of on or our so than that the their them then there these they this to up was we were what when where which who will with you your about all any not out new get let make want need please".split(" "),
);
/** Endings and helper words that make Korean pairs of characters match without meaning anything. */
const STOP_KO = new Set("에서 으로 에게 에는 에도 니다 습니 합니 하는 하고 하다 해서 해줘 해주 있는 있다 없는 이다 입니 그리 그런 이런 저런".split(" "));

/** Words of a text: lower-cased letters and digits, plus character pairs for Hangul and CJK so that endings do not hide a match. */
export function memoryTokens(s: string): Set<string> {
  const out = new Set<string>();
  const words = s.toLowerCase().replace(NOT_WORD_OR_SPACE, " ").split(/\s+/).filter(Boolean);
  for (const w of words) {
    const cjk = /[ㄱ-ㆎ가-힣一-鿿]/.test(w);
    if (w.length > 1 && !STOP.has(w) && (cjk || w.length > 2 || /\d/.test(w))) out.add(w);
    if (cjk) for (let i = 0; i + 2 <= w.length; i++) if (!STOP_KO.has(w.slice(i, i + 2))) out.add(w.slice(i, i + 2));
  }
  return out;
}

const norm = (t: string) => t.toLowerCase().replace(/\s+/g, "");
const sha = (t: string) => createHash("sha1").update(norm(t)).digest("hex");

interface MemoryFile {
  version: 2;
  items: MemoryItem[];
  proposals: MemoryProposal[];
  /** Hashes of proposals the owner turned down, so the same sentence is not suggested again. */
  ignored: string[];
}

const emptyFile = (): MemoryFile => ({ version: 2, items: [], proposals: [], ignored: [] });

export class MemoryService {
  private readonly file: string;
  private readonly now: () => number;

  constructor(file: string, now: () => number = Date.now) {
    this.file = file;
    this.now = now;
  }

  /** Reads the file. A version 1 file (items only) is read as it is and becomes version 2 the next time anything is written. */
  private read(): MemoryFile {
    if (!existsSync(this.file)) return emptyFile();
    try {
      const j = JSON.parse(readFileSync(this.file, "utf8")) as { version?: number; items?: MemoryItem[]; proposals?: MemoryProposal[]; ignored?: string[] };
      if ((j?.version !== 1 && j?.version !== 2) || !Array.isArray(j.items)) return emptyFile();
      const items = j.items.filter((i) => i && typeof i.id === "string" && typeof i.text === "string" && (i.kind === "preference" || i.kind === "fact"));
      const proposals = Array.isArray(j.proposals) ? j.proposals.filter((p) => p && typeof p.id === "string" && typeof p.text === "string" && (p.kind === "preference" || p.kind === "fact")) : [];
      return { version: 2, items, proposals, ignored: Array.isArray(j.ignored) ? j.ignored.filter((h) => typeof h === "string") : [] };
    } catch {
      return emptyFile();
    }
  }

  private write(f: MemoryFile): void {
    mkdirSync(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp-${process.pid}`;
    writeFileSync(tmp, JSON.stringify(f), { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  /** Every kept memory, including past ones (see [isPast]). */
  list(): MemoryItem[] {
    return this.read().items;
  }

  isPast(i: MemoryItem, at = this.now()): boolean {
    return i.expiresAt !== undefined && i.expiresAt <= at;
  }

  /** The memories in use: not past. */
  active(): MemoryItem[] {
    const at = this.now();
    return this.list().filter((i) => !this.isPast(i, at));
  }

  /** Removes memories that ran out more than 30 days ago. Returns how many. */
  purgePast(): number {
    const f = this.read();
    const at = this.now();
    const keep = f.items.filter((i) => i.expiresAt === undefined || i.expiresAt + PAST_KEEP_DAYS * DAY_MS > at);
    if (keep.length === f.items.length) return 0;
    const gone = f.items.length - keep.length;
    f.items = keep;
    this.write(f);
    return gone;
  }

  /** The text as it will be stored: one line, no markup brackets, cut at 300 characters. Null when nothing is left. */
  static clean(text: unknown): string | null {
    if (typeof text !== "string") return null;
    const t = text.replace(/[\u0000-\u001f<>]/g, " ").replace(/\s+/g, " ").trim().slice(0, MEMORY_TEXT_MAX);
    return t.length >= 3 ? t : null;
  }

  /**
   * Adds an item, or returns the existing one with the same words (or nearly the same: the same kind and 85 percent alike).
   * At most 200 are kept: the one used longest ago makes room.
   */
  add(text: string, kind: MemoryKind, source: MemorySource, opts: { expiresAt?: number } = {}): MemoryItem {
    const clean = MemoryService.clean(text);
    if (!clean) throw new Error("the memory is too short");
    const f = this.read();
    const dup = f.items.find((i) => sha(i.text) === sha(clean) || (i.kind === kind && !this.isPast(i) && dice(i.text, clean) >= SAME));
    if (dup) return dup;
    const item: MemoryItem = { id: `mem_${randomBytes(5).toString("hex")}`, text: clean, kind, source, createdAt: this.now(), uses: 0, ...(opts.expiresAt !== undefined ? { expiresAt: opts.expiresAt } : {}) };
    f.items.push(item);
    while (f.items.length > MEMORY_MAX_ITEMS) {
      let oldest = 0;
      f.items.forEach((i, idx) => {
        if ((i.lastUsedAt ?? i.createdAt) < (f.items[oldest]!.lastUsedAt ?? f.items[oldest]!.createdAt)) oldest = idx;
      });
      f.items.splice(oldest, 1);
    }
    this.write(f);
    return item;
  }

  /**
   * Changes a memory. A new wording keeps the old one as history (at most three) and counts as the owner's own words.
   * [expiresAt] null removes the end date. Returns the item, or null when there is no such memory.
   */
  update(id: string, patch: { text?: string; kind?: MemoryKind; expiresAt?: number | null }): MemoryItem | null {
    const f = this.read();
    const item = f.items.find((i) => i.id === id);
    if (!item) return null;
    if (patch.text !== undefined) {
      const clean = MemoryService.clean(patch.text);
      if (!clean) throw new Error("the memory is too short");
      if (norm(clean) !== norm(item.text)) {
        item.history = [{ text: item.text, at: this.now() }, ...(item.history ?? [])].slice(0, 3);
        item.text = clean;
        item.source = { type: "owner" };
      }
    }
    if (patch.kind !== undefined) item.kind = patch.kind;
    if (patch.expiresAt === null) delete item.expiresAt;
    else if (patch.expiresAt !== undefined) item.expiresAt = patch.expiresAt;
    this.write(f);
    return item;
  }

  /** Deletes a memory and its history. */
  remove(id: string): boolean {
    const f = this.read();
    const rest = f.items.filter((i) => i.id !== id);
    if (rest.length === f.items.length) return false;
    f.items = rest;
    // A proposal that meant to correct this memory becomes a plain addition.
    f.proposals = f.proposals.map((p) => (p.replaces === id ? { id: p.id, text: p.text, kind: p.kind, taskId: p.taskId, createdAt: p.createdAt } : p));
    this.write(f);
    return true;
  }

  // ---------------------------------------------------------------- retrieval

  /** Scores for a query: relevance (BM25-style over the memories in use, saturating), recency that fades slower with use, importance. */
  private ranked(query: string): { item: MemoryItem; rel: number; score: number }[] {
    const items = this.active();
    const q = memoryTokens(query);
    const docs = items.map((item) => memoryTokens(item.text));
    const avg = docs.reduce((n, d) => n + d.size, 0) / Math.max(1, docs.length);
    const df = new Map<string, number>();
    for (const d of docs) for (const t of d) df.set(t, (df.get(t) ?? 0) + 1);
    const at = this.now();
    return items
      .map((item, i) => {
        const d = docs[i]!;
        let bm = 0;
        for (const t of q) {
          if (!d.has(t)) continue;
          const idf = Math.log(1 + (items.length + 1) / ((df.get(t) ?? 0) + 0.5));
          bm += idf * (2.2 / (1 + 1.2 * (0.25 + (0.75 * d.size) / Math.max(1, avg))));
        }
        const rel = bm / (bm + 1);
        const days = Math.max(0, (at - (item.lastUsedAt ?? item.createdAt)) / DAY_MS);
        // A memory that is used is reinforced: it fades more slowly and gets a small lift (MemoryBank's idea; the constants are ours).
        const recency = Math.exp(-days / (30 * (1 + Math.log(1 + item.uses)))) + 0.1 * Math.log(1 + item.uses);
        const importance = item.source.type === "owner" ? 1 : 0.6;
        return { item, rel, score: recency + importance + rel * 2 };
      })
      .sort((a, b) => b.score - a.score);
  }

  /**
   * What goes into the prompt of a task: how the owner likes things done (preferences, always, up to 4) and the facts that
   * match the goal, held to a character budget, and never two that say the same thing. Past memories are not used.
   */
  relevant(goal: string, maxItems = 8, maxChars = 1200): MemoryItem[] {
    const ranked = this.ranked(goal);
    const picks: MemoryItem[] = [];
    let chars = 0;
    const take = (item: MemoryItem) => {
      if (picks.length >= maxItems || chars + item.text.length > maxChars || picks.includes(item)) return;
      if (picks.some((p) => dice(p.text, item.text) >= REDUNDANT)) return;
      picks.push(item);
      chars += item.text.length;
    };
    ranked.filter((r) => r.item.kind === "preference").slice(0, 4).forEach((r) => take(r.item));
    ranked.filter((r) => r.item.kind === "fact" && r.rel >= 0.25).forEach((r) => take(r.item));
    return picks;
  }

  /** For the search tool: the best matches, with some relevance, whatever the kind. */
  search(query: string, limit = 8): MemoryItem[] {
    return this.ranked(query).filter((r) => r.rel > 0).slice(0, limit).map((r) => r.item);
  }

  /** Remembers that these items were shown to a task (recency counts from here). */
  markUsed(ids: string[]): void {
    if (ids.length === 0) return;
    const f = this.read();
    const at = this.now();
    let changed = false;
    for (const i of f.items) {
      if (ids.includes(i.id)) {
        i.lastUsedAt = at;
        i.uses += 1;
        changed = true;
      }
    }
    if (changed) this.write(f);
  }

  // ---------------------------------------------------------------- proposals

  proposals(): MemoryProposal[] {
    return this.read().proposals;
  }

  /**
   * Takes what a finished task suggests remembering (at most two) and queues it for the owner. Each is compared with what is
   * kept first (Mem0's update step, done here by plain rules): the same as a kept memory or a turned-down proposal is dropped,
   * a close one is offered as a correction of that memory. Text that looks like a secret or an instruction to the model is
   * dropped. Nothing becomes a memory here; at most ten wait, the oldest leaving first.
   */
  propose(raw: unknown, taskId: string, isSecret: (text: string) => boolean = () => false): MemoryProposal[] {
    if (!Array.isArray(raw)) return [];
    const f = this.read();
    const at = this.now();
    const made: MemoryProposal[] = [];
    for (const r of raw.slice(0, 2)) {
      const o: Record<string, unknown> = r !== null && typeof r === "object" && !Array.isArray(r) ? (r as Record<string, unknown>) : { text: r };
      const text = MemoryService.clean(o.text);
      const kind: MemoryKind = o.kind === "preference" ? "preference" : "fact";
      if (!text || isSecret(text) || looksLikeInjection(text) || /https?:|www\./i.test(text)) continue;
      if (f.ignored.includes(sha(text))) continue;
      const live = f.items.filter((i) => !this.isPast(i, at));
      if (live.some((i) => sha(i.text) === sha(text) || (i.kind === kind && dice(i.text, text) >= SAME))) continue;
      if (f.proposals.some((p) => sha(p.text) === sha(text) || dice(p.text, text) >= SAME)) continue;
      const close = live.filter((i) => i.kind === kind && dice(i.text, text) >= CLOSE).sort((a, b) => dice(b.text, text) - dice(a.text, text))[0];
      const p: MemoryProposal = { id: `prop_${randomBytes(5).toString("hex")}`, text, kind, taskId, createdAt: at, ...(close ? { replaces: close.id } : {}) };
      f.proposals.push(p);
      made.push(p);
    }
    if (made.length === 0) return [];
    while (f.proposals.length > MAX_PROPOSALS) f.proposals.shift();
    this.write(f);
    return made;
  }

  /**
   * The owner accepts a proposal, possibly with their own wording ([text]). A proposal that corrects a kept memory changes that
   * memory (the old wording stays as history); otherwise a memory is added. Null when there is no such proposal.
   */
  accept(id: string, text?: string, expiresAt?: number): MemoryItem | null {
    const f = this.read();
    const p = f.proposals.find((x) => x.id === id);
    if (!p) return null;
    const clean = MemoryService.clean(text ?? p.text);
    if (!clean) throw new Error("the memory is too short");
    const edited = norm(clean) !== norm(p.text);
    f.proposals = f.proposals.filter((x) => x.id !== id);
    this.write(f);
    const target = p.replaces ? f.items.find((i) => i.id === p.replaces) : undefined;
    if (target) {
      this.update(target.id, { text: clean, ...(expiresAt !== undefined ? { expiresAt } : {}) });
      // update() calls a new wording the owner's own; an unedited proposal is still the agent's, approved word for word.
      const g = this.read();
      const stored = g.items.find((i) => i.id === target.id)!;
      if (!edited) {
        stored.source = { type: "agent", taskId: p.taskId };
        this.write(g);
      }
      return stored;
    }
    return this.add(clean, p.kind, edited ? { type: "owner" } : { type: "agent", taskId: p.taskId }, expiresAt !== undefined ? { expiresAt } : {});
  }

  /** The owner turns a proposal down; the same sentence is not suggested again. */
  ignore(id: string): boolean {
    const f = this.read();
    const p = f.proposals.find((x) => x.id === id);
    if (!p) return false;
    f.proposals = f.proposals.filter((x) => x.id !== id);
    f.ignored = [...f.ignored, sha(p.text)].slice(-MAX_IGNORED);
    this.write(f);
    return true;
  }

  // ---------------------------------------------------------------- tidying

  /** Suggestions for the owner, never acted on by themselves: memories that say nearly the same, and facts not used for 90 days. */
  tidy(): { type: "duplicate" | "unused"; ids: string[]; text: string }[] {
    const items = this.active();
    const at = this.now();
    const out: { type: "duplicate" | "unused"; ids: string[]; text: string }[] = [];
    for (let i = 0; i < items.length && out.length < 5; i++) {
      for (let j = i + 1; j < items.length; j++) {
        if (items[i]!.kind === items[j]!.kind && dice(items[i]!.text, items[j]!.text) >= REDUNDANT) {
          out.push({ type: "duplicate", ids: [items[i]!.id, items[j]!.id], text: items[j]!.text });
          break;
        }
      }
    }
    const taken = new Set(out.flatMap((s) => s.ids));
    for (const it of items) {
      if (out.length >= 10) break;
      if (it.kind === "fact" && !taken.has(it.id) && at - (it.lastUsedAt ?? it.createdAt) >= 90 * DAY_MS && it.uses <= 1) out.push({ type: "unused", ids: [it.id], text: it.text });
    }
    return out;
  }
}
