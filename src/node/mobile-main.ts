import "./polyfills.ts";
import { join } from "node:path";
import { serveCommand } from "./serve.ts";

/**
 * Entry point of the server that runs inside the Android app (Node 18 from nodejs-mobile). It listens on the device's
 * loopback only, so nothing else on the network can reach it, and hands its pairing code to the app through a file in the
 * app's private folder. The same code as `aeon serve`; only the way it is started and paired differs.
 *
 * Arguments: <config dir> <workspace> [port]
 */
const [cfgDir, workspace, port] = process.argv.slice(2);
if (!cfgDir || !workspace) {
  console.error("usage: mobile-main <config dir> <workspace> [port]");
  process.exit(2);
}
await serveCommand({ cfgDir, workspace, host: "127.0.0.1", port: Number(port ?? 7421), pairingCodeFile: join(cfgDir, "pairing-code.txt") });
