/**
 * Secret storage contract (decision D11). Implementations live next to the platform that provides
 * the protection (DPAPI on Windows, Secret Service / 0600 file on Linux, Android Keystore in the
 * Kotlin app). Secrets are never part of the source tree, the package, or the task database.
 */
export interface SecretStore {
  /** Human-readable backend name, shown to the owner so they know where secrets live. */
  readonly kind: string;
  get(name: string): Promise<string | null>;
  set(name: string, value: string): Promise<void>;
  delete(name: string): Promise<void>;
  /** Names only. Values are never listed. */
  list(): Promise<string[]>;
}

const NAME = /^[A-Z][A-Z0-9_]{1,63}$/;

export function assertSecretName(name: string): void {
  if (!NAME.test(name)) throw new Error(`Invalid secret name "${name}" (use UPPER_SNAKE_CASE, e.g. EXAONE_API_KEY)`);
}

/** Read-only view of environment variables. Always wins over stored values (explicit beats implicit). */
export class EnvSecretStore implements SecretStore {
  readonly kind = "environment variables (read-only)";
  private readonly env: Record<string, string | undefined>;
  constructor(env: Record<string, string | undefined>) {
    this.env = env;
  }
  async get(name: string): Promise<string | null> {
    const v = this.env[name];
    return v ? v : null;
  }
  async set(): Promise<void> {
    throw new Error("Environment variables are read-only; set the variable in your shell instead.");
  }
  async delete(): Promise<void> {
    throw new Error("Environment variables are read-only.");
  }
  async list(): Promise<string[]> {
    return Object.keys(this.env).filter((k) => NAME.test(k) && /(KEY|TOKEN|SECRET|PASSWORD)/.test(k) && this.env[k]);
  }
}

/** Reads fall through the chain in order; writes go to the first writable store. */
export class ChainSecretStore implements SecretStore {
  readonly kind: string;
  private readonly stores: SecretStore[];
  private readonly writable: SecretStore | null;
  constructor(stores: SecretStore[], writable: SecretStore | null) {
    this.stores = stores;
    this.writable = writable;
    this.kind = stores.map((s) => s.kind).join(" > ");
  }
  async get(name: string): Promise<string | null> {
    for (const s of this.stores) {
      const v = await s.get(name);
      if (v !== null) return v;
    }
    return null;
  }
  async set(name: string, value: string): Promise<void> {
    if (!this.writable) throw new Error("No writable secret store on this platform. Use environment variables.");
    assertSecretName(name);
    await this.writable.set(name, value);
  }
  async delete(name: string): Promise<void> {
    if (!this.writable) throw new Error("No writable secret store on this platform.");
    await this.writable.delete(name);
  }
  async list(): Promise<string[]> {
    const all = new Set<string>();
    for (const s of this.stores) for (const n of await s.list()) all.add(n);
    return [...all].sort();
  }
}

/** In-memory store for tests and for UIs that hold a secret only for the current session. */
export class MemorySecretStore implements SecretStore {
  readonly kind = "memory";
  private m = new Map<string, string>();
  async get(name: string): Promise<string | null> {
    return this.m.get(name) ?? null;
  }
  async set(name: string, value: string): Promise<void> {
    assertSecretName(name);
    this.m.set(name, value);
  }
  async delete(name: string): Promise<void> {
    this.m.delete(name);
  }
  async list(): Promise<string[]> {
    return [...this.m.keys()].sort();
  }
}
