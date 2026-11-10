import type { Tool } from "../../core/tools.ts";
import { checkUrl, safeFetch, type SafeFetchOptions } from "../net-guard.ts";

type Opts = Pick<SafeFetchOptions, "fetchImpl" | "skipDns" | "resolver">;

const clip = (s: string, n: number): string => (s.length <= n ? s : `${s.slice(0, n)}… [+${s.length - n} chars]`);

export function httpTools(opts: Opts = {}): Tool[] {
  const get: Tool = {
    name: "http.get",
    description: "HTTP GET a public URL (private/local addresses are blocked). Returns status and body text.",
    argsHint: '{"url":"https://..."}',
    assess(a) {
      const url = (a as { url?: unknown } | null)?.url;
      const c = checkUrl(url);
      if ("violation" in c) return { risk: "network_read", summary: "GET (blocked)", resources: [], violation: c.violation };
      // A query string or fragment can smuggle data out to the remote host, so such requests are
      // flagged and need approval even though a plain GET is auto-allowed.
      const carriesData = c.url.search.length > 0 || c.url.hash.length > 0;
      return { risk: "network_read", summary: `GET ${clip(c.url.toString(), 300)}`, resources: [c.url.toString()], ...(carriesData ? { exfilRisk: true } : {}) };
    },
    async run(args) {
      const url = (args as { url?: unknown } | null)?.url;
      if (typeof url !== "string") return { ok: false, output: "url must be a string" };
      try {
        const r = await safeFetch(url, { method: "GET", ...opts });
        return { ok: r.status >= 200 && r.status < 400, output: `status=${r.status}\n${r.text}${r.truncated ? "\n[truncated]" : ""}` };
      } catch (e) {
        return { ok: false, output: `request failed: ${(e as Error).message}` };
      }
    },
  };

  const post: Tool = {
    name: "http.post",
    description: "HTTP POST to a public URL (requires owner approval). Has external side effects.",
    argsHint: '{"url":"https://...","body":"string"}',
    assess(a) {
      const o = a as { url?: unknown; body?: unknown } | null;
      const c = checkUrl(o?.url);
      if ("violation" in c) return { risk: "network_write", summary: "POST (blocked)", resources: [], violation: c.violation };
      if (typeof o?.body !== "string") {
        return { risk: "network_write", summary: "POST (blocked)", resources: [], violation: "body must be a string" };
      }
      return {
        risk: "network_write",
        summary: `POST ${clip(c.url.toString(), 300)} body(${o.body.length} chars): ${clip(JSON.stringify(o.body), 200)}`,
        resources: [c.url.toString()],
        irreversible: true,
      };
    },
    async run(args) {
      const o = args as { url?: unknown; body?: unknown } | null;
      if (typeof o?.url !== "string" || typeof o.body !== "string") return { ok: false, output: "url and body must be strings" };
      try {
        const r = await safeFetch(o.url, { method: "POST", body: o.body, ...opts });
        return { ok: r.status >= 200 && r.status < 400, output: `status=${r.status}\n${r.text}${r.truncated ? "\n[truncated]" : ""}` };
      } catch (e) {
        return { ok: false, output: `request failed: ${(e as Error).message}` };
      }
    },
  };

  return [get, post];
}
