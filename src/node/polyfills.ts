import { webcrypto } from "node:crypto";

/**
 * Node 18 (the runtime inside the Android app) has WebCrypto but not as the global `crypto`, which the core uses.
 * Newer Node already has it, so this changes nothing there.
 */
if (!("crypto" in globalThis)) Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
