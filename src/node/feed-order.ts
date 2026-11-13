import { dayKey, type FeedPost } from "./feed-store.ts";

const DAY_MS = 86_400_000;

/** How much a post wants to be near the top: soon-due and to-do posts and unread ones up, past-due ones down. A pure function of the post and the time. */
export function priority(p: FeedPost, now: number): number {
  const past = p.deadline !== undefined && p.deadline < now;
  const soon = p.deadline !== undefined && !past && p.deadline - now <= 3 * DAY_MS;
  return (past ? -1000 : 0) + (soon ? 300 : 0) + (p.actionable === true && !past ? 200 : 0) + (p.seenAt === undefined ? 100 : 0);
}

/**
 * The order the posts are shown in. By priority, then newest first. If the owner moved a post today, that order is kept for
 * today (the posts they placed come first, in their order) and the day after it is forgotten.
 */
export function orderPosts(posts: FeedPost[], now: number, manual: { day: string; ids: string[] } | null): FeedPost[] {
  const auto = [...posts].sort((a, b) => priority(b, now) - priority(a, now) || b.createdAt - a.createdAt);
  if (!manual || manual.day !== dayKey(now)) return auto;
  const byId = new Map(auto.map((p) => [p.id, p] as const));
  const placed = manual.ids.map((id) => byId.get(id)).filter((p): p is FeedPost => p !== undefined);
  const placedIds = new Set(placed.map((p) => p.id));
  return [...placed, ...auto.filter((p) => !placedIds.has(p.id))];
}

/** Puts one post first or last for today, keeping what was already placed today. */
export function moveForToday(posts: FeedPost[], now: number, manual: { day: string; ids: string[] } | null, id: string, to: "top" | "bottom"): { day: string; ids: string[] } {
  const current = orderPosts(posts, now, manual).map((p) => p.id);
  const rest = current.filter((x) => x !== id);
  return { day: dayKey(now), ids: to === "top" ? [id, ...rest] : [...rest, id] };
}
