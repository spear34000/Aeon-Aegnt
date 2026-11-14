import { decodeEntities } from "./feed-text.ts";
import { NOT_WORD_OR_SPACE } from "../core/word-chars.ts";

/**
 * Rules for the headlines the feed starts from. They come from Google News RSS (free, no key, the only host the search contacts),
 * and fixed rules drop clickbait, old items and the same story from another outlet. Writing the posts is the pipeline's job
 * (feed-pipeline.ts); this file only knows how to read and filter headlines.
 */

export type FeedFetch = (url: string, init?: { signal?: AbortSignal; headers?: Record<string, string> }) => Promise<Response>;
/** Asks a model one question. Throws when no model may be used; the feed then keeps plain headlines. */
export type FeedChat = (system: string, user: string) => Promise<string>;

const MAX_AGE_MS = 3 * 86_400_000;
const CANDIDATES = 8;

/** What to search for each interest label; anything else (a label the owner typed) is searched as written. */
const QUERIES: Record<string, string> = {
  "개발": "소프트웨어 개발 OR 개발자", "문서 정리": "문서 작업 OR 생산성 도구", "일정": "시간 관리 OR 일정 관리 앱", "공부": "공부법 OR 교육 정책", "재테크": "재테크 OR 금리",
  "글쓰기": "글쓰기 OR 출판", "디자인": "디자인 OR UX", "번역": "번역 기술 OR 통번역", "쇼핑": "소비자 OR 유통", "여행": "여행 -연예 -예능 -화보", "건강": "건강 OR 의료", "운동": "운동 OR 스포츠 과학",
  "요리": "요리 OR 식품", "반려동물": "반려동물", "자동차": "자동차", "집 꾸미기": "인테리어", "게임": "게임", "음악": "음악 -연예", "영화": "영화 개봉 OR 영화제", "사진": "사진 OR 카메라", "독서": "책 OR 출판",
  "그림": "일러스트 OR 미술", "뉴스": "주요 뉴스", "과학": "과학", "AI": "인공지능",
};

/** Headlines that sell a feeling instead of telling a fact. Matched on the headline, and again on a written title. */
const BAIT: RegExp[] = [
  /[♥♡❤]|\[(?:포토|화보|영상|종합)\]/,
  /충격|경악|발칵|난리|대참사|소름|멘붕|헉|ㅋㅋ|ㅠㅠ|ㄷㄷ/,
  /[!?]{2,}/,
  /알고\s*보니|이럴\s*수가|결국\s*터졌|꼭\s*봐야|이것만\s*알면|이유는\s*\?|모르면\s*손해|지금\s*당장|절대\s*하지\s*마|난리\s*난/,
  /^\s*\[?(?:단독|속보)\]?\s*[^가-힣a-zA-Z0-9]*$/,
  /…\s*왜\s*\??$|\.\.\.\s*왜\s*\??$/,
];

export function looksLikeBait(title: string): boolean {
  return BAIT.some((re) => re.test(title));
}

function tag(block: string, name: string): string | null {
  const m = new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`, "i").exec(block);
  return m ? decodeEntities(m[1]!).trim() : null;
}

export interface RawItem {
  title: string;
  source: string;
  publishedAt: number;
  url: string;
}

/**
 * A headline as plain text. One pass of tag removal is not enough (`<scr<b>ipt>` becomes `<script>`), so it repeats until nothing
 * changes, and what is still a bracket afterwards is dropped: the headline is shown as text, never as markup.
 */
export function plainText(s: string): string {
  let out = s;
  for (let before = ""; before !== out; ) {
    before = out;
    out = out.replace(/<[^>]*>/g, "");
  }
  return out.replace(/[<>]/g, "").replace(/\s+/g, " ").trim();
}

/** Reads the items of an RSS 2.0 document. Anything that is not a plain https link, or has no time, is left out. */
export function parseRss(xml: string): RawItem[] {
  const out: RawItem[] = [];
  for (const m of xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)) {
    const block = m[1]!;
    let title = tag(block, "title");
    const link = tag(block, "link");
    const when = Date.parse(tag(block, "pubDate") ?? "");
    const source = tag(block, "source") ?? "";
    if (!title || !link || !Number.isFinite(when) || !/^https:\/\//i.test(link)) continue;
    title = plainText(title);
    // Google News writes "Headline - Source".
    // (some outlets put their own name in the headline too, so it can be there twice)
    while (source && title.endsWith(` - ${source}`)) title = title.slice(0, -(source.length + 3)).trim();
    if (title.length < 4 || title.length > 200) continue;
    out.push({ title, source: source.slice(0, 40) || "출처 없음", publishedAt: when, url: link });
  }
  return out;
}

function tokens(s: string): Set<string> {
  return new Set(s.toLowerCase().replace(NOT_WORD_OR_SPACE, " ").split(/\s+/).filter((t) => t.length > 1));
}

function similar(a: Set<string>, b: Set<string>): boolean {
  if (a.size === 0 || b.size === 0) return false;
  let both = 0;
  for (const t of a) if (b.has(t)) both++;
  return both / (a.size + b.size - both) >= 0.6;
}

/** Newest first; drops old items, bait, and the same story from another outlet. */
export function pick(items: RawItem[], now: number, limit = CANDIDATES): RawItem[] {
  const fresh = items.filter((i) => now - i.publishedAt <= MAX_AGE_MS && i.publishedAt <= now + 3_600_000 && !looksLikeBait(i.title)).sort((a, b) => b.publishedAt - a.publishedAt);
  const out: RawItem[] = [];
  const seen: Set<string>[] = [];
  for (const i of fresh) {
    const t = tokens(i.title);
    if (seen.some((s) => similar(s, t))) continue;
    seen.push(t);
    out.push(i);
    if (out.length >= limit) break;
  }
  return out;
}

/** The search text for an interest: the interests are the only thing the search is built from (never memory, chat or mail). */
export function queryFor(interest: string): string {
  return QUERIES[interest] ?? interest;
}
