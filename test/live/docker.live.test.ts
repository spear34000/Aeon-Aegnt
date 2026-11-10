// REAL Docker sandbox test. SKIPPED unless AEON_DOCKER_TESTS=1 (it pulls alpine:3.20 on first run).
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { WorkspaceGuard } from "../../src/node/fs-guard.ts";
import { sandboxTool } from "../../src/node/tools/sandbox-tool.ts";
import { tempDir } from "../helpers.ts";

const enabled = process.env.AEON_DOCKER_TESTS === "1";

describe("Docker sandbox (real daemon)", { skip: enabled ? false : "set AEON_DOCKER_TESTS=1 (needs a running Docker daemon)" }, () => {
  it("runs a command with no network access", async () => {
    const { dir, cleanup } = tempDir();
    const tool = sandboxTool(new WorkspaceGuard(dir));
    const ok = await tool.run({ image: "alpine:3.20", command: ["echo", "hello-from-sandbox"] }, { taskId: "t_live", idempotencyKey: "k" });
    assert.equal(ok.ok, true, ok.output);
    assert.match(ok.output, /hello-from-sandbox/);
    const net = await tool.run({ image: "alpine:3.20", command: ["wget", "-q", "-T", "3", "-O", "-", "http://example.com"] }, { taskId: "t_live", idempotencyKey: "k2" });
    assert.equal(net.ok, false, "network must be unreachable inside the sandbox");
    cleanup();
  });
});
