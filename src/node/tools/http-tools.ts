import type { Tool } from "../../core/tools.ts";
import { checkUrl, safeFetch, type SafeFetchOptions } from "../net-guard.ts";

type Opts = Pick<SafeFetchOptions, "fetchImpl" | "skipDns">;

export function httpTools(opts: Opts = {}): Tool[] {
  const get: Tool = {
    name: "http.get",
    description: "HTTP GET a public URL (private/local addresses are blocked). Returns status and body text.",
    argsHint: '{"url":"https://..."}',
    assess(a) {
      const url = (a as { url?: unknown } | null)?.url;
      const c = checkUrl(url);
      if ("violation" in c) return { risk: "network_read", summary: "GET (blocked)", resources: [], violation: c.violation };
      return { risk: "network_read", summary: `GET ${c.url.origin}${c.url.pathname}`, resources: [c.url.toString()] };
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
        summary: `POST ${c.url.origin}${c.url.pathname} (${o.body.length} chars)`,
        resources: [c.url.toString()],
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
