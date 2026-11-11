import { lookup as dnsLookup } from "node:dns/promises";
import { request as httpRequest, type IncomingHttpHeaders } from "node:http";
import { request as httpsRequest } from "node:https";
import { isIP } from "node:net";

function v4Private(a: number, b: number, c: number): boolean {
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) || // CGNAT
    (a === 169 && b === 254) || // link-local, cloud metadata
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 192 && b === 0 && (c === 0 || c === 2)) || // IETF protocol + TEST-NET-1
    (a === 198 && (b === 18 || b === 19)) || // benchmarking
    (a === 198 && b === 51 && c === 100) || // TEST-NET-2
    (a === 203 && b === 0 && c === 113) || // TEST-NET-3
    a >= 224 // multicast, reserved, broadcast
  );
}

/** Parse any textual IPv6 form (compression, zone id, dotted tail) into 16 bytes, or null. */
function ipv6Bytes(input: string): number[] | null {
  let s = input.split("%")[0]!.toLowerCase();
  let tail: number[] = [];
  const dot = s.lastIndexOf(".");
  if (dot !== -1) {
    const colon = s.lastIndexOf(":");
    const parts = s.slice(colon + 1).split(".").map(Number);
    if (parts.length !== 4 || parts.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return null;
    tail = parts;
    s = s.slice(0, colon + 1) + "0:0";
  }
  const halves = s.split("::");
  if (halves.length > 2) return null;
  const groups = (h: string) => (h === "" ? [] : h.split(":"));
  const head = groups(halves[0]!);
  const rest = halves.length === 2 ? groups(halves[1]!) : [];
  const missing = 8 - head.length - rest.length;
  if ((halves.length === 1 && missing !== 0) || missing < 0 || (halves.length === 2 && missing < 1)) return null;
  const all = [...head, ...Array<string>(halves.length === 2 ? missing : 0).fill("0"), ...rest];
  if (all.length !== 8) return null;
  const bytes: number[] = [];
  for (const g of all) {
    if (!/^[0-9a-f]{1,4}$/.test(g)) return null;
    const n = parseInt(g, 16);
    bytes.push(n >> 8, n & 255);
  }
  if (tail.length === 4) bytes.splice(12, 4, ...tail);
  return bytes;
}

/**
 * True for anything that must never be reachable from an agent-initiated request: loopback, private,
 * link-local, metadata, CGNAT, multicast, documentation, transition ranges that embed a private IPv4
 * (IPv4-mapped, NAT64, 6to4), and everything outside global unicast for IPv6. Unparseable input is unsafe.
 */
export function isPrivateAddress(ip: string): boolean {
  const v = isIP(ip.split("%")[0]!);
  if (v === 4) {
    const [a = 0, b = 0, c = 0] = ip.split(".").map(Number);
    return v4Private(a, b, c);
  }
  if (v !== 6) return true;
  const x = ipv6Bytes(ip);
  if (!x) return true;
  const embedded = (i: number) => v4Private(x[i]!, x[i + 1]!, x[i + 2]!);
  if (x.slice(0, 12).every((n) => n === 0)) return true; // ::, ::1, deprecated IPv4-compatible
  if (x.slice(0, 10).every((n) => n === 0) && x[10] === 0xff && x[11] === 0xff) return embedded(12); // ::ffff:a.b.c.d
  if (x[0] === 0x00 && x[1] === 0x64 && x[2] === 0xff && x[3] === 0x9b) {
    // 64:ff9b::/96 NAT64 embeds an IPv4; 64:ff9b:1::/48 local-use NAT64 is blocked outright.
    if (x[4] === 0 && x[5] === 0 && x[6] === 0 && x[7] === 0 && x.slice(8, 12).every((n) => n === 0)) return embedded(12);
    return true;
  }
  if (x[0] === 0x20 && x[1] === 0x02) return embedded(2); // 6to4 2002:AABB:CCDD::/48
  if ((x[0]! & 0xe0) !== 0x20) return true; // only 2000::/3 (global unicast) is allowed at all
  if (x[0] === 0x20 && x[1] === 0x01 && ((x[2] === 0x0d && x[3] === 0xb8) || (x[2] === 0 && x[3] === 0))) return true; // documentation, Teredo
  return false;
}

/** Static check of a URL string before any network access. */
export function checkUrl(raw: unknown): { url: URL } | { violation: string } {
  if (typeof raw !== "string") return { violation: "url must be a string" };
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return { violation: "invalid url" };
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return { violation: `scheme not allowed: ${url.protocol}` };
  if (url.username || url.password) return { violation: "credentials in url are not allowed" };
  const host = url.hostname.replace(/^\[|\]$/g, "");
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
    return { violation: "private/local host not allowed" };
  }
  if (isIP(host.split("%")[0]!) && isPrivateAddress(host)) return { violation: "private IP address not allowed" };
  return { url };
}

export type Resolver = (hostname: string) => Promise<{ address: string; family: number }[]>;
const defaultResolver: Resolver = (h) => dnsLookup(h, { all: true });

export interface SafeFetchOptions {
  method?: "GET" | "POST";
  body?: string;
  timeoutMs?: number;
  maxBytes?: number;
  /** Test seam: when set, requests go through this fetch instead of the pinned-DNS transport. */
  fetchImpl?: typeof fetch;
  /** Test seam: replaces DNS resolution in the pinned transport. */
  resolver?: Resolver;
  /** Only meaningful together with `fetchImpl` (skips the pre-flight DNS check). */
  skipDns?: boolean;
  /** TEST ONLY: lets the pinned transport reach loopback so size/time caps can be exercised on real sockets. Tools never set this. */
  testAllowPrivate?: boolean;
}

interface Raw {
  status: number;
  headers: IncomingHttpHeaders | Headers;
  text: string;
  /** Raw body (capped at maxBytes), for callers that fetch binary data such as icons. */
  bytes: Uint8Array;
  truncated: boolean;
}

function contentType(h: IncomingHttpHeaders | Headers): string {
  const v = h instanceof Headers ? h.get("content-type") : h["content-type"];
  return typeof v === "string" ? v.split(";")[0]!.trim().toLowerCase() : "";
}

/**
 * Transport that validates every resolved address AT CONNECT TIME (the `lookup` hook), so a hostname
 * cannot pass a pre-check and then resolve to a private address when the socket is opened (DNS rebinding).
 */
function pinnedRequest(url: URL, o: Required<Pick<SafeFetchOptions, "timeoutMs" | "maxBytes">> & SafeFetchOptions): Promise<Raw> {
  const resolver = o.resolver ?? defaultResolver;
  const lookup = (
    hostname: string,
    options: { all?: boolean },
    cb: (err: Error | null, address?: string | { address: string; family: number }[], family?: number) => void,
  ) => {
    resolver(hostname).then(
      (addrs) => {
        if (addrs.length === 0) return cb(new Error("host did not resolve"));
        for (const a of addrs) if (!o.testAllowPrivate && isPrivateAddress(a.address)) return cb(new Error("host resolves to a private address"));
        if (options.all) cb(null, addrs);
        else cb(null, addrs[0]!.address, addrs[0]!.family);
      },
      (e: Error) => cb(e),
    );
  };
  const doRequest = url.protocol === "https:" ? httpsRequest : httpRequest;
  return new Promise<Raw>((resolve, reject) => {
    let settled = false;
    const finish = (fn: () => void) => {
      if (settled) return;
      settled = true;
      fn();
    };
    const req = doRequest(
      url,
      {
        method: o.method ?? "GET",
        lookup: lookup as never,
        timeout: o.timeoutMs, // idle timeout only; the wall-clock cap is the abort signal below
        signal: AbortSignal.timeout(o.timeoutMs), // total time, so a slow-drip response cannot hold the task forever
        headers: { "user-agent": "aeon-agent", ...(o.body !== undefined ? { "content-type": "text/plain; charset=utf-8", "content-length": Buffer.byteLength(o.body) } : {}) },
      },
      (res) => {
        const chunks: Buffer[] = [];
        let size = 0;
        let truncated = false;
        const done = () =>
          finish(() => {
            const bytes = Buffer.concat(chunks);
            resolve({ status: res.statusCode ?? 0, headers: res.headers, text: bytes.toString("utf8"), bytes, truncated });
          });
        res.on("data", (c: Buffer) => {
          const room = o.maxBytes - size;
          chunks.push(c.length > room ? c.subarray(0, room) : c);
          size += Math.min(c.length, room);
          if (c.length >= room) {
            truncated = true;
            done();
            res.destroy(); // stop downloading once the cap is reached
          }
        });
        res.on("end", done);
        res.on("error", (e) => finish(() => reject(e)));
      },
    );
    req.on("timeout", () => req.destroy(new Error("request timed out")));
    req.on("error", (e) => finish(() => reject(e)));
    req.end(o.body);
  });
}

function location(h: IncomingHttpHeaders | Headers): string | null {
  if (h instanceof Headers) return h.get("location");
  const l = h["location"];
  return typeof l === "string" ? l : null;
}

/**
 * fetch with SSRF protection: validates every hop, pins DNS at connect time, caps size and time.
 * Redirects are followed for GET only. A redirected POST is NOT replayed (its body would reach a host
 * the owner never approved); the 3xx response is returned instead.
 */
export async function safeFetch(
  rawUrl: string,
  opts: SafeFetchOptions = {},
): Promise<{ status: number; text: string; truncated: boolean; bytes: Uint8Array; contentType: string }> {
  const timeoutMs = opts.timeoutMs ?? 20_000;
  const maxBytes = opts.maxBytes ?? 1_000_000;
  const method = opts.method ?? "GET";
  let current = rawUrl;
  for (let hop = 0; hop < 4; hop++) {
    const chk = checkUrl(current);
    if ("violation" in chk) throw new Error(chk.violation);
    let raw: Raw;
    if (opts.fetchImpl) {
      if (!opts.skipDns) await assertPublicHost(chk.url.hostname, opts.resolver);
      const res = await opts.fetchImpl(chk.url, {
        method,
        ...(opts.body !== undefined ? { body: opts.body } : {}),
        redirect: "manual",
        signal: AbortSignal.timeout(timeoutMs),
      });
      const buf = new Uint8Array(await res.arrayBuffer());
      const truncated = buf.length > maxBytes;
      const bytes = truncated ? buf.slice(0, maxBytes) : buf;
      raw = { status: res.status, headers: res.headers, text: new TextDecoder().decode(bytes), bytes, truncated };
    } else {
      raw = await pinnedRequest(chk.url, { ...opts, timeoutMs, maxBytes });
    }
    const loc = location(raw.headers);
    if (raw.status >= 300 && raw.status < 400 && loc) {
      if (method !== "GET") return { status: raw.status, text: `redirect to ${loc} not followed for ${method}`, truncated: false, bytes: new Uint8Array(), contentType: "" };
      current = new URL(loc, chk.url).toString();
      continue;
    }
    return { status: raw.status, text: raw.text, truncated: raw.truncated, bytes: raw.bytes, contentType: contentType(raw.headers) };
  }
  throw new Error("too many redirects");
}

/** Pre-flight DNS check (defence in depth; the real guard is the connect-time lookup above). */
export async function assertPublicHost(hostname: string, resolver: Resolver = defaultResolver): Promise<void> {
  const host = hostname.replace(/^\[|\]$/g, "");
  if (isIP(host.split("%")[0]!)) {
    if (isPrivateAddress(host)) throw new Error("private IP address not allowed");
    return;
  }
  const addrs = await resolver(host);
  if (addrs.length === 0) throw new Error("host did not resolve");
  for (const a of addrs) if (isPrivateAddress(a.address)) throw new Error("host resolves to a private address");
}
