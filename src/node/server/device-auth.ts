import { createHash, createHmac, createPublicKey, randomBytes, timingSafeEqual, verify } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

/**
 * Device pairing and request signing (decision D6: one owner, devices paired with key pairs).
 *
 * Keys are ECDSA P-256 because every client platform can hold them non-exportably
 * (Android Keystore, WebCrypto, JVM). Signatures may be DER (Android/JVM/Node) or
 * IEEE-P1363 (WebCrypto); both are accepted.
 */
export interface Device {
  id: string;
  name: string;
  /** base64 of the SPKI DER public key. */
  publicKey: string;
  createdAt: number;
  lastSeenAt: number;
  revoked: boolean;
  /**
   * May change settings, keys, connections and devices. Missing means true: one owner, and every device is theirs
   * (D6). The owner can turn it off per device to leave a device with chat and approvals only.
   */
  admin?: boolean;
}

export const isAdmin = (d: Device | undefined): boolean => d !== undefined && !d.revoked && d.admin !== false;

export class DeviceRegistry {
  private devices = new Map<string, Device>();
  private readonly path: string | null;

  constructor(path: string | null) {
    this.path = path;
    if (path && existsSync(path)) {
      for (const d of JSON.parse(readFileSync(path, "utf8")) as Device[]) this.devices.set(d.id, d);
    }
  }

  private save(): void {
    if (!this.path) return;
    mkdirSync(dirname(this.path), { recursive: true });
    const tmp = `${this.path}.tmp-${process.pid}`;
    writeFileSync(tmp, JSON.stringify([...this.devices.values()], null, 2), { mode: 0o600 });
    renameSync(tmp, this.path);
  }

  add(name: string, publicKey: string, now: number, admin = true): Device {
    const d: Device = { id: `dev_${randomBytes(9).toString("hex")}`, name: name.slice(0, 64), publicKey, createdAt: now, lastSeenAt: now, revoked: false, admin };
    this.devices.set(d.id, d);
    this.save();
    return d;
  }

  get(id: string): Device | undefined {
    return this.devices.get(id);
  }

  touch(id: string, now: number): void {
    const d = this.devices.get(id);
    if (d && now - d.lastSeenAt > 60_000) {
      d.lastSeenAt = now;
      this.save();
    }
  }

  update(id: string, change: { name?: string; admin?: boolean }): Device | undefined {
    const d = this.devices.get(id);
    if (!d || d.revoked) return undefined;
    if (change.name !== undefined) d.name = change.name.slice(0, 64);
    if (change.admin !== undefined) d.admin = change.admin;
    this.save();
    return d;
  }

  revoke(id: string): boolean {
    const d = this.devices.get(id);
    if (!d) return false;
    d.revoked = true;
    this.save();
    return true;
  }

  list(): Device[] {
    return [...this.devices.values()];
  }
}

export function sha256Hex(data: string | Buffer): string {
  return createHash("sha256").update(data).digest("hex");
}

/** The exact bytes a client signs. Binds method, path+query, time, nonce and body. */
export function canonicalRequest(method: string, pathAndQuery: string, timestamp: string, nonce: string, body: Buffer | string): string {
  return ["AEON-V1", method.toUpperCase(), pathAndQuery, timestamp, nonce, sha256Hex(body)].join("\n");
}

export function verifySignature(publicKeyB64: string, data: string, signatureB64: string): boolean {
  try {
    const key = createPublicKey({ key: Buffer.from(publicKeyB64, "base64"), format: "der", type: "spki" });
    const sig = Buffer.from(signatureB64, "base64");
    if (sig.length === 64) return verify("sha256", Buffer.from(data), { key, dsaEncoding: "ieee-p1363" }, sig);
    return verify("sha256", Buffer.from(data), { key, dsaEncoding: "der" }, sig);
  } catch {
    return false;
  }
}

/** Remembers nonces for the freshness window so a captured request cannot be replayed. */
export class NonceCache {
  private seen = new Map<string, number>();
  private readonly ttlMs: number;
  constructor(ttlMs: number) {
    this.ttlMs = ttlMs;
  }
  /** Returns false if the nonce was already used. */
  use(key: string, now: number): boolean {
    for (const [k, t] of this.seen) if (now - t > this.ttlMs) this.seen.delete(k);
    if (this.seen.has(key)) return false;
    this.seen.set(key, now);
    return true;
  }
}

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
function base32(buf: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const b of buf) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  return out;
}

export interface PairingCode {
  code: string;
  expiresAt: number;
  /** Whether the device paired with this code may change settings. */
  admin: boolean;
}

export interface PairRequest {
  deviceName: string;
  publicKey: string;
  clientNonce: string;
  proof: string;
}

export type PairResult =
  | { ok: true; device: Device; serverProof: string; serverKey?: string; serverKeyProof?: string }
  | { ok: false; reason: "invalid" | "locked" | "bad_request" };

/**
 * One-time pairing codes (96 random bits, 5 minutes, single use). The code never crosses the network:
 * the device proves knowledge of it with an HMAC over its public key, and the server proves it back,
 * so an eavesdropper on plain HTTP can neither learn the code nor impersonate the server.
 */
export class PairingManager {
  private codes: PairingCode[] = [];
  private failures: number[] = [];
  private lockedUntil = 0;
  private readonly registry: DeviceRegistry;
  private readonly ttlMs: number;
  private readonly maxFailures: number;
  /** The server's sealing key (SPKI, base64). When set, a pairing device receives and pins it. */
  serverKey: string | null = null;
  constructor(registry: DeviceRegistry, ttlMs = 5 * 60_000, maxFailures = 5) {
    this.registry = registry;
    this.ttlMs = ttlMs;
    this.maxFailures = maxFailures;
  }

  newCode(now: number, admin = true): PairingCode {
    this.codes = this.codes.filter((c) => c.expiresAt > now);
    const code = base32(randomBytes(10)); // 16 chars = 80 bits, shown as 4-4-4-4
    const c = { code, expiresAt: now + this.ttlMs, admin };
    this.codes.push(c);
    return c;
  }

  static format(code: string): string {
    return code.match(/.{1,4}/g)!.join("-");
  }

  static normalize(code: string): string {
    return code.replace(/[^A-Za-z2-7]/g, "").toUpperCase();
  }

  static clientProof(code: string, deviceName: string, publicKey: string, clientNonce: string): string {
    return createHmac("sha256", code).update(`aeon-pair-client|${deviceName}|${publicKey}|${clientNonce}`).digest("base64");
  }

  static serverProof(code: string, deviceId: string, clientNonce: string, publicKey: string): string {
    return createHmac("sha256", code).update(`aeon-pair-server|${deviceId}|${clientNonce}|${publicKey}`).digest("base64");
  }

  /** Proves the sealing key came from the server that knew the code, so the device can pin it. */
  static serverKeyProof(code: string, serverKey: string, clientNonce: string, deviceId: string): string {
    return createHmac("sha256", code).update(`aeon-pair-key|${serverKey}|${clientNonce}|${deviceId}`).digest("base64");
  }

  complete(req: PairRequest, now: number): PairResult {
    if (now < this.lockedUntil) return { ok: false, reason: "locked" };
    if (typeof req.deviceName !== "string" || !req.deviceName || typeof req.publicKey !== "string" || typeof req.clientNonce !== "string" || typeof req.proof !== "string") {
      return { ok: false, reason: "bad_request" };
    }
    try {
      createPublicKey({ key: Buffer.from(req.publicKey, "base64"), format: "der", type: "spki" });
    } catch {
      return { ok: false, reason: "bad_request" };
    }
    this.codes = this.codes.filter((c) => c.expiresAt > now);
    for (const c of this.codes) {
      const expected = Buffer.from(PairingManager.clientProof(c.code, req.deviceName, req.publicKey, req.clientNonce));
      const given = Buffer.from(req.proof);
      if (expected.length === given.length && timingSafeEqual(expected, given)) {
        this.codes = this.codes.filter((x) => x !== c); // single use
        this.failures = [];
        const device = this.registry.add(req.deviceName, req.publicKey, now, c.admin);
        const key = this.serverKey;
        return {
          ok: true,
          device,
          serverProof: PairingManager.serverProof(c.code, device.id, req.clientNonce, req.publicKey),
          ...(key ? { serverKey: key, serverKeyProof: PairingManager.serverKeyProof(c.code, key, req.clientNonce, device.id) } : {}),
        };
      }
    }
    this.failures = [...this.failures.filter((t) => now - t < 5 * 60_000), now];
    if (this.failures.length >= this.maxFailures) {
      this.codes = []; // too many guesses: burn all codes
      this.lockedUntil = now + 60_000;
      this.failures = [];
    }
    return { ok: false, reason: "invalid" };
  }
}
