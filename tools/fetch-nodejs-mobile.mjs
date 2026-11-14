// Fetches Node.js for Mobile (nodejs-mobile v18.20.4, MIT) into ui/androidApp/nodejs-mobile, which the Android build
// then links and bundles. It is not stored in git (about 130 MB with headers). Without it the app builds as a pure remote control.
//   node tools/fetch-nodejs-mobile.mjs
import { execFileSync } from "node:child_process";
import { createWriteStream, existsSync, mkdirSync, rmSync, statSync, cpSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const URL_ = "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-android.zip";
const SIZE = 57287354; // the release's published size
const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dest = join(root, "ui", "androidApp", "nodejs-mobile");
const work = join(tmpdir(), `nodejs-mobile-${Date.now()}`);
mkdirSync(work, { recursive: true });
const zip = join(work, "nodejs-mobile.zip");
const res = await fetch(URL_);
if (!res.ok || !res.body) throw new Error(`download failed: ${res.status}`);
await pipeline(res.body, createWriteStream(zip));
if (statSync(zip).size !== SIZE) throw new Error(`unexpected size ${statSync(zip).size}, expected ${SIZE}`);
mkdirSync(join(work, "x"));
execFileSync("tar", ["-xf", zip, "-C", join(work, "x")], { stdio: "inherit" });
rmSync(dest, { recursive: true, force: true });
mkdirSync(join(dest, "bin"), { recursive: true });
cpSync(join(work, "x", "include"), join(dest, "include"), { recursive: true });
for (const abi of ["arm64-v8a", "x86_64"]) {
  if (!existsSync(join(work, "x", "bin", abi, "libnode.so"))) throw new Error(`missing ${abi}`);
  mkdirSync(join(dest, "bin", abi), { recursive: true });
  cpSync(join(work, "x", "bin", abi, "libnode.so"), join(dest, "bin", abi, "libnode.so"));
}
rmSync(work, { recursive: true, force: true });
console.log("nodejs-mobile ready in", dest);
