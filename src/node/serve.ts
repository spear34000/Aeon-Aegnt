import { networkInterfaces } from "node:os";
import { createInterface } from "node:readline";
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
      for (const a of list ?? []) if (a.family === "IPv4" && !a.internal && (any || a.address === o.host)) out.push(`http://${a.address}:${boundPort}`);
    }
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
    console.log(`\nPairing code (valid 5 minutes, single use):\n  ${PairingManager.format(c.code)}\n`);
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
