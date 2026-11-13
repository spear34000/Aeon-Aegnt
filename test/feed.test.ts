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
