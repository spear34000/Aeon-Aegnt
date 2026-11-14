import { NOT_WORD, NOT_WORD_OR_SPACE } from "../core/word-chars.ts";
/**
 * Text helpers for the feed pipeline: reading an article page (with its character set), and the plain checks the reviewer
 * relies on. Nothing here calls a model: each check is a fixed rule that can be tested with a fixed input.
 */

export function decodeEntities(s: string): string {
  return s
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&#(\d+);/g, (_, n: string) => String.fromCodePoint(Math.min(Number(n), 0x10ffff)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n: string) => String.fromCodePoint(Math.min(parseInt(n, 16), 0x10ffff)))
    .replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
}

/**
 * The text of a page as the page says it is encoded: the character set of the header, else of a meta tag in the first bytes,
 * else UTF-8. Korean sites still serve EUC-KR. When the runtime does not know the character set, or the result is mostly
 * replacement characters, there is no text (null): garbage is never handed to the writer.
 */
export function decodeHtml(bytes: Uint8Array, contentType: string): string | null {
  const head = Buffer.from(bytes.subarray(0, 4000)).toString("latin1");
  const label = (/charset\s*=\s*["']?([\w-]+)/i.exec(contentType)?.[1] ?? /<meta[^>]+charset\s*=\s*["']?([\w-]+)/i.exec(head)?.[1] ?? "utf-8").toLowerCase();
  let text: string;
  try {
    text = new TextDecoder(label).decode(bytes);
  } catch {
    if (label === "utf-8" || label === "utf8") return null;
    text = new TextDecoder("utf-8").decode(bytes);
  }
  const bad = (text.match(/�/g) ?? []).length;
  return bad > Math.max(2, text.length * 0.01) ? null : text;
}

const BOILERPLATE = /Copyright|ⓒ|©|무단\s*전재|재배포\s*(?:및\s*)?금지|구독|기자\s*페이스북|네이버\s*채널|공유하기|광고/i;

const flat = (s: string) => decodeEntities(s.replace(/<[^>]*>/g, " ")).replace(/\s+/g, " ").trim();

/** The description of the page head followed by the first paragraphs of the article, at most [max] characters. May be empty. */
export function extractArticleText(html: string, max = 2000): string {
  const head = html.slice(0, 600_000);
  const body = head.replace(/<!--[\s\S]*?-->/g, " ").replace(/<(script|style|noscript|nav|header|footer|aside|form|svg)\b[\s\S]*?<\/\1>/gi, " ");
  const parts: string[] = [];
  for (const t of body.match(/<meta\b[^>]*>/gi) ?? []) {
    const key = /(?:property|name)\s*=\s*["'](og:description|description)["']/i.exec(t)?.[1];
    const content = /content\s*=\s*"([^"]+)"|content\s*=\s*'([^']+)'/i.exec(t);
    if (key && content) {
      const d = flat((content[1] ?? content[2])!);
      if (d.length >= 20 && !parts.includes(d)) parts.push(d);
      if (key.toLowerCase() === "og:description") break;
    }
  }
  for (const m of body.matchAll(/<p\b[^>]*>([\s\S]*?)<\/p>/gi)) {
    const p = flat(m[1]!);
    if (p.length >= 30 && !BOILERPLATE.test(p) && !parts.includes(p)) parts.push(p);
    if (parts.join(" ").length >= max) break;
  }
  return parts.join(" ").slice(0, max).trim();
}

/** The numbers in a text, written one way: "1,200" and "1200" are the same, "03" and "3" are the same. */
export function numbersIn(s: string): Set<string> {
  const plain = s.replace(/(\d),(?=\d{3}(?!\d))/g, "$1");
  return new Set((plain.match(/\d+(?:\.\d+)?/g) ?? []).map((n) => String(Number(n))));
}

const letters = (s: string) => s.toLowerCase().replace(NOT_WORD, "");

/** Sørensen–Dice similarity of two texts by pairs of characters, 0 to 1. Spaces and punctuation do not count. */
export function dice(a: string, b: string): number {
  const x = letters(a);
  const y = letters(b);
  if (x.length < 2 || y.length < 2) return x === y && x.length > 0 ? 1 : 0;
  const pairs = (s: string) => {
    const m = new Map<string, number>();
    for (let i = 0; i + 2 <= s.length; i++) {
      const p = s.slice(i, i + 2);
      m.set(p, (m.get(p) ?? 0) + 1);
    }
    return m;
  };
  const pa = pairs(x);
  const pb = pairs(y);
  let both = 0;
  for (const [p, n] of pa) both += Math.min(n, pb.get(p) ?? 0);
  return (2 * both) / (x.length - 1 + (y.length - 1));
}

/** The longest stretch a text takes over from a source: in consecutive words, and in characters (spaces ignored). */
export function longestSharedRun(text: string, source: string): { words: number; chars: number } {
  const words = (s: string) => s.toLowerCase().replace(NOT_WORD_OR_SPACE, " ").split(/\s+/).filter(Boolean);
  const a = words(text);
  const b = words(source);
  let w = 0;
  let prev = new Array<number>(b.length + 1).fill(0);
  for (let i = 1; i <= a.length; i++) {
    const cur = new Array<number>(b.length + 1).fill(0);
    for (let j = 1; j <= b.length; j++) {
      if (a[i - 1] === b[j - 1]) {
        cur[j] = prev[j - 1]! + 1;
        if (cur[j]! > w) w = cur[j]!;
      }
    }
    prev = cur;
  }
  const x = letters(text);
  const y = letters(source);
  let c = 0;
  let p2 = new Array<number>(y.length + 1).fill(0);
  for (let i = 1; i <= x.length; i++) {
    const cur = new Array<number>(y.length + 1).fill(0);
    for (let j = 1; j <= y.length; j++) {
      if (x[i - 1] === y[j - 1]) {
        cur[j] = p2[j - 1]! + 1;
        if (cur[j]! > c) c = cur[j]!;
      }
    }
    p2 = cur;
  }
  return { words: w, chars: c };
}
