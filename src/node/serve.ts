import { existsSync, rmSync, writeFileSync } from "node:fs";
import { networkInterfaces } from "node:os";
import { createInterface } from "node:readline";
import { buildPairLink } from "../core/pair-link.ts";
import { encodeQr, qrToAnsi } from "../core/qr.ts";
import { join } from "node:path";
import { bootRuntime } from "./bootstrap.ts";
import { ConnectionService } from "./connections.ts";
import { ProviderCatalog } from "./provider-catalog.ts";
import { ServerIdentity } from "./server/seal.ts";
import { DeviceRegistry, PairingManager } from "./server/device-auth.ts";
import { AeonServer } from "./server/server.ts";

export interface ServeOptions {
  cfgDir: string;
  workspace: string;
  host: string;
  port: number;
  web?: string | undefined;
  /**
   * The app that runs this server (the Android app) reads the pairing code from this file, which only it can read, instead
   * of someone typing it. A new code is written at start and whenever a file named like this plus `.request` appears.
   */
  pairingCodeFile?: string | undefined;
}

/**
 * `aeon serve`: the local API used by the web, Windows and Android UIs.
 * Binds to loopback by default. Binding to a LAN address is an explicit choice because the transport is
 * plain HTTP there: requests are signed and replay-protected, but they are not encrypted.
 */
export async function serveCommand(o: ServeOptions): Promise<number> {
  let { runtime, secrets, problem } = await bootRuntime(o.cfgDir, o.workspace, {}, undefined, true);
  const registry = new DeviceRegistry(join(o.cfgDir, "devices.json"));
  const pairing = new PairingManager(registry);
  const lan = o.host !== "127.0.0.1" && o.host !== "::1" && o.host !== "localhost";
  let boundPort = o.port;
  /** Addresses a phone or another computer can type. Empty while the server listens on this machine only. */
  const addresses = (): string[] => {
    if (!lan) return [];
    const any = o.host === "0.0.0.0" || o.host === "::";
    const out: string[] = [];
    for (const list of Object.values(networkInterfaces())) {
      for (const a of list ?? []) if (a.family === "IPv4" && !a.internal && !a.address.startsWith("169.254.") && (any || a.address === o.host)) out.push(`http://${a.address}:${boundPort}`);
    }
    // A phone is most likely on the 192.168.x or 10.x network: put those first, VPN-style 100.64/10 and the rest later.
    const rank = (u: string): number => {
      const ip = u.slice("http://".length).split(":")[0]!;
      const [a, b] = ip.split(".").map(Number) as [number, number];
      return a === 192 && b === 168 ? 0 : a === 10 ? 1 : a === 172 && b >= 16 && b <= 31 ? 2 : a === 100 && b >= 64 && b <= 127 ? 3 : 4;
    };
    out.sort((x, y) => rank(x) - rank(y));
    return out.length > 0 || any ? out : [`http://${o.host}:${boundPort}`];
  };
  const server = new AeonServer({
    getRuntime: () => runtime,
    secrets,
    connections: new ConnectionService({ configDir: o.cfgDir }),
    providers: new ProviderCatalog({ configDir: o.cfgDir }),
    identity: ServerIdentity.load(join(o.cfgDir, "server-key.json")),
    addresses,
    getProblem: () => problem,
    configDir: o.cfgDir,
    registry,
    pairing,
    ...(o.web ? { webRoot: o.web } : {}),
    onConfigChanged: async () => {
      // New settings or keys take effect for tasks started afterwards; running tasks keep their runtime.
      ({ runtime, secrets, problem } = await bootRuntime(o.cfgDir, o.workspace, {}, secrets, true));
    },
  });
  const { port } = await server.listen(o.port, o.host);
  boundPort = port;
  const showCode = () => {
    const c = pairing.newCode(Date.now());
    if (o.pairingCodeFile) {
      // The code goes only to the app that started this server. Not into the log: on Android the log is readable over adb.
      writeFileSync(o.pairingCodeFile, c.code, { mode: 0o600 });
      console.log("\nA pairing code was written for the app that started this server.\n");
      return;
    }
    console.log(`\nPairing code (valid 5 minutes, single use):\n  ${PairingManager.format(c.code)}\n`);
    // A QR code is only useful when another device can reach this machine. The phone's camera opens it in the Aeon app.
    const reachable = addresses();
    if (reachable.length > 0 && process.stdout.isTTY) {
      console.log("Scan with the phone's camera to fill in the address and code:\n");
      console.log(qrToAnsi(encodeQr(buildPairLink(reachable, c.code)), 2));
      console.log();
    }
  };
  console.log(`Aeon server listening on http://${o.host}:${port}  (workspace: ${runtime.guard.root})`);
  if (problem) console.log(`Setup needed: ${problem}`);
  if (lan) {
    console.log("WARNING: listening beyond this computer over plain HTTP. Requests are signed but NOT encrypted; use only on a network you trust.");
    for (const a of addresses()) console.log(`  Other devices connect to: ${a}`);
  } else {
    console.log("Only this machine can connect. To pair a phone or another computer, start with: serve --host 0.0.0.0");
  }
  console.log("Type 'pair' + Enter for a new pairing code, 'devices' to list devices, 'quit' to stop.");
  showCode();
  if (o.pairingCodeFile) {
    const request = `${o.pairingCodeFile}.request`;
    setInterval(() => {
      if (!existsSync(request)) return;
      rmSync(request, { force: true });
      showCode();
    }, 500).unref();
  }

  const rl = createInterface({ input: process.stdin });
  const done = new Promise<void>((resolve) => {
    rl.on("line", (line) => {
      const cmd = line.trim().toLowerCase();
      if (cmd === "pair") showCode();
      else if (cmd === "devices") for (const d of registry.list()) console.log(`${d.id}  ${d.name}  ${d.revoked ? "REVOKED" : "active"}`);
      else if (cmd === "quit" || cmd === "exit") resolve();
    });
    rl.on("close", () => {
      /* stdin closed (e.g. started as a service): keep serving until signalled */
    });
    process.once("SIGINT", () => resolve());
    process.once("SIGTERM", () => resolve());
  });
  await done;
  rl.close();
  await server.close();
  return 0;
}
