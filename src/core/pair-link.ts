/**
 * The text inside a pairing QR code: `aeon://pair?u=<address>[&u=<address>...]&c=<code>`.
 * A phone's own camera app opens it, the Aeon app receives it, and fills in the address and code. It never pairs
 * by itself: the owner still taps 연결, and any app can send such a link, so everything in it is only a suggestion.
 */
export const PAIR_LINK_MAX_ADDRESSES = 3;

const CODE = /^[A-Z2-7]{16}$/;

const isHttpAddress = (s: string): boolean => {
  try {
    const u = new URL(s);
    return (u.protocol === "http:" || u.protocol === "https:") && !u.username && !u.password && u.pathname === "/" && !u.search && !u.hash;
  } catch {
    return false;
  }
};

export function buildPairLink(addresses: string[], code: string): string {
  const c = code.replace(/[^A-Za-z2-7]/g, "").toUpperCase();
  if (!CODE.test(c)) throw new Error("pairing code must be 16 base32 characters");
  const list = addresses.filter(isHttpAddress).slice(0, PAIR_LINK_MAX_ADDRESSES);
  return `aeon://pair?${list.map((a) => `u=${encodeURIComponent(a.replace(/\/$/, ""))}`).join("&")}${list.length > 0 ? "&" : ""}c=${c}`;
}

export function parsePairLink(link: string): { addresses: string[]; code: string } | null {
  const m = /^aeon:\/\/pair\?(.*)$/.exec(link.trim());
  if (!m) return null;
  const addresses: string[] = [];
  let code: string | null = null;
  for (const part of m[1]!.split("&")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    let value: string;
    try {
      value = decodeURIComponent(part.slice(eq + 1));
    } catch {
      return null;
    }
    const key = part.slice(0, eq);
    if (key === "u" && addresses.length < PAIR_LINK_MAX_ADDRESSES && isHttpAddress(value.endsWith("/") ? value : `${value}/`)) addresses.push(value.replace(/\/$/, ""));
    else if (key === "c" && CODE.test(value.toUpperCase())) code = value.toUpperCase();
  }
  return code ? { addresses, code } : null;
}
