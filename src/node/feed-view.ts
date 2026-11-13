import { orderPosts } from "./feed-order.ts";
import { queryFor } from "./feed.ts";
import { dayKey, type FeedPost, type FeedSettings, type RunReport, type Taste } from "./feed-store.ts";
import type { FeedPipeline } from "./feed-pipeline.ts";

export interface FeedStateBody {
  posts: FeedPost[];
  settings: FeedSettings;
  status: {
    today: { runs: number; posts: number; lookups: number };
    caps: { runs: number; posts: number };
    lastRunAt: number;
    lastRun: RunReport | null;
    /** When the timer will next make posts, or null (automatic runs off, no interests, or a limit reached). */
    nextRunAt: number | null;
  };
  interests: string[];
  /** What is actually searched for each interest: only the interest labels are used. */
  queries: { interest: string; query: string }[];
  taste: Taste | null;
  online: boolean;
}

/** Everything the feed screen needs, in the order it shows the posts. */
export function feedState(feed: FeedPipeline, interests: string[], online: boolean, now: number): FeedStateBody {
  const f = feed.store.read();
  const today = f.ledger[dayKey(now)] ?? { runs: 0, posts: 0, lookups: 0 };
  const limited = today.runs >= f.settings.dailyRuns || today.posts >= f.settings.dailyPosts;
  const next = f.settings.auto && online && interests.length > 0 && !limited ? Math.max(now, f.lastRunAt + f.settings.intervalMinutes * 60_000) : null;
  return {
    posts: orderPosts(f.posts, now, f.order),
    settings: f.settings,
    status: { today, caps: { runs: f.settings.dailyRuns, posts: f.settings.dailyPosts }, lastRunAt: f.lastRunAt, lastRun: f.lastRun, nextRunAt: next },
    interests,
    queries: interests.map((interest) => ({ interest, query: queryFor(interest) })),
    taste: f.taste,
    online,
  };
}
