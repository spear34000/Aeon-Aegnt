import assert from "node:assert/strict";
import { join } from "node:path";
import { describe, it } from "node:test";
import { FeedService, looksLikeBait, parseRss, pick, rewriteIsFaithful, type FeedChat, type FeedFetch } from "../src/node/feed.ts";
import { tempDir } from "./helpers.ts";

const NOW = Date.parse("2026-11-13T09:00:00Z");
const hoursAgo = (h: number) => new Date(NOW - h * 3_600_000).toUTCString();

const item = (title: string, source: string, h: number, link = `https://news.google.com/rss/articles/${encodeURIComponent(title)}`) =>
  `<item><title><![CDATA[${title} - ${source}]]></title><link>${link}</link><pubDate>${hoursAgo(h)}</pubDate><source url="https://example.com">${source}</source></item>`;

const rss = (...items: string[]) => `<?xml version="1.0"?><rss version="2.0"><channel><title>x</title>${items.join("")}</channel></rss>`;

/** A fetch that answers every search with the same feed and remembers what was asked. */
function fakeNews(body: string, asked: string[] = []): FeedFetch {
  return async (url) => {
    asked.push(url);
    return new Response(body, { status: 200, headers: { "content-type": "application/rss+xml" } });
  };
}

describe("reading headlines", () => {
  it("reads RSS items, decodes entities, strips the outlet from the title, and leaves out what is not a plain https link", () => {
    const xml = rss(
      item("삼성 &amp; LG, 2분기 영업이익 1.2조 발표", "연합뉴스", 2),
      `<item><title>no link here</title><pubDate>${hoursAgo(1)}</pubDate></item>`,
      `<item><title>javascript link</title><link>javascript:alert(1)</link><pubDate>${hoursAgo(1)}</pubDate></item>`,
      `<item><title>no time</title><link>https://x.example/a</link></item>`,
    );
    const items = parseRss(xml);
    assert.equal(items.length, 1);
    assert.deepEqual([items[0]!.title, items[0]!.source], ["삼성 & LG, 2분기 영업이익 1.2조 발표", "연합뉴스"]);
  });

  it("drops old items, clickbait and the same story from another outlet; newest first", () => {
    const raw = parseRss(rss(
      item("정부, 내년 예산안 5% 증액 확정", "A일보", 5),
      item("정부 내년 예산안 5% 증액 확정", "B신문", 4),
      item("충격!! 알고 보니 이게 원인이었다", "C뉴스", 3),
      item("오래된 소식: 지난주 발표된 정책", "D", 24 * 5),
      item("반도체 수출 두 달 연속 증가", "E경제", 1),
    ));
    const out = pick(raw, NOW);
    assert.deepEqual(out.map((i) => i.title), ["반도체 수출 두 달 연속 증가", "정부 내년 예산안 5% 증액 확정"]);
    for (const bad of ["이럴 수가!!", "알고 보니 이게 문제였다", "소름 돋는 결과", "지금 당장 확인하세요", "결국 터졌다"]) assert.ok(looksLikeBait(bad), bad);
    for (const ok of ["한국은행, 기준금리 연 3.0% 동결", "새 노트북 출시 예정"]) assert.ok(!looksLikeBait(ok), ok);
  });

  it("keeps a rewrite only if it invents nothing checkable", () => {
    const orig = "한국은행, 기준금리 연 3.0% 동결…시장 예상 부합";
    assert.ok(rewriteIsFaithful(orig, "한국은행이 기준금리를 연 3.0%로 동결"));
    assert.ok(!rewriteIsFaithful(orig, "한국은행이 기준금리를 연 3.5%로 동결"), "a number that was not in the headline");
    assert.ok(!rewriteIsFaithful(orig, "기준금리가 동결됐을까?"), "a question");
    assert.ok(!rewriteIsFaithful(orig, "기준금리 동결, 충격!"), "a shout");
    assert.ok(!rewriteIsFaithful(orig, "SKIP"));
    assert.ok(!rewriteIsFaithful(orig, "x".repeat(200)));
  });
});

describe("the feed", () => {
  const body = rss(item("정부, 내년 예산안 5% 증액 확정", "A일보", 5), item("반도체 수출 두 달 연속 증가", "E경제", 1), item("이럴 수가!! 대박 사건", "Z", 2));

  it("fetches each interest from Google News only, filters, and keeps the originals when no model may be used", async () => {
    const t = tempDir();
    const asked: string[] = [];
    const feed = new FeedService({ file: join(t.dir, "feed.json"), fetchImpl: fakeNews(body, asked), now: () => NOW });
    const d = await feed.refresh(["AI", "내가 쓴 관심사"]);
    assert.ok(asked.every((u) => u.startsWith("https://news.google.com/rss/search?q=")), "only that host is contacted");
    assert.match(decodeURIComponent(asked[0]!), /인공지능/, "a known interest is searched by its news keywords");
    assert.match(decodeURIComponent(asked[1]!), /내가 쓴 관심사/, "an interest the owner typed is searched as written");
    assert.deepEqual([d.mode, d.sections.length, d.sections[0]!.items.map((i) => i.title)], ["rules", 2, ["반도체 수출 두 달 연속 증가", "정부, 내년 예산안 5% 증액 확정"]]);
    assert.match(d.modeNote ?? "", /원문 제목/);
    assert.deepEqual(feed.get()?.interests, ["AI", "내가 쓴 관심사"], "what was made is kept");
    assert.equal(feed.stale(["AI", "내가 쓴 관심사"]), false);
    assert.equal(feed.stale(["AI"]), true, "other interests make it stale");
    t.cleanup();
  });

  it("rewrites with a model but never trusts it: faithful rewrites are shown, the rest keep the original; instructions in headlines are data", async () => {
    const t = tempDir();
    let seen = "";
    const chat: FeedChat = async (_system, user) => {
      seen = user;
      const ids = (JSON.parse(user) as { headlines: { id: string; title: string }[] }).headlines;
      return "Here you go: " + JSON.stringify({
        items: [
          { id: ids[0]!.id, title: "반도체 수출이 두 달 연속 늘었다" },
          { id: ids[1]!.id, title: "내년 예산안이 7% 늘었다" },
          { id: "not-an-id", title: "ignored" },
        ],
      });
    };
    const feed = new FeedService({ file: join(t.dir, "feed.json"), fetchImpl: fakeNews(body), now: () => NOW });
    const d = await feed.refresh(["AI"], chat);
    const [a, b] = d.sections[0]!.items;
    assert.equal(d.mode, "model");
    assert.deepEqual([a!.title, a!.rewritten, a!.original], ["반도체 수출이 두 달 연속 늘었다", true, "반도체 수출 두 달 연속 증가"]);
    assert.deepEqual([b!.title, b!.rewritten], ["정부, 내년 예산안 5% 증액 확정", false], "an invented number is refused");
    assert.ok(!seen.includes("A일보") && !seen.includes("news.google.com"), "the model sees only the headlines, not sources or links");
    t.cleanup();
  });

  it("a model that fails or answers nonsense leaves the headlines as they were; two refreshes at once make one request", async () => {
    const t = tempDir();
    const asked: string[] = [];
    const feed = new FeedService({ file: join(t.dir, "feed.json"), fetchImpl: fakeNews(body, asked), now: () => NOW });
    const broken = await feed.refresh(["AI"], async () => {
      throw new Error("no model");
    });
    assert.deepEqual([broken.mode, broken.sections[0]!.items.every((i) => !i.rewritten)], ["rules", true]);
    const nonsense = await feed.refresh(["AI"], async () => "not json at all");
    assert.equal(nonsense.sections[0]!.items.every((i) => !i.rewritten), true);
    asked.length = 0;
    const [x, y] = await Promise.all([feed.refresh(["AI"]), feed.refresh(["AI"])]);
    assert.equal(asked.length, 1, "one fetch for both callers");
    assert.equal(x, y);
    t.cleanup();
  });

  it("says why an interest is empty when the source fails or nothing recent passes, and survives a damaged cache", async () => {
    const t = tempDir();
    const down: FeedFetch = async () => new Response("", { status: 503 });
    const feed = new FeedService({ file: join(t.dir, "feed.json"), fetchImpl: down, now: () => NOW });
    const d = await feed.refresh(["여행"]);
    assert.match(d.sections[0]!.note ?? "", /503/);
    const quiet = new FeedService({ file: join(t.dir, "q.json"), fetchImpl: fakeNews(rss(item("아주 오래된 소식", "A", 24 * 9))), now: () => NOW });
    assert.match((await quiet.refresh(["여행"])).sections[0]!.note ?? "", /새 소식이 없어요/);
    const { writeFileSync } = await import("node:fs");
    writeFileSync(join(t.dir, "feed.json"), "{broken");
    assert.equal(feed.get(), null);
    t.cleanup();
  });
});

// ---- article images ----

import { articleImageFinder, findImageUrl, ImageStore, resolveArticleUrl, sniffImage, type Fetcher, type ImageFinder, type StoredImage } from "../src/node/feed-images.ts";
import { existsSync } from "node:fs";

const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(3000, 7)]);
const NLN = String.fromCharCode(10);

function googleFetch(target: string | null, asked: string[] = []): Fetcher {
  return async (url, init) => {
    asked.push(`${init?.method ?? "GET"} ${url}`);
    if (url.includes("/articles/")) return new Response(`<html><c-wiz data-n-a-sg="SIG" data-n-a-ts="1700000000"></c-wiz></html>`, { status: 200 });
    const row = ["wrb.fr", "Fbv4je", JSON.stringify(["garturlres", target, 1]), null, null, null, "generic"];
    return new Response(`)]}'${NLN}${NLN}${JSON.stringify([row, ["di", 14]])}`, { status: 200 });
  };
}

describe("article images", () => {
  it("knows an image by its first bytes, not by what a server says", () => {
    assert.equal(sniffImage(JPEG), "image/jpeg");
    assert.equal(sniffImage(Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(20)])), "image/png");
    assert.equal(sniffImage(Buffer.concat([Buffer.from("RIFF"), Buffer.alloc(4), Buffer.from("WEBP"), Buffer.alloc(8)])), "image/webp");
    for (const bad of ["<svg xmlns='http://www.w3.org/2000/svg'></svg>", "<html>not an image</html>", "GIF89a......."]) assert.equal(sniffImage(Buffer.from(bad)), null, bad);
  });

  it("finds the article image in the page head: og:image first, https only, a stand-in picture is not an image", () => {
    const page = (meta: string) => `<html><head><title>x</title>${meta}</head><body></body></html>`;
    assert.equal(findImageUrl(page(`<meta name="twitter:image" content="https://cdn.example/t.jpg"><meta property="og:image" content="https://cdn.example/o.jpg?a=1&amp;b=2">`), "https://news.example/a"), "https://cdn.example/o.jpg?a=1&b=2");
    assert.equal(findImageUrl(page(`<meta content="/img/a.jpg" property="og:image">`), "https://news.example/a/b"), "https://news.example/img/a.jpg", "a relative address is made absolute");
    assert.equal(findImageUrl(page(`<meta property="og:image" content="http://cdn.example/a.jpg">`), "https://news.example/a"), null, "plain http is not used");
    assert.equal(findImageUrl(page(`<meta property="og:image" content="https://cdn.example/images/default_img.jpg">`), "https://news.example/a"), null, "a site's stand-in picture says nothing about the article");
    assert.equal(findImageUrl(page(`<meta property="og:image" content="javascript:alert(1)">`), "https://news.example/a"), null);
    assert.equal(findImageUrl(page(""), "https://news.example/a"), null);
  });

  it("asks Google only for news.google.com links and reads the article address out of the reply", async () => {
    const asked: string[] = [];
    const id = "CBMi" + "A".repeat(60);
    assert.equal(await resolveArticleUrl(`https://news.google.com/rss/articles/${id}?oc=5`, googleFetch("https://pub.example/article/1", asked)), "https://pub.example/article/1");
    assert.deepEqual(asked.map((a) => a.split(" ")[0]), ["GET", "POST"]);
    for (const bad of ["https://evil.example/rss/articles/" + id, "http://news.google.com/rss/articles/" + id, "https://news.google.com/other/" + id, "https://news.google.com/rss/articles/short", "not a url"]) {
      const none: string[] = [];
      assert.equal(await resolveArticleUrl(bad, googleFetch("https://pub.example/x", none)), null, bad);
      assert.equal(none.length, 0, "nothing was asked for " + bad);
    }
    assert.equal(await resolveArticleUrl(`https://news.google.com/rss/articles/${id}`, googleFetch("http://insecure.example/x")), null, "an http article address is refused");
    assert.equal(await resolveArticleUrl(`https://news.google.com/rss/articles/${id}`, async () => new Response("", { status: 500 })), null);
    assert.equal(await articleImageFinder(googleFetch(null))(`https://news.google.com/rss/articles/${id}`), null, "any failure is simply no image");
  });

  it("keeps copies by item id, serves only what it kept, and deletes what the feed no longer shows", () => {
    const t = tempDir();
    const store = new ImageStore(join(t.dir, "img"));
    store.put("aaaaaaaaaaaa", { bytes: JPEG, type: "image/jpeg" });
    store.put("bbbbbbbbbbbb", { bytes: JPEG, type: "image/jpeg" });
    assert.deepEqual([store.has("aaaaaaaaaaaa"), store.read("aaaaaaaaaaaa")?.type, store.has("cccccccccccc")], [true, "image/jpeg", false]);
    for (const bad of ["../../etc/passwd", "AAAAAAAAAAAA", "aaaa", "aaaaaaaaaaaa.jpg"]) assert.equal(store.read(bad), null, bad);
    assert.throws(() => store.put("../x", { bytes: JPEG, type: "image/jpeg" }), /bad image id/);
    store.prune(new Set(["aaaaaaaaaaaa"]));
    assert.deepEqual([existsSync(join(t.dir, "img", "aaaaaaaaaaaa.jpg")), existsSync(join(t.dir, "img", "bbbbbbbbbbbb.jpg"))], [true, false]);
    t.cleanup();
  });

  const body = rss(
    item("반도체 수출 두 달 연속 증가", "E경제", 1), item("정부, 내년 예산안 5% 증액 확정", "A일보", 2), item("신약 임상 3상 통과 소식", "B신문", 3),
  );

  it("looks for an image for the first items of each interest only, keeps it, and does not look again for what it has or what failed", async () => {
    const t = tempDir();
    const calls: string[] = [];
    const finder: ImageFinder = async (link) => {
      calls.push(link);
      return link.includes(encodeURIComponent("신약")) ? null : ({ bytes: JPEG, type: "image/jpeg" } satisfies StoredImage);
    };
    const feed = new FeedService({ file: join(t.dir, "feed.json"), fetchImpl: fakeNews(body), now: () => NOW, images: { store: new ImageStore(join(t.dir, "img")), finder } });
    const d = await feed.refresh(["AI"]);
    const items = d.sections[0]!.items;
    assert.equal(calls.length, 3, "the first three of the interest");
    assert.deepEqual(items.map((i) => i.image), [true, true, false]);
    assert.ok(feed.image(items[0]!.id), "the copy can be served");
    assert.equal(feed.image("0".repeat(12)), null);
    calls.length = 0;
    const again = await feed.refresh(["AI"]);
    assert.equal(calls.length, 0, "an image that is kept is not fetched again");
    assert.deepEqual(again.sections[0]!.items.map((i) => i.image), [true, true, false]);
    // A failure is remembered for a day.
    const feed2 = new FeedService({ file: join(t.dir, "f2.json"), fetchImpl: fakeNews(rss(item("신약 임상 3상 통과 소식", "B신문", 3))), now: () => NOW, images: { store: new ImageStore(join(t.dir, "img2")), finder } });
    await feed2.refresh(["AI"]);
    calls.length = 0;
    await feed2.refresh(["AI"]);
    assert.equal(calls.length, 0, "no image was found for it: not tried again within a day");
    t.cleanup();
  });

  it("with images switched off nothing is looked for and the kept copies are deleted", async () => {
    const t = tempDir();
    let n = 0;
    const store = new ImageStore(join(t.dir, "img"));
    const feed = new FeedService({ file: join(t.dir, "feed.json"), fetchImpl: fakeNews(body), now: () => NOW, images: { store, finder: async () => (n++, { bytes: JPEG, type: "image/jpeg" }) } });
    const on = await feed.refresh(["AI"]);
    assert.ok(on.sections[0]!.items[0]!.image && n === 3);
    const off = await feed.refresh(["AI"], undefined, { images: false });
    assert.deepEqual([off.sections[0]!.items.some((i) => i.image), n, store.has(on.sections[0]!.items[0]!.id)], [false, 3, false]);
    t.cleanup();
  });
});
