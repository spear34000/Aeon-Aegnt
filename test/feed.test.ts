import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { decodeHtml, dice, extractArticleText, longestSharedRun, numbersIn } from "../src/node/feed-text.ts";
import { looksLikeBait, parseRss, pick, queryFor, type FeedFetch } from "../src/node/feed.ts";
import { findImageUrl, ImageStore, resolveArticleUrl, sniffImage, type Fetcher } from "../src/node/feed-images.ts";
import { GoogleNewsAdapter } from "../src/node/feed-sources.ts";
import { tempDir } from "./helpers.ts";

const NOW = Date.parse("2026-11-13T09:00:00Z");
const hoursAgo = (h: number) => new Date(NOW - h * 3_600_000).toUTCString();

const item = (title: string, source: string, h: number, link = `https://news.google.com/rss/articles/${encodeURIComponent(title)}`) =>
  `<item><title><![CDATA[${title} - ${source}]]></title><link>${link}</link><pubDate>${hoursAgo(h)}</pubDate><source url="https://example.com">${source}</source></item>`;

const rss = (...items: string[]) => `<?xml version="1.0"?><rss version="2.0"><channel><title>x</title>${items.join("")}</channel></rss>`;

describe("reading headlines", () => {
  it("a headline is shown as text: nested or broken tags leave no markup behind", async () => {
    const { plainText } = await import("../src/node/feed.ts");
    assert.equal(plainText("<scr<b>ipt>alert(1)</scr</b>ipt> 진짜 제목입니다"), "iptalert(1)ipt 진짜 제목입니다", "what looked like nested tags is taken out as one tag and no bracket is left");
    assert.ok(!/[<>]/.test(plainText("<<script>script>alert(1)<</script>/script> x 제목")), "tags that appear only after the first pass are taken out too");
    assert.equal(plainText("5 > 3 이라는 소식 제목"), "5 3 이라는 소식 제목", "a lone bracket is dropped, not kept");
    assert.equal(plainText("  <b>굵은</b>   제목  "), "굵은 제목");
    const items = parseRss(rss(`<item><title>&lt;scr&lt;b&gt;ipt&gt;x&lt;/scr&lt;/b&gt;ipt&gt; 헤드라인 본문 - 출처</title><link>https://x.example/a</link><pubDate>${hoursAgo(1)}</pubDate><source url="https://m.example">출처</source></item>`));
    assert.ok(items.length === 1 && !/[<>]/.test(items[0]!.title), JSON.stringify(items[0]?.title));
  });

  it("reads RSS items, decodes entities, strips the outlet from the title, and leaves out what is not a plain https link", () => {
    const xml = rss(
      item("삼성 &amp; LG, 2분기 영업이익 1.2조 발표", "연합뉴스", 2),
      `<item><title>no link here</title><pubDate>${hoursAgo(1)}</pubDate></item>`,
      `<item><title>javascript link</title><link>javascript:alert(1)</link><pubDate>${hoursAgo(1)}</pubDate></item>`,
      `<item><title>no time</title><link>https://x.example/a</link></item>`,
    );
    assert.equal(parseRss(rss(`<item><title>머니 제목 - 머니투데이 - 머니투데이</title><link>https://x.example/a</link><pubDate>${hoursAgo(1)}</pubDate><source url="https://m.example">머니투데이</source></item>`))[0]!.title, "머니 제목", "the outlet is taken off the end even when it is there twice");
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

  it("the google news adapter contacts only that host, searches an interest by its keywords or as typed, and says when the source fails", async () => {
    const asked: string[] = [];
    const ok: FeedFetch = async (url) => (asked.push(url), new Response(rss(item("반도체 수출 두 달 연속 증가", "E경제", 1), item("정부, 내년 예산안 5% 증액 확정", "A일보", 2)), { status: 200 }));
    const a = new GoogleNewsAdapter({ fetchImpl: ok });
    const r = await a.search("AI", NOW);
    await a.search("내가 쓴 관심사", NOW);
    assert.ok(asked.every((u) => u.startsWith("https://news.google.com/rss/search?q=")));
    assert.match(decodeURIComponent(asked[0]!), /인공지능/);
    assert.match(decodeURIComponent(asked[1]!), /내가 쓴 관심사/);
    assert.deepEqual(r.items.map((c) => [c.interest, c.title, c.id.length]), [["AI", "반도체 수출 두 달 연속 증가", 12], ["AI", "정부, 내년 예산안 5% 증액 확정", 12]]);
    assert.match((await new GoogleNewsAdapter({ fetchImpl: async () => new Response("", { status: 503 }) }).search("여행", NOW)).error ?? "", /503/);
    assert.match((await new GoogleNewsAdapter({ fetchImpl: async () => { throw new Error("offline"); } }).search("여행", NOW)).error ?? "", /닿지 못했어요/);
    assert.equal(queryFor("내 취미"), "내 취미", "a typed interest is searched as written");
  });
});

describe("text rules the reviewer relies on", () => {
  it("reads a page in the character set it declares, and gives nothing rather than garbage", () => {
    const euckr = Buffer.from([0xc7, 0xd1, 0xb1, 0xdb, 0x20, 0xb1, 0xe2, 0xbb, 0xe7]); // 한글 기사
    assert.equal(decodeHtml(euckr, "text/html; charset=euc-kr"), "한글 기사");
    assert.equal(decodeHtml(Buffer.concat([Buffer.from('<meta charset="euc-kr">'), euckr]), "text/html"), '<meta charset="euc-kr">한글 기사', "the meta tag is used when the header says nothing");
    assert.equal(decodeHtml(Buffer.from("한글 기사", "utf8"), "text/html"), "한글 기사");
    assert.equal(decodeHtml(Buffer.from("가".repeat(200) + "�".repeat(40) + "다".repeat(10), "utf8"), "text/html"), null, "mostly broken text is not passed on");
    assert.equal(decodeHtml(euckr, "text/html; charset=not-a-charset"), null, "an unknown character set on a non-UTF-8 page is not guessed");
  });

  it("takes the description and the first paragraphs of an article, and leaves scripts, menus and copyright lines out", () => {
    const html = `<html><head><meta property="og:description" content="한국은행이 기준금리를 연 3.0%로 동결했다고 밝혔습니다."><script>var a = '<p>script text that is long enough to count</p>';</script></head>
      <body><nav><p>메뉴 메뉴 메뉴 메뉴 메뉴 메뉴 메뉴 메뉴 메뉴 메뉴 메뉴</p></nav><article><p>금융통화위원회는 이날 회의를 열고 물가 상승률이 목표 수준에 가까워졌다고 평가했습니다.</p>
      <p>Copyright ⓒ 예시일보 무단 전재 및 재배포 금지 이 줄은 저작권 안내입니다 길이를 채웁니다</p><p>짧음</p></article></body></html>`;
    const text = extractArticleText(html);
    assert.match(text, /^한국은행이 기준금리를 연 3\.0%로 동결/);
    assert.match(text, /금융통화위원회는 이날 회의를/);
    assert.ok(!/script text|메뉴|Copyright|짧음/.test(text));
    assert.equal(extractArticleText("<html><body>nothing here</body></html>"), "");
    assert.ok(extractArticleText(`<p>${"가".repeat(5000)}</p>`).length <= 2000);
  });

  it("compares numbers in one way, pairs of characters for similarity, and finds long stretches taken over from a source", () => {
    assert.deepEqual([...numbersIn("1,200명 중 3.0% 그리고 03월 12일")].sort(), ["1200", "12", "3", "3"].sort().filter((v, i, a) => a.indexOf(v) === i));
    assert.ok(dice("한국은행 기준금리 연 3.0% 동결", "한국은행, 기준금리 연 3.0% 동결!") > 0.9);
    assert.ok(dice("한국은행 기준금리 동결", "항공권 평균 가격 하락") < 0.2);
    assert.equal(dice("", "가나"), 0);
    const src = "금융통화위원회는 이날 회의를 열고 물가 상승률이 목표 수준에 가까워졌다고 평가했으며 기준금리를 유지하기로 했다";
    assert.ok(longestSharedRun("위원회는 이날 회의를 열고 물가 상승률이 목표 수준에 가까워졌다고 평가했다", src).words >= 8);
    const own = longestSharedRun("한국은행이 금리를 그대로 두었습니다. 물가가 안정되고 있다는 판단입니다.", src);
    assert.ok(own.words < 4 && own.chars < 20, JSON.stringify(own));
  });
});

// ---- article images ----

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

describe("article pages and images", () => {
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
  });

  it("keeps copies by post id, serves only what it kept, and deletes what the feed no longer shows", () => {
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
});
