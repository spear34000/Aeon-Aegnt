import { createInterface } from "node:readline/promises";
import { parseArgs } from "node:util";
import type { VerificationCriterion } from "../core/verify.ts";
import { parseConfig, type AeonConfig } from "../core/config.ts";
import { assertSecretName } from "../core/secrets.ts";
import { loadConfig, saveConfig } from "./config-file.ts";
import { configDir } from "./platform-paths.ts";
import { bootRuntime } from "./bootstrap.ts";
import type { createRuntime } from "./runtime.ts";
import { defaultSecretStore } from "./secret-backends.ts";
import { serveCommand } from "./serve.ts";

const HELP = `aeon - continuity-first personal agent (Phase 1, local runtime)

Usage: node src/node/cli.ts <command> [options]

Commands:
  run "<goal>" [--check file_exists:PATH] [--check file_contains:PATH:TEXT] [--at ISO_TIME]
  tick                       run everything that is due (scheduler pass)
  list                       list tasks
  show <taskId>              task details, steps, verification evidence
  approvals                  list pending approvals
  approve <approvalId> | deny <approvalId>
  cancel <taskId> | verify <taskId> | confirm <taskId>
  usage                      model calls recorded so far
  serve [--host H] [--port N] [--web DIR]   start the local API server for the web/Windows/Android UIs
  secrets set NAME           store a secret (value read from a hidden prompt or stdin, never from argv)
  secrets list | delete NAME names only; values are never printed
  config show                print saved settings (no secrets)
  config set k=v ...         providers=a,b consent=a,b fallback=true|false ollamaModel=... (see docs)

Options:
  --workspace DIR            workspace root (default: current directory)
  --providers a,b            e.g. exaone,ollama (default: saved config, else exaone)
  --fallback                 allow automatic switch to later providers
  --consent a,b              external providers you allow to receive prompts (default: saved config, else none)
  --yes-consent              shorthand: consent for every listed external provider

Secrets: environment variables win; otherwise the platform store is used (Windows DPAPI, Linux Secret
Service or a 0600 file; macOS: environment only). Names: EXAONE_API_KEY, OPENAI_COMPAT_API_KEY.
Settings and secrets live in AEON_CONFIG_DIR, or the platform default config directory.
`;

function parseCheck(spec: string): VerificationCriterion {
  const [kind, path, ...rest] = spec.split(":");
  if (kind === "file_exists" && path) return { kind, path, source: "user" };
  if (kind === "file_contains" && path && rest.length > 0) return { kind, path, text: rest.join(":"), source: "user" };
  throw new Error(`bad --check: ${spec}`);
}

async function main(): Promise<number> {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      workspace: { type: "string", default: process.cwd() },
      providers: { type: "string" },
      fallback: { type: "boolean" },
      consent: { type: "string" },
      "yes-consent": { type: "boolean", default: false },
      check: { type: "string", multiple: true, default: [] },
      at: { type: "string" },
      host: { type: "string", default: "127.0.0.1" },
      port: { type: "string", default: "7421" },
      web: { type: "string" },
      help: { type: "boolean", default: false },
    },
  });
  const [cmd, arg] = positionals;
  if (values.help || !cmd) {
    console.log(HELP);
    return cmd ? 0 : 1;
  }
  const cfgDir = configDir();
  if (cmd === "secrets") return secretsCommand(arg, positionals[2], cfgDir);
  if (cmd === "config") return configCommand(arg, positionals.slice(2), cfgDir);
  if (cmd === "serve") return serveCommand({ cfgDir, workspace: values.workspace!, host: values.host!, port: Number(values.port), web: values.web });

  const list = (v: string) => v.split(",").map((x) => x.trim()).filter(Boolean);
  const { runtime: rt } = await bootRuntime(cfgDir, values.workspace!, {
    ...(values.providers !== undefined ? { providers: list(values.providers) } : {}),
    ...(values.consent !== undefined ? { dataConsent: list(values.consent) } : {}),
    ...(values.fallback !== undefined ? { fallbackAllowed: values.fallback } : {}),
    yesConsent: values["yes-consent"] ?? false,
  });
  const { engine, store } = rt;

  switch (cmd) {
    case "run": {
      if (!arg) throw new Error('usage: run "<goal>"');
      const task = await engine.submit({
        goal: arg,
        criteria: (values.check ?? []).map(parseCheck),
        ...(values.at ? { runAfter: Date.parse(values.at) } : {}),
      });
      console.log(`submitted ${task.id}`);
      if (values.at) return 0;
      await drive(rt, task.id);
      return 0;
    }
    case "tick": {
      for (const s of await engine.tick()) console.log(JSON.stringify(s));
      return 0;
    }
    case "list": {
      for (const t of await store.listTasks()) {
        console.log(`${t.id}  ${t.status.padEnd(22)} ${t.verification?.status ?? "-"}  ${t.goal.slice(0, 60)}`);
      }
      return 0;
    }
    case "show": {
      const t = await store.getTask(arg ?? "");
      if (!t) throw new Error("task not found");
      console.log(JSON.stringify({ ...t, steps: t.steps.map((s) => ({ ...s, output: s.output.slice(0, 200) })) }, null, 2));
      return 0;
    }
    case "approvals": {
      for (const a of await store.listApprovals("pending")) {
        console.log(`${a.id}  task=${a.taskId}  risk=${a.risk}  expires=${new Date(a.expiresAt).toISOString()}  ${a.summary}`);
      }
      return 0;
    }
    case "approve":
    case "deny": {
      await engine.decideApproval(arg ?? "", cmd === "approve" ? "approved" : "denied");
      console.log(`${cmd}d ${arg}`);
      return 0;
    }
    case "cancel":
      console.log((await engine.cancel(arg ?? "")).status);
      return 0;
    case "verify":
      console.log(JSON.stringify((await engine.reverify(arg ?? "")).verification, null, 2));
      return 0;
    case "confirm":
      console.log((await engine.confirmByUser(arg ?? "")).verification?.status);
      return 0;
    case "usage": {
      for (const u of await store.listUsage()) {
        console.log(`${new Date(u.at).toISOString()} ${u.provider}/${u.model} in=${u.inputTokens} out=${u.outputTokens} reasoning=${u.reasoningTokens} ok=${u.ok}`);
      }
      return 0;
    }
    default:
      console.log(HELP);
      return 1;
  }
}


/** Read a secret without echo on a TTY, or from stdin when piped. The value never goes through argv. */
async function readSecret(prompt: string): Promise<string> {
  const CR = String.fromCharCode(13);
  const LF = String.fromCharCode(10);
  const CTRL_C = String.fromCharCode(3);
  const DEL = String.fromCharCode(127);
  const BS = String.fromCharCode(8);
  if (!process.stdin.isTTY) {
    const chunks: Buffer[] = [];
    for await (const c of process.stdin) chunks.push(c as Buffer);
    let text = Buffer.concat(chunks).toString("utf8");
    while (text.endsWith(LF) || text.endsWith(CR)) text = text.slice(0, -1);
    return text;
  }
  process.stdout.write(prompt);
  return new Promise((resolve, reject) => {
    let buf = "";
    process.stdin.setRawMode(true);
    process.stdin.resume();
    process.stdin.setEncoding("utf8");
    const done = (fn: () => void) => {
      process.stdin.setRawMode(false);
      process.stdin.pause();
      process.stdin.off("data", onData);
      process.stdout.write(LF);
      fn();
    };
    const onData = (ch: string) => {
      for (const c of ch) {
        if (c === CR || c === LF) return done(() => resolve(buf));
        if (c === CTRL_C) return done(() => reject(new Error("cancelled")));
        if (c === DEL || c === BS) buf = buf.slice(0, -1);
        else buf += c;
      }
    };
    process.stdin.on("data", onData);
  });
}

async function secretsCommand(sub: string | undefined, name: string | undefined, dir: string): Promise<number> {
  const store = await defaultSecretStore({ dir });
  switch (sub) {
    case "set": {
      if (!name) throw new Error("usage: secrets set NAME");
      assertSecretName(name);
      const value = await readSecret(`Value for ${name} (input hidden): `);
      if (!value) throw new Error("empty value; nothing stored");
      await store.set(name, value);
      console.log(`stored ${name} in: ${store.kind}`);
      return 0;
    }
    case "delete":
      if (!name) throw new Error("usage: secrets delete NAME");
      await store.delete(name);
      console.log(`deleted ${name}`);
      return 0;
    case "list": {
      console.log(`backend: ${store.kind}`);
      for (const n of await store.list()) console.log(n);
      return 0;
    }
    default:
      throw new Error("usage: secrets set NAME | secrets list | secrets delete NAME");
  }
}

function configCommand(sub: string | undefined, rest: string[], dir: string): number {
  if (sub === "show") {
    console.log(JSON.stringify(loadConfig(dir), null, 2));
    return 0;
  }
  if (sub !== "set" || rest.length === 0) throw new Error("usage: config show | config set key=value ...");
  const cfg: Record<string, unknown> = { ...loadConfig(dir) };
  const csv = (v: string) => v.split(",").map((x) => x.trim()).filter(Boolean);
  for (const kv of rest) {
    const i = kv.indexOf("=");
    if (i < 1) throw new Error(`expected key=value, got: ${kv}`);
    const k = kv.slice(0, i);
    const v = kv.slice(i + 1);
    if (k === "providers" || k === "dataConsent" || k === "consent") cfg[k === "consent" ? "dataConsent" : k] = csv(v);
    else if (k === "fallback" || k === "fallbackAllowed") cfg.fallbackAllowed = v === "true";
    else cfg[k] = v;
  }
  const checked: AeonConfig = parseConfig(cfg);
  saveConfig(dir, checked);
  console.log(JSON.stringify(checked, null, 2));
  return 0;
}

/** Run a task to a resting state, prompting interactively for approvals. */
async function drive(rt: ReturnType<typeof createRuntime>, taskId: string): Promise<void> {
  const { engine, store } = rt;
  for (let i = 0; i < 50; i++) {
    const s = await engine.runTask(taskId);
    const t = await store.getTask(taskId);
    if (!t) return;
    console.log(`[${t.status}] ${t.note ?? ""}${t.error ? ` - ${t.error}` : ""}`);
    if (t.status === "WAITING_FOR_APPROVAL" && t.pendingApprovalId && process.stdin.isTTY) {
      const a = await store.getApproval(t.pendingApprovalId);
      if (!a) return;
      const rl = createInterface({ input: process.stdin, output: process.stdout });
      const ans = (await rl.question(`Approve? ${a.summary} [y/N] `)).trim().toLowerCase();
      rl.close();
      await engine.decideApproval(a.id, ans === "y" || ans === "yes" ? "approved" : "denied");
      continue;
    }
    if (t.status === "COMPLETED" || t.status === "FAILED" || t.status === "CANCELLED") {
      console.log(`result: ${t.result ?? t.error ?? ""}`);
      console.log(`verification: ${t.verification?.status ?? "not run"}`);
      for (const e of t.verification?.evidence ?? []) console.log(`  ${e.passed ? "PASS" : "FAIL"} ${JSON.stringify(e.criterion)} -> ${e.observed}`);
      return;
    }
    if (s.result !== "ran" || t.status !== "PENDING") {
      console.log(`Task is waiting (${t.status}). Run \`tick\` later, or \`approvals\`.`);
      return;
    }
  }
}

main().then(
  (code) => process.exit(code),
  (e: Error) => {
    console.error(`error: ${e.message}`);
    process.exit(1);
  },
);
