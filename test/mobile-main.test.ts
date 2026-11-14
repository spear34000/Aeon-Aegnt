import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { before, test } from "node:test";

// mobile-main is the entry point of the server inside the Android app. Node lives in a process of the app's own there, and
// an error nothing catches ends that process before the app can see why, so what matters is what it writes down.

// The app runs the compiled JavaScript (Node 18 cannot strip types), so that is what is started here.
const root = join(import.meta.dirname, "..");
const entry = join(root, "dist-mobile", "node", "mobile-main.js");
before(() => {
  execFileSync(process.execPath, [join(root, "tools", "build-mobile.mjs")], { cwd: root, stdio: "ignore" });
});

const freePort = (): Promise<number> =>
  new Promise((resolve, reject) => {
    const s = createServer();
    s.once("error", reject);
    s.listen(0, "127.0.0.1", () => {
      const port = (s.address() as { port: number }).port;
      s.close(() => resolve(port));
    });
  });

const waitFor = async (check: () => boolean | Promise<boolean>, ms: number): Promise<boolean> => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await check()) return true;
    await new Promise((r) => setTimeout(r, 100));
  }
  return false;
};

test("starts without any terminal input, answers on the port and records its progress in node-error.log", async () => {
  const dir = mkdtempSync(join(tmpdir(), "aeon-mobile-"));
  mkdirSync(join(dir, "cfg")); // the app creates both folders before it starts the server
  mkdirSync(join(dir, "ws"));
  const port = await freePort();
  const child = spawn(process.execPath, [entry, join(dir, "cfg"), join(dir, "ws"), String(port)], { stdio: ["ignore", "pipe", "pipe"] });
  try {
    const up = await waitFor(async () => {
      try {
        return (await fetch(`http://127.0.0.1:${port}/v1/hello`)).ok;
      } catch {
        return false;
      }
    }, 15_000);
    assert.ok(up, "the server answers");
    const log = join(dir, "cfg", "node-error.log");
    assert.ok(existsSync(log));
    const text = readFileSync(log, "utf8");
    for (const line of ["starting", "polyfills loaded", "server code loaded"]) assert.ok(text.includes(line), `node-error.log says "${line}"`);
  } finally {
    child.kill();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("a server that cannot start leaves the reason in node-error.log instead of ending the process with an uncaught error", async () => {
  const dir = mkdtempSync(join(tmpdir(), "aeon-mobile-"));
  mkdirSync(join(dir, "cfg")); // the app creates both folders before it starts the server
  mkdirSync(join(dir, "ws"));
  const port = await freePort();
  const holder = createServer();
  await new Promise<void>((resolve) => holder.listen(port, "127.0.0.1", resolve)); // the port is taken
  let stderr = "";
  const child = spawn(process.execPath, [entry, join(dir, "cfg"), join(dir, "ws"), String(port)], { stdio: ["ignore", "ignore", "pipe"] });
  child.stderr.on("data", (d: Buffer) => (stderr += d.toString()));
  try {
    const log = join(dir, "cfg", "node-error.log");
    const wrote = await waitFor(() => existsSync(log) && /EADDRINUSE/.test(readFileSync(log, "utf8")), 15_000);
    assert.ok(wrote, `the reason is recorded (stderr: ${stderr.slice(0, 300)})`);
  } finally {
    child.kill();
    holder.close();
    rmSync(dir, { recursive: true, force: true });
  }
});
