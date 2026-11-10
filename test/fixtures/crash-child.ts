// Runs a task and HARD-KILLS the process (exit 137, no cleanup) in the middle of it.
import { MockProvider } from "../../src/core/models/mock.ts";
import { createRuntime } from "../../src/node/runtime.ts";
import { SqliteStore } from "../../src/node/sqlite-store.ts";

const [workspace, dbPath] = process.argv.slice(2) as [string, string];
const model = new MockProvider("m", [
  JSON.stringify({ plan: ["write a.txt", "write b.txt"], criteria: [] }),
  JSON.stringify({ action: "tool", tool: "fs.write", args: { path: "a.txt", content: "one" } }),
  () => {
    process.exit(137); // power loss while the model is "thinking" about step 2
  },
]);
const rt = createRuntime({
  workspace,
  providers: ["m"],
  providerOverrides: [model],
  fallbackAllowed: false,
  dataConsent: [],
  store: new SqliteStore(dbPath),
  holderId: "doomed-process",
});
const task = await rt.engine.submit({ goal: "write two files", criteria: [{ kind: "file_exists", path: "b.txt" }] });
console.log(task.id);
await rt.engine.runTask(task.id);
console.log("unreachable");
