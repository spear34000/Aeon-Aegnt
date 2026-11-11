import { createCipheriv, createDecipheriv, createHash, createPrivateKey, createPublicKey, diffieHellman, generateKeyPairSync, randomBytes, type KeyObject } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

/** A value encrypted for one receiver. `epk` is the sender's one-time ECDH public key (SPKI DER, base64). */
export interface SealedBox {
  epk: string;
  iv: string;
  ct: string;
}

/**
 * The server's long-term ECDH P-256 key. A device pins the public half while pairing (it is authenticated by the
 * pairing code), and later uses it to send secrets and receive pairing codes over plain HTTP without an
 * eavesdropper or a machine in the middle being able to read them.
 *
 * Box format ("AEON-SEAL-V1"): the sender makes a one-time key pair, both sides compute the ECDH shared secret,
 * key = SHA-256("AEON-SEAL-V1|" + direction + "|" || shared || epk || serverKey), then AES-256-GCM with a
 * 12-byte random IV; `ct` is ciphertext followed by the 16-byte tag. The caller supplies the associated data that
 * binds the box to its purpose (device, secret name).
 */
export class ServerIdentity {
  /** SPKI DER, base64. */
  readonly publicKey: string;
  private readonly privateKey: KeyObject;

  private constructor(privateKey: KeyObject) {
    this.privateKey = privateKey;
    this.publicKey = createPublicKey(privateKey).export({ type: "spki", format: "der" }).toString("base64");
  }

  /** Loads the key from [path], creating it on first use. A null path gives a key that lives only in memory (tests). */
  static load(path: string | null): ServerIdentity {
    if (path && existsSync(path)) {
      const saved = JSON.parse(readFileSync(path, "utf8")) as { privateKey: string };
      return new ServerIdentity(createPrivateKey({ key: Buffer.from(saved.privateKey, "base64"), format: "der", type: "pkcs8" }));
    }
    const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    if (path) {
      mkdirSync(dirname(path), { recursive: true });
      const tmp = `${path}.tmp-${process.pid}`;
      writeFileSync(tmp, JSON.stringify({ privateKey: privateKey.export({ type: "pkcs8", format: "der" }).toString("base64") }), { mode: 0o600 });
      renameSync(tmp, path);
    }
    return new ServerIdentity(privateKey);
  }

  private key(epkB64: string, direction: "c2s" | "s2c"): Buffer {
    const epk = Buffer.from(epkB64, "base64");
    const peer = createPublicKey({ key: epk, format: "der", type: "spki" });
    if (peer.asymmetricKeyType !== "ec" || peer.asymmetricKeyDetails?.namedCurve !== "prime256v1") throw new Error("unsupported key");
    const shared = diffieHellman({ privateKey: this.privateKey, publicKey: peer });
    return createHash("sha256")
      .update(`AEON-SEAL-V1|${direction}|`)
      .update(shared)
      .update(epk)
      .update(Buffer.from(this.publicKey, "base64"))
      .digest();
  }

  /** Decrypts a box a device sealed for this server. Throws on any tampering or wrong context. */
  open(box: SealedBox, aad: string): Buffer {
    const iv = Buffer.from(box.iv, "base64");
    const data = Buffer.from(box.ct, "base64");
    if (iv.length !== 12 || data.length < 17) throw new Error("malformed box");
    const d = createDecipheriv("aes-256-gcm", this.key(box.epk, "c2s"), iv);
    d.setAAD(Buffer.from(aad, "utf8"));
    d.setAuthTag(data.subarray(data.length - 16));
    return Buffer.concat([d.update(data.subarray(0, data.length - 16)), d.final()]);
  }

  /** Encrypts for the holder of the one-time key [epkB64] (sent by a device in a signed request). */
  sealFor(epkB64: string, plaintext: Buffer, aad: string): { iv: string; ct: string } {
    const iv = randomBytes(12);
    const c = createCipheriv("aes-256-gcm", this.key(epkB64, "s2c"), iv);
    c.setAAD(Buffer.from(aad, "utf8"));
    const ct = Buffer.concat([c.update(plaintext), c.final(), c.getAuthTag()]);
    return { iv: iv.toString("base64"), ct: ct.toString("base64") };
  }
}

export function isSealedBox(v: unknown): v is SealedBox {
  const o = v as Record<string, unknown> | null;
  return o !== null && typeof o === "object" && typeof o.epk === "string" && typeof o.iv === "string" && typeof o.ct === "string" && o.epk.length <= 400 && o.iv.length <= 32 && o.ct.length <= 16_000;
}
