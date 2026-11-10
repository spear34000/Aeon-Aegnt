// Tries to claim a task in a shared SQLite file. Prints CLAIMED or NO.
import { SqliteStore } from "../../src/node/sqlite-store.ts";

const [dbPath, taskId, holder, startAt] = process.argv.slice(2) as [string, string, string, string];
const store = new SqliteStore(dbPath);
while (Date.now() < Number(startAt)) {
  /* spin until the shared start time so both processes race */
}
const r = await store.claim(taskId, holder, 60_000, Date.now());
console.log(r ? `CLAIMED ${r.token}` : "NO");
store.close();
