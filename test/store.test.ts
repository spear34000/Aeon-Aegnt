import { join } from "node:path";
import { MemoryStore } from "../src/core/memory-store.ts";
import { SqliteStore } from "../src/node/sqlite-store.ts";
import { tempDir } from "./helpers.ts";
import { runStoreConformance } from "./store-conformance.ts";

runStoreConformance("MemoryStore", async () => ({ store: new MemoryStore(), cleanup: () => {} }));

runStoreConformance("SqliteStore", async () => {
  const { dir, cleanup } = tempDir();
  const store = new SqliteStore(join(dir, "t.db"));
  return {
    store,
    cleanup: () => {
      store.close();
      cleanup();
    },
  };
});
