import { lookup } from "node:dns/promises";
import { isIP } from "node:net";

/** True for loopback, private, link-local, CGNAT, multicast, and unspecified addresses (SSRF targets). */
export function isPrivateAddress(ip: string): boolean {
  const v = isIP(ip);
  if (v === 4) {
    const [a = 0, b = 0] = ip.split(".").map(Number);
    return (
      a === 0 ||
      a === 10 ||
      a === 127 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 192 && b === 0) ||
      a >= 224
    );
  }
  if (v === 6) {
    const s = ip.toLowerCase();
    if (s === "::" || s === "::1") return true;
    if (s.startsWith("::ffff:")) {
      const tail = s.slice(7);
      if (isIP(tail) === 4) return isPrivateAddress(tail);
      return true; // hex-form mapped addresses: refuse rather than parse
    }
    return s.startsWith("fc") || s.startsWith("fd") || s.startsWith("fe8") || s.startsWith("fe9") || s.startsWith("fea") || s.startsWith("feb") || s.startsWith("ff");
  }
  return true; // not an IP at all: treat as unsafe
}

/** Static check of a URL string before any network access. Returns a violation message or null. */
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
  if (isIP(host) && isPrivateAddress(host)) return { violation: "private IP address not allowed" };
  return { url };
}

/** DNS check at connect time (blocks hostnames that resolve to private addresses). */
export async function assertPublicHost(hostname: string): Promise<void> {
  const host = hostname.replace(/^\[|\]$/g, "");
  if (isIP(host)) {
    if (isPrivateAddress(host)) throw new Error("private IP address not allowed");
    return;
  }
  const addrs = await lookup(host, { all: true });
  if (addrs.length === 0) throw new Error("host did not resolve");
  for (const a of addrs) if (isPrivateAddress(a.address)) throw new Error("host resolves to a private address");
}

export interface SafeFetchOptions {
  method?: "GET" | "POST";
  body?: string;
  timeoutMs?: number;
  maxBytes?: number;
  fetchImpl?: typeof fetch;
  /** Skip DNS resolution check (tests only). */
  skipDns?: boolean;
}

/** fetch with SSRF protection: re-validates every redirect hop, caps size and time. */
export async function safeFetch(
  rawUrl: string,
  opts: SafeFetchOptions = {},
): Promise<{ status: number; text: string; truncated: boolean }> {
  const doFetch = opts.fetchImpl ?? fetch;
  const maxBytes = opts.maxBytes ?? 1_000_000;
  let current = rawUrl;
  for (let hop = 0; hop < 4; hop++) {
    const chk = checkUrl(current);
    if ("violation" in chk) throw new Error(chk.violation);
    if (!opts.skipDns) await assertPublicHost(chk.url.hostname);
    const res = await doFetch(chk.url, {
      method: opts.method ?? "GET",
      ...(opts.body !== undefined ? { body: opts.body } : {}),
      redirect: "manual",
      signal: AbortSignal.timeout(opts.timeoutMs ?? 20_000),
    });
    if (res.status >= 300 && res.status < 400 && res.headers.get("location")) {
      current = new URL(res.headers.get("location")!, chk.url).toString();
      continue;
    }
    const buf = new Uint8Array(await res.arrayBuffer());
    const truncated = buf.length > maxBytes;
    return { status: res.status, text: new TextDecoder().decode(truncated ? buf.slice(0, maxBytes) : buf), truncated };
  }
  throw new Error("too many redirects");
}
