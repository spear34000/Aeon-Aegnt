// Builds the server for the Node 18 that runs inside the Android app: plain JavaScript (Node 18 cannot strip types),
// written to dist-mobile/ with a package.json that marks it as ES modules. Run with `npm run build:mobile`.
import { execFileSync } from "node:child_process";
import { rmSync, writeFileSync, mkdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const out = join(root, "dist-mobile");
rmSync(out, { recursive: true, force: true });
const tsc = createRequire(import.meta.url).resolve("typescript/bin/tsc");
execFileSync(process.execPath, [tsc, "-p", join(root, "tsconfig.mobile.json")], { cwd: root, stdio: "inherit" });
mkdirSync(out, { recursive: true });
writeFileSync(join(out, "package.json"), JSON.stringify({ name: "aeon-mobile", private: true, type: "module" }) + "\n");
console.log("built", out);
