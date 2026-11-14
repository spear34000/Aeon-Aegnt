import { appendFileSync } from "node:fs";
import { join } from "node:path";
/**
 * Entry point of the server that runs inside the Android app (Node 18 from nodejs-mobile). It listens on the device's
 * loopback only, so nothing else on the network can reach it, and hands its pairing code to the app through a file in the
 * app's private folder. The same code as `aeon serve`; only the way it is started and paired differs.
 *
 * Node lives in a process of the app's own here, and an error that nothing catches ends that process before the app can
 * see why. So this file imports nothing of the server up front: it installs the handlers first, then loads the server
 * with `import()` so an error while loading is caught too. Everything is written to `node-error.log` in the config
 * folder, which the app shows when the agent does not start.
 *
 * Arguments: <config dir> <workspace> [port]
 */
const [cfgDir, workspace, port] = process.argv.slice(2);
if (!cfgDir || !workspace) {
  console.error("usage: mobile-main <config dir> <workspace> [port]");
  process.exit(2);
}
const logFile = join(cfgDir, "node-error.log");
const note = (message: string): void => {
  try {
    appendFileSync(logFile, `${new Date().toISOString()} ${message}\n`);
  } catch {
    /* the folder is not writable: the line below still reaches logcat */
  }
  console.error(message);
};
const text = (e: unknown): string => (e instanceof Error ? (e.stack ?? e.message) : String(e));
process.on("uncaughtException", (e) => note(`uncaughtException: ${text(e)}`));
process.on("unhandledRejection", (e) => note(`unhandledRejection: ${text(e)}`));
process.exit = ((code?: number) => {
  note(`process.exit(${code ?? 0}) was blocked so the app stays open: ${new Error().stack ?? ""}`);
}) as typeof process.exit;
note(`node ${process.version} ${process.platform}/${process.arch} starting`);
try {
  await import("./polyfills.js");
  note("polyfills loaded");
  const { serveCommand } = await import("./serve.js");
  note("server code loaded");
  await serveCommand({ cfgDir, workspace, host: "127.0.0.1", port: Number(port ?? 7421), pairingCodeFile: join(cfgDir, "pairing-code.txt"), noStdin: true });
  note("the server stopped");
} catch (e) {
  note(`the server failed to start: ${text(e)}`);
}
