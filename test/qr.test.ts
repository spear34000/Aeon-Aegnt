import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { describe, it } from "node:test";
import { buildPairLink, parsePairLink } from "../src/core/pair-link.ts";
import { encodeQr, qrToAnsi, qrToText, QR_MAX_BYTES, rsDivisor, rsRemainder } from "../src/core/qr.ts";

const sha = (s: string) => createHash("sha256").update(s).digest("hex");

/**
 * Vectors shared with the Kotlin port (ui/.../QrTest.kt). Each matrix was also checked against an independent
 * encoder (python-qrcode, same version and mask: identical modules) and decoded again from a rendered image.
 */
export const QR_VECTORS = [
  { text: "hello", version: 1, mask: 0, sha: "bcb1c127e8a305bb237b297a8e376eeb7ec1504d5766f4dba30ed1e023c74b94" },
  { text: "aeon://pair?u=http%3A%2F%2F192.168.0.12%3A7421&c=75KFXQWOXQGQ7VLQ", version: 5, mask: 6, sha: "7895bddfda0b25e853efd3aa67b8b008b121eb6e2c5cb82d63ff2cfbd0263e34" },
  { text: "aeon://pair?u=http%3A%2F%2F192.168.0.12%3A7421&u=http%3A%2F%2F100.115.170.78%3A7421&c=75KFXQWOXQGQ7VLQ", version: 6, mask: 2, sha: "7736f2758286cb660eb0767ee1789d6e9a38a989e1f1ad05be23cf063a5ba8dd" },
  { text: "한글", version: 1, mask: 6, sha: "8b675c1c5745f0752ef07dd80ea413b93f778b73373085f32aa3bd35507041a1" },
];

describe("QR encoder", () => {
  it("produces the matrices the shared vectors expect", () => {
    for (const v of QR_VECTORS) {
      const q = encodeQr(v.text);
      assert.deepEqual([q.version, q.mask, sha(qrToText(q))], [v.version, v.mask, v.sha], v.text);
    }
  });

  it("Reed-Solomon matches the published worked example (HELLO WORLD, 1-M)", () => {
    const data = [0x20, 0x5b, 0x0b, 0x78, 0xd1, 0x72, 0xdc, 0x4d, 0x43, 0x40, 0xec, 0x11, 0xec, 0x11, 0xec, 0x11];
    assert.deepEqual(rsRemainder(data, rsDivisor(10)), [196, 35, 39, 119, 235, 215, 231, 226, 93, 23]);
  });

  it("has the fixed structure: finder patterns, timing and the dark module, at every version", () => {
    for (const n of [1, 30, 60, 100, 150, 213]) {
      const q = encodeQr("x".repeat(n));
      const s = q.size;
      assert.equal(s, q.version * 4 + 17);
      for (const [cx, cy] of [[0, 0], [s - 7, 0], [0, s - 7]] as const) {
        assert.ok(q.modules[cy]![cx]! && q.modules[cy + 3]![cx + 3]! && !q.modules[cy + 1]![cx + 1]!, "finder pattern");
      }
      assert.ok(q.modules[s - 8]![8]!, "dark module");
      for (let i = 8; i < s - 8; i++) assert.equal(q.modules[6]![i], i % 2 === 0, "timing row");
    }
  });

  it("grows the version with the text and refuses what cannot fit", () => {
    assert.equal(encodeQr("a").version, 1);
    assert.equal(encodeQr("x".repeat(QR_MAX_BYTES)).version, 10);
    assert.throws(() => encodeQr("x".repeat(QR_MAX_BYTES + 1)), /too long/);
  });

  it("renders for a terminal with explicit black and white and a quiet zone", () => {
    const q = encodeQr("hello");
    const text = qrToAnsi(q, 2);
    const rows = text.split("\n");
    assert.equal(rows.length, Math.ceil((q.size + 4) / 2));
    assert.ok(text.includes("▀") && text.includes("\x1b[97;107m"), "white quiet zone, not the terminal's own background");
    assert.ok(text.endsWith("\x1b[0m"));
  });
});

describe("Pairing link", () => {
  it("round-trips addresses and the code", () => {
    const link = buildPairLink(["http://192.168.0.12:7421", "http://100.115.170.78:7421"], "75KF-XQWO-XQGQ-7VLQ");
    assert.equal(link, "aeon://pair?u=http%3A%2F%2F192.168.0.12%3A7421&u=http%3A%2F%2F100.115.170.78%3A7421&c=75KFXQWOXQGQ7VLQ");
    assert.deepEqual(parsePairLink(link), { addresses: ["http://192.168.0.12:7421", "http://100.115.170.78:7421"], code: "75KFXQWOXQGQ7VLQ" });
    assert.ok(encodeQr(link).version <= 6, "fits a small QR code");
  });

  it("keeps at most four addresses and drops anything that is not a plain http(s) origin", () => {
    const link = buildPairLink(["http://a:1", "javascript:alert(1)", "http://u:p@evil:1", "http://b:2", "http://c:3", "http://d:4", "http://e:5", "http://f:6/path"], "75KFXQWOXQGQ7VLQ");
    assert.deepEqual(parsePairLink(link)!.addresses, ["http://a:1", "http://b:2", "http://c:3", "http://d:4"]);
    assert.ok(encodeQr(buildPairLink(["http://192.168.100.200:65535", "http://192.168.100.201:65535", "http://192.168.100.202:65535", "http://192.168.100.203:65535"], "75KFXQWOXQGQ7VLQ")).version <= 10, "four long addresses still fit");
    assert.throws(() => buildPairLink([], "short"));
  });

  it("ignores hostile links: wrong scheme or host, bad code, credentials, junk percent-encoding", () => {
    const code = "75KFXQWOXQGQ7VLQ";
    for (const bad of [`http://pair?c=${code}`, `aeon://other?c=${code}`, "aeon://pair?c=nothex", "aeon://pair?u=http%3A%2F%2Fa%3A1", `aeon://pair?c=${code}%`, ""]) assert.equal(parsePairLink(bad), null, bad);
    assert.deepEqual(parsePairLink(`aeon://pair?u=http%3A%2F%2Fu%3Ap%40evil%3A1&u=file%3A%2F%2F%2Fetc&c=${code}`), { addresses: [], code }, "unsafe addresses are dropped, the code alone is kept");
  });
});
