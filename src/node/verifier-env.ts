import { createHash } from "node:crypto";
import { existsSync, readFileSync, statSync } from "node:fs";
import type { VerifierEnv } from "../core/verify.ts";
import type { WorkspaceGuard } from "./fs-guard.ts";
import { safeFetch, type SafeFetchOptions } from "./net-guard.ts";

/** Real filesystem / HTTP checks, confined to the workspace and public network like the tools. */
export function nodeVerifierEnv(guard: WorkspaceGuard, net: Pick<SafeFetchOptions, "fetchImpl" | "skipDns"> = {}): VerifierEnv {
  const abs = (p: string): string => {
    const r = guard.resolve(p);
    if (!r.ok) throw new Error(r.violation);
    return r.abs;
  };
  return {
    async fileExists(p) {
      const a = abs(p);
      return existsSync(a) && statSync(a).isFile();
    },
    async readText(p) {
      const a = abs(p);
      return existsSync(a) && statSync(a).isFile() ? readFileSync(a, "utf8") : null;
    },
    async sha256File(p) {
      const a = abs(p);
      return existsSync(a) && statSync(a).isFile() ? createHash("sha256").update(readFileSync(a)).digest("hex") : null;
    },
    async httpStatus(url) {
      return (await safeFetch(url, { method: "GET", ...net })).status;
    },
  };
}
