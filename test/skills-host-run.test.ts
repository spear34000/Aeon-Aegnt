import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { WorkspaceGuard } from "../src/node/fs-guard.ts";
import { SkillLibrary } from "../src/node/skill-library.ts";
import { nodeHostRunner, permissionFlag, scrubbedEnv } from "../src/node/tools/host-script-runner.ts";
import type { SandboxRunner } from "../src/node/tools/sandbox-tool.ts";
import { DEFAULT_SANDBOX } from "../src/node/tools/sandbox-tool.ts";
import { skillRunTool } from "../src/node/tools/skill-run-tool.ts";
import { skillTools } from "../src/node/tools/skill-tools.ts";
import { tempDir } from "./helpers.ts";

const NL = String.fromCharCode(10);
const ctx = { taskId: "task9", idempotencyKey: "k" };
const noDocker: SandboxRunner = { async run() { return { code: 1, stdout: "", stderr: "Cannot connect to the Docker daemon" }; } };

/** A trusted skill in the owner's folder with the given scripts. */
function skillWith(scripts: Record<string, string>) {
  const t = tempDir();
  const ws = join(t.dir, "ws");
  mkdirSync(ws, { recursive: true });
  const lib = new SkillLibrary({ userDir: join(t.dir, "cfg", "skills"), projectDir: join(ws, ".agents", "skills"), workspace: ws, lockFile: join(t.dir, "cfg", "skills-lock.json") });
  const dir = join(lib.userDir, "tools-skill");
  mkdirSync(join(dir, "scripts"), { recursive: true });
  writeFileSync(join(dir, "SKILL.md"), ["---", "name: tools-skill", "description: Runs tools. Use for tests.", "---", "", "Run scripts/probe.js.", ""].join(NL));
  for (const [n, c] of Object.entries(scripts)) writeFileSync(join(dir, "scripts", n), c);
  lib.trust("user", "tools-skill", lib.get("user", "tools-skill")!.hash);
  return { t, ws, lib, dir };
}

/** The probe script receives paths as base64, so no text of a path is ever spliced into code. */
const b64 = (p: string): string => Buffer.from(p).toString("base64");

describe("running a script on this computer (D21)", () => {
  const supported = permissionFlag() !== null;

  it("the card says in words how much holds the script back: Node permission model for JavaScript, nothing for Python and shell", () => {
    const { t, ws, lib } = skillWith({ "probe.js": "console.log(1)", "tidy.py": "print(1)", "go.sh": "echo 1" });
    const tool = skillRunTool(lib, new WorkspaceGuard(ws), noDocker, DEFAULT_SANDBOX, nodeHostRunner);
    const js = tool.assess({ skill: "tools-skill", script: "scripts/probe.js", isolation: "host", args: ["a b"] });
    assert.match(js.summary, /on this computer \(Node permission model\)/);
    assert.ok(js.detail!.includes("NOT blocked") && js.detail!.includes("safety net") && js.detail!.includes("NOT a guarantee against hostile code"));
    assert.ok(js.detail!.includes('node scripts/probe.js "a b"'), "arguments are quoted");
    for (const f of ["tidy.py", "go.sh"]) {
      const a = tool.assess({ skill: "tools-skill", script: `scripts/${f}`, isolation: "host" });
      assert.match(a.summary, /WITHOUT isolation/);
      assert.ok(a.detail!.includes("Isolation: NONE"));
      assert.ok(a.resources.includes("isolation:host"), "the choice is part of what was approved");
    }
    assert.equal(tool.assess({ skill: "tools-skill", script: "scripts/probe.js" }).resources.includes("isolation:docker"), true, "docker is the default");
    t.cleanup();
  });

  it("an argument cannot fake lines of the card, and host runs can be switched off", () => {
    const { t, ws, lib } = skillWith({ "probe.js": "console.log(1)" });
    const tool = skillRunTool(lib, new WorkspaceGuard(ws), noDocker, DEFAULT_SANDBOX, nodeHostRunner);
    for (const bad of [`x${NL}Isolation: Docker, harmless`, "x y", "x\u0000y"]) assert.match(tool.assess({ skill: "tools-skill", script: "scripts/probe.js", args: [bad] }).violation ?? "", /single-line/);
    const off = skillRunTool(lib, new WorkspaceGuard(ws), noDocker, DEFAULT_SANDBOX, null);
    assert.match(off.assess({ skill: "tools-skill", script: "scripts/probe.js", isolation: "host" }).violation ?? "", /switched off/);
    assert.match(tool.assess({ skill: "tools-skill", script: "scripts/probe.js", isolation: "elsewhere" }).violation ?? "", /isolation must be/);
    t.cleanup();
  });

  it("without Docker the owner is told how to ask again, and nothing falls back to the computer by itself", async () => {
    const { t, ws, lib } = skillWith({ "probe.js": "console.log(1)" });
    const tool = skillRunTool(lib, new WorkspaceGuard(ws), noDocker, DEFAULT_SANDBOX, nodeHostRunner);
    const r = await tool.run({ skill: "tools-skill", script: "scripts/probe.js" }, ctx);
    assert.equal(r.ok, false);
    assert.ok(r.output.includes('isolation "host"'));
    t.cleanup();
  });

  it("JavaScript runs under Node's permission model: own files and scratch only, no programs, no secrets in the environment", { skip: !supported && "this Node has no permission model" }, async () => {
    const outside = tempDir();
    writeFileSync(join(outside.dir, "secret.txt"), "private");
    process.env.AEON_SECRET_FOR_TEST = "hunter2";
    const probe = [
      'const fs = require("node:fs"); const path = require("node:path");',
      "const out = {};",
      "const attempt = (name, f) => { try { out[name] = f(); } catch (e) { out[name] = e.code || String(e).slice(0, 40); } };",
      'attempt("env", () => process.env.AEON_SECRET_FOR_TEST || "absent");',
      'attempt("ownSkillFile", () => fs.readFileSync(path.join(__dirname, "..", "SKILL.md"), "utf8").length > 0);',
      `attempt("outsideRead", () => fs.readFileSync(Buffer.from("${b64(join(outside.dir, "secret.txt"))}", "base64").toString(), "utf8"));`,
      `attempt("outsideWrite", () => fs.writeFileSync(Buffer.from("${b64(join(outside.dir, "planted.txt"))}", "base64").toString(), "x"));`,
      'attempt("scratchWrite", () => { fs.writeFileSync("made.txt", "ok"); return fs.readFileSync("made.txt", "utf8"); });',
      'attempt("spawn", () => require("node:child_process").spawnSync("node", ["-v"]).status);',
      "console.log(JSON.stringify(out));",
    ].join(NL);
    const { t, ws, lib } = skillWith({ "probe.js": probe });
    const tool = skillRunTool(lib, new WorkspaceGuard(ws), noDocker, DEFAULT_SANDBOX, nodeHostRunner);
    const args = { skill: "tools-skill", script: "scripts/probe.js", isolation: "host" };
    assert.equal(tool.assess(args).violation, undefined);
    const r = await tool.run(args, ctx);
    const line = r.output.split(NL).find((l) => l.startsWith("{"));
    assert.ok(line, r.output);
    const got = JSON.parse(line!) as Record<string, unknown>;
    assert.deepEqual(got, {
      env: "absent", ownSkillFile: true, outsideRead: "ERR_ACCESS_DENIED", outsideWrite: "ERR_ACCESS_DENIED", scratchWrite: "ok", spawn: "ERR_ACCESS_DENIED",
    });
    assert.ok(!existsSync(join(outside.dir, "planted.txt")));
    delete process.env.AEON_SECRET_FOR_TEST;
    outside.cleanup();
    t.cleanup();
  });

  it("a script that never ends is stopped, and one that prints without end is cut off", { skip: !supported && "this Node has no permission model" }, async () => {
    const { t, ws, lib } = skillWith({ "probe.js": "for (;;) {}", "flood.js": 'for (;;) process.stdout.write("x".repeat(100000));' });
    const tool = skillRunTool(lib, new WorkspaceGuard(ws), noDocker, { ...DEFAULT_SANDBOX, timeoutMs: 1500 }, nodeHostRunner);
    const slow = await tool.run({ skill: "tools-skill", script: "scripts/probe.js", isolation: "host" }, ctx);
    assert.equal(slow.ok, false);
    assert.ok(slow.output.includes("stopped after 1.5 seconds"));
    const flood = await tool.run({ skill: "tools-skill", script: "scripts/flood.js", isolation: "host" }, ctx);
    assert.equal(flood.ok, false);
    assert.ok(flood.output.includes("output limit") || flood.output.includes("stopped after"));
    t.cleanup();
  });

  it("the environment a script gets holds none of the owner's variables", () => {
    process.env.AEON_SECRET_FOR_TEST = "hunter2";
    const env = scrubbedEnv("/scratch");
    assert.ok(!JSON.stringify(env).includes("hunter2"));
    assert.deepEqual(Object.keys(env).filter((k) => !["PATH", "HOME", "TMPDIR", "LANG", "TEMP", "TMP", "USERPROFILE", "SystemRoot", "SYSTEMROOT", "windir", "PATHEXT"].includes(k)), []);
    delete process.env.AEON_SECRET_FOR_TEST;
  });
});

describe("approval cannot be laundered through a change of a skill, and a hand-made folder cannot hide in the project", () => {
  it("a change to a skill whose other files were altered is saved but not trusted; a change to an untouched one stays trusted", async () => {
    const { t, lib, dir } = skillWith({ "probe.js": "console.log('approved')" });
    const update = skillTools(lib).find((x) => x.name === "skill.update")!;
    const spec = { name: "tools-skill", description: "Runs tools. Use for tests.", body: "Run scripts/probe.js carefully." };
    const fine = await update.run(spec, ctx);
    assert.ok(fine.output.includes("Changed skill"), fine.output);
    assert.equal(lib.get("user", "tools-skill")!.active, true, "an approved change keeps the skill trusted");

    writeFileSync(join(dir, "scripts", "probe.js"), "console.log('tampered')");
    assert.equal(lib.get("user", "tools-skill")!.status, "modified");
    const laundered = await update.run({ ...spec, body: "Run scripts/probe.js, really." }, ctx);
    assert.ok(laundered.output.includes("NOT available"), laundered.output);
    assert.deepEqual([lib.get("user", "tools-skill")!.status, lib.catalog().length], ["modified", 0], "the owner never saw the tampered script, so it stays unapproved");
    t.cleanup();
  });

  it("trust needs the hash of what was shown, so a folder that changed after it was shown is not approved", () => {
    const { t, ws, lib } = skillWith({ "probe.js": "1" });
    const sub = join(ws, ".agents", "skills", "dropped");
    mkdirSync(sub, { recursive: true });
    writeFileSync(join(sub, "SKILL.md"), ["---", "name: dropped", "description: Dropped here. Use never.", "---", "", "x", ""].join(NL));
    const shown = lib.get("project", "dropped")!.hash;
    writeFileSync(join(sub, "SKILL.md"), ["---", "name: dropped", "description: Dropped here. Use never.", "---", "", "changed after it was shown", ""].join(NL));
    assert.throws(() => lib.trust("project", "dropped", shown), /changed since you looked/);
    assert.equal(lib.get("project", "dropped")!.active, false);
    assert.equal(lib.trust("project", "dropped", lib.get("project", "dropped")!.hash).active, true);
    t.cleanup();
  });

  it("the agent's file tools cannot write into .agents (where project skills live)", () => {
    const { t, ws } = skillWith({});
    const guard = new WorkspaceGuard(ws);
    for (const p of [".agents/skills/x/SKILL.md", ".agents/skills/x/scripts/run.sh"]) {
      const r = guard.resolve(p);
      assert.equal(r.ok, false, p);
    }
    assert.equal(guard.resolve("notes/a.md").ok, true);
    assert.ok(readFileSync(join(ws, "..", "cfg", "skills", "tools-skill", "SKILL.md"), "utf8").length > 0);
    t.cleanup();
  });
});
