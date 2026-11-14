import { createHash } from "node:crypto";
import type { ArticleFetcher, ArticleInfo } from "./feed-images.ts";
import { parseRss, pick, queryFor, type FeedFetch } from "./feed.ts";

/**
 * The researcher's side of the feed: where candidates come from. Every source is an adapter with the same two calls, so a
 * source can be added or replaced without touching the writer or the reviewer. Candidates carry the address the reviewer
 * will cite; the model never types an address.
 */

export interface Candidate {
  /** Twelve hex characters from the address; also the id of the post that cites it first, and of its picture. */
  id: string;
  /** The interest it was found for. */
  interest: string;
  title: string;
  outlet: string;
  url: string;
  publishedAt: number;
}

export interface SourceAdapter {
  readonly id: string;
  /** Recent candidates for one interest. Queries are made from the interest label only. */
  search(interest: string, now: number): Promise<{ items: Candidate[]; error?: string }>;
  /** The text (and picture) of a candidate's article, or null when it cannot be had. */
  article(c: Candidate, opts: { image: boolean }): Promise<ArticleInfo | null>;
}

export const candidateId = (url: string): string => createHash("sha1").update(url).digest("hex").slice(0, 12);

const HOST = "news.google.com";
const MAX_BYTES = 1_500_000;
const PER_INTEREST = 3;

/** Google News RSS, one search per interest (free, no key), and the article pages behind its links. */
export class GoogleNewsAdapter implements SourceAdapter {
  readonly id = "google-news";
  private readonly fetchImpl: FeedFetch;
  private readonly fetchArticle: ArticleFetcher | null;

  constructor(o: { fetchImpl?: FeedFetch; articles?: ArticleFetcher } = {}) {
    this.fetchImpl = o.fetchImpl ?? (fetch as unknown as FeedFetch);
    this.fetchArticle = o.articles ?? null;
  }

  async search(interest: string, now: number): Promise<{ items: Candidate[]; error?: string }> {
    const url = `https://${HOST}/rss/search?q=${encodeURIComponent(queryFor(interest))}&hl=ko&gl=KR&ceid=KR:ko`;
    try {
      const res = await this.fetchImpl(url, { signal: AbortSignal.timeout(15_000), headers: { "user-agent": "aeon-feed", accept: "application/rss+xml, application/xml" } });
      if (!res.ok) return { items: [], error: `뉴스 출처가 ${res.status}로 답했어요` };
      if (Number(res.headers.get("content-length") ?? 0) > MAX_BYTES) return { items: [], error: "뉴스 출처의 답이 너무 커요" };
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length > MAX_BYTES) return { items: [], error: "뉴스 출처의 답이 너무 커요" };
      const items = pick(parseRss(buf.toString("utf8")), now, PER_INTEREST).map<Candidate>((i) => ({ id: candidateId(i.url), interest, title: i.title, outlet: i.source, url: i.url, publishedAt: i.publishedAt }));
      return { items };
    } catch {
      return { items: [], error: "뉴스 출처에 닿지 못했어요" };
    }
  }

  async article(c: Candidate, opts: { image: boolean }): Promise<ArticleInfo | null> {
    return this.fetchArticle ? this.fetchArticle(c.url, opts) : null;
  }
}
