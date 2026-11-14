import { extractJsonObject, looksLikeInjection } from "../core/untrusted.ts";
import type { FeedChat } from "./feed.ts";
import { clean, MAX_REACTIONS, type FeedFile, type FeedPost, type Reaction, type Taste } from "./feed-store.ts";

/**
 * Personalisation without handing anyone the owner's reading history in words. A reaction (like, remove) is remembered as the
 * post's category, kicker and title, and an open is only counted per category. From those a short summary is made ("likes these,
 * dislikes those") and only that summary goes to the writer. No chat text, no post body and no e-mail ever reaches it. The owner
 * can read the summary, change it or clear it; one the owner wrote is never overwritten.
 */

export const TASTE = {
  /** New likes or removals before the summary is made again. */
  minNew: 5,
  /** And at least this long since the last summary. */
  minGapMs: 20 * 3_600_000,
  maxChars: 300,
} as const;

export function recordReaction(f: FeedFile, post: FeedPost, kind: Reaction["kind"], now: number): void {
  f.reactions = [...f.reactions, { at: now, kind, category: post.category, kicker: post.kicker, title: post.title.slice(0, 100) }].slice(-MAX_REACTIONS);
  if (kind !== "open") f.tasteBasis += 1;
}

const uniq = (xs: string[], n: number) => [...new Set(xs.filter(Boolean))].slice(0, n);

/** What the summary is made from, and the only thing a model is shown. */
export function tasteInputs(f: FeedFile) {
  const rows = (kind: Reaction["kind"]) => f.reactions.filter((r) => r.kind === kind).slice(-20).map((r) => ({ category: r.category, kicker: r.kicker, title: r.title }));
  const opened = Object.entries(f.opens).sort((a, b) => b[1] - a[1]).slice(0, 5).map(([category, count]) => ({ category, count }));
  return { liked: rows("like"), removed: rows("dismiss"), opened };
}

/** The summary made by fixed rules, used when no model can be used or its answer is not plain. */
export function ruleSummary(f: FeedFile): string {
  const i = tasteInputs(f);
  const parts: string[] = [];
  const opened = uniq(i.opened.filter((o) => o.count >= 2).map((o) => o.category), 3);
  const liked = uniq(i.liked.map((r) => r.kicker), 4);
  const removed = uniq(i.removed.map((r) => r.kicker), 4);
  if (opened.length > 0) parts.push(`자주 여는 관심사: ${opened.join(", ")}.`);
  if (liked.length > 0) parts.push(`좋아하는 주제: ${liked.join(", ")}.`);
  if (removed.length > 0) parts.push(`지우는 주제: ${removed.join(", ")}.`);
  return clean(parts.join(" "), TASTE.maxChars);
}

const SYSTEM = [
  "You sum up a reader's taste from their reactions to posts of a news feed, in at most three short Korean sentences: what they like, what they dislike.",
  "Use only the lists you are given. No names of people, no numbers, no web addresses, no advice. If there is too little to say, say little.",
  'Reply with JSON only: {"summary":"..."}',
].join("\n");

/** A model's summary is kept only if it is short, plain, and contains no digits, address, bracket or instruction-like text. */
export function summaryIsPlain(text: unknown): text is string {
  return typeof text === "string" && text.trim().length >= 5 && text.length <= TASTE.maxChars && !/[\d<>]|https?:|www\./i.test(text) && !looksLikeInjection(text);
}

/** True when the summary should be made again: enough new reactions, enough time, and not an owner-written one. */
export function tasteDue(f: FeedFile, now: number): boolean {
  if (f.taste?.source === "owner") return false;
  return f.tasteBasis >= TASTE.minNew && (!f.taste || now - f.taste.updatedAt >= TASTE.minGapMs);
}

/** Makes the summary (by the model when it may be used, else by rules) and returns it; the caller stores it. Never throws. */
export async function makeTaste(f: FeedFile, chat: FeedChat | undefined, now: number): Promise<Taste | null> {
  let text = "";
  if (chat) {
    try {
      const parsed = extractJsonObject(await chat(SYSTEM, JSON.stringify(tasteInputs(f)))) as { summary?: unknown } | null;
      if (summaryIsPlain(parsed?.summary)) text = clean(parsed!.summary, TASTE.maxChars);
    } catch {
      text = "";
    }
  }
  if (!text) text = ruleSummary(f);
  return text ? { text, updatedAt: now, source: "auto" } : null;
}
