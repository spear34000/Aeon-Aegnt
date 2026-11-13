import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { safeFetch } from "./net-guard.ts";

/**
 * Article images for the feed. Google News RSS has none, so for the few items that get one the server does what a browser
 * would: opens the Google News link, asks Google's own page for the article address (an undocumented call that can stop
 * working; then there is simply no image), reads the article page's `og:image`, fetches that image and keeps a copy.
 * The phone only ever talks to the server, so no publisher learns the phone's address. Every fetch of a publisher goes
 * through the SSRF-safe transport (public hosts only, size and time capped); the image must be a real JPEG, PNG or WebP.
 */

const GOOGLE = "https://news.google.com";
const PAGE_MAX = 1_700_000; // the signature sits at the end of a page of about 1.1 MB
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36";
export const IMAGE_MAX_BYTES = 600_000;
const IMAGE_ID = /^[a-f0-9]{12}$/;

export type Fetcher = (url: string, init?: { method?: string; body?: string; headers?: Record<string, string>; signal?: AbortSignal }) => Promise<Response>;

export interface StoredImage {
  bytes: Buffer;
  type: "image/jpeg" | "image/png" | "image/webp";
}

/** What a feed item's image is, as far as the pipeline is concerned. Null: this item has none (and none is tried again for a day). */
export type ImageFinder = (googleLink: string) => Promise<StoredImage | null>;

/** JPEG, PNG or WebP by their first bytes, never by what the server claims. */
export function sniffImage(b: Buffer): StoredImage["type"] | null {
  if (b.length > 12 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  if (b.length > 12 && b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "image/png";
  if (b.length > 12 && b.subarray(0, 4).toString("latin1") === "RIFF" && b.subarray(8, 12).toString("latin1") === "WEBP") return "image/webp";
  return null;
}

/** The address of the article behind a Google News RSS link, or null. Only news.google.com is contacted. */
export async function resolveArticleUrl(googleLink: string, fetchImpl: Fetcher = fetch as unknown as Fetcher): Promise<string | null> {
  let id: string;
  try {
    const u = new URL(googleLink);
    if (u.origin !== GOOGLE || !/^\/rss\/articles\/[A-Za-z0-9_-]{20,400}$/.test(u.pathname)) return null;
    id = u.pathname.split("/").pop()!;
  } catch {
    return null;
  }
  const page = await fetchImpl(`${GOOGLE}/articles/${id}?hl=ko&gl=KR&ceid=KR:ko`, { headers: { "user-agent": UA }, signal: AbortSignal.timeout(12_000) });
  if (!page.ok) return null;
  const buf = Buffer.from(await page.arrayBuffer());
  if (buf.length > PAGE_MAX) return null;
  const html = buf.toString("utf8");
  const sg = /data-n-a-sg="([^"]{1,200})"/.exec(html)?.[1];
  const ts = /data-n-a-ts="(\d{1,12})"/.exec(html)?.[1];
  if (!sg || !ts) return null;
  const inner = JSON.stringify(["garturlreq", [["X", "X", ["X", "X"], null, null, 1, 1, "KR:ko", null, 1, null, null, null, null, null, 0, 1], "X", "X", 1, [1, 1, 1], 1, 1, null, 0, 0, null, 0], id, Number(ts), sg]);
  const res = await fetchImpl(`${GOOGLE}/_/DotsSplashUi/data/batchexecute`, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded;charset=UTF-8", "user-agent": UA },
    body: `f.req=${encodeURIComponent(JSON.stringify([[["Fbv4je", inner, null, "generic"]]]))}`, signal: AbortSignal.timeout(12_000),
  });
  if (!res.ok) return null;
  const text = (await res.text()).replace(/^\)\]\}'\s*/, "");
  try {
    const rows = JSON.parse(text.slice(text.indexOf("["))) as unknown[][];
    const row = rows.find((r) => Array.isArray(r) && r[0] === "wrb.fr" && typeof r[2] === "string");
    const parsed = JSON.parse(row![2] as string) as unknown[];
    const url = parsed[1];
    return parsed[0] === "garturlres" && typeof url === "string" && /^https:\/\//.test(url) ? url : null;
  } catch {
    return null;
  }
}

/** Images that are a site's stand-in for "no picture": not worth showing in place of the outlet's tile. */
const GENERIC = /default|placeholder|no[-_]?image|noimg|blank|logo|favicon|sprite|common\/|\/ci\//i;

/** The article image named in the page head, as an absolute https address, or null. */
export function findImageUrl(html: string, base: string): string | null {
  const head = html.slice(0, 400_000);
  const tagsOf = head.match(/<meta\b[^>]*>/gi) ?? [];
  let found: string | null = null;
  for (const t of tagsOf) {
    const key = /(?:property|name)\s*=\s*["'](og:image(?::secure_url)?|twitter:image(?::src)?)["']/i.exec(t)?.[1]?.toLowerCase();
    const content = /content\s*=\s*"([^"]+)"|content\s*=\s*'([^']+)'/i.exec(t);
    if (!key || !content) continue;
    if (found === null || key.startsWith("og:image")) found = (content[1] ?? content[2])!.replace(/&amp;/g, "&").trim();
    if (key.startsWith("og:image")) break;
  }
  if (!found) return null;
  try {
    const u = new URL(found, base);
    return u.protocol === "https:" && !GENERIC.test(u.pathname) ? u.toString() : null;
  } catch {
    return null;
  }
}

/** The whole pipeline for one Google News link. Never throws: any failure is "no image". */
export function articleImageFinder(fetchGoogle: Fetcher = fetch as unknown as Fetcher): ImageFinder {
  return async (googleLink) => {
    try {
      const article = await resolveArticleUrl(googleLink, fetchGoogle);
      if (!article) return null;
      const page = await safeFetch(article, { timeoutMs: 8_000, maxBytes: 400_000 });
      if (page.status !== 200 || !/html/i.test(page.contentType)) return null;
      const imageUrl = findImageUrl(page.text, article);
      if (!imageUrl) return null;
      const img = await safeFetch(imageUrl, { timeoutMs: 8_000, maxBytes: IMAGE_MAX_BYTES });
      if (img.status !== 200 || img.truncated) return null;
      const bytes = Buffer.from(img.bytes);
      const type = sniffImage(bytes);
      return type && bytes.length >= 2_000 ? { bytes, type } : null;
    } catch {
      return null;
    }
  };
}

const EXT: Record<StoredImage["type"], string> = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" };
const TYPE: Record<string, StoredImage["type"]> = { jpg: "image/jpeg", png: "image/png", webp: "image/webp" };

/** The copies kept in one folder, named by the item id. */
export class ImageStore {
  readonly dir: string;
  constructor(dir: string) {
    this.dir = dir;
  }

  has(id: string): boolean {
    return this.read(id) !== null;
  }

  put(id: string, img: StoredImage): void {
    if (!IMAGE_ID.test(id)) throw new Error("bad image id");
    mkdirSync(this.dir, { recursive: true });
    const file = join(this.dir, `${id}.${EXT[img.type]}`);
    writeFileSync(`${file}.tmp`, img.bytes);
    renameSync(`${file}.tmp`, file);
  }

  read(id: string): StoredImage | null {
    if (!IMAGE_ID.test(id) || !existsSync(this.dir)) return null;
    for (const ext of Object.keys(TYPE)) {
      const file = join(this.dir, `${id}.${ext}`);
      if (existsSync(file)) {
        try {
          const bytes = readFileSync(file);
          const type = sniffImage(bytes);
          return type ? { bytes, type } : null;
        } catch {
          return null;
        }
      }
    }
    return null;
  }

  /** Deletes every copy whose id is not in [keep]. */
  prune(keep: Set<string>): void {
    if (!existsSync(this.dir)) return;
    for (const name of readdirSync(this.dir)) {
      const id = name.split(".")[0] ?? "";
      if (!keep.has(id)) rmSync(join(this.dir, name), { force: true });
    }
  }
}
