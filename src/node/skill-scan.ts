/**
 * The safety check made when a skill is installed, on its scripts. It is a fixed list of rules, not a model's opinion: a
 * model can be talked into calling anything safe, costs a request, and would send the code to a provider. A finding
 * means "a person should read this first"; no finding does not mean the script is harmless, only that none of these
 * patterns is in it. That is why scripts without a finding may run without a question only inside the Docker
 * sandbox (no network, read-only root, only the skill and a scratch folder visible), never on the computer itself.
 */

export type Severity = "block" | "note";

export interface Finding {
  file: string;
  line: number;
  severity: Severity;
  /** What was found, in a sentence a person can check against the line. */
  what: string;
}

export interface SafetyReport {
  /** "clean": no blocking finding, scripts may run without a question in Docker. "review": read it first. */
  level: "clean" | "review";
  findings: Finding[];
  /** How many script files were looked at. */
  scanned: number;
}

interface Rule {
  re: RegExp;
  severity: Severity;
  what: string;
}

const RULES: Rule[] = [
  // Hidden or generated code: the reader cannot see what runs.
  { re: /\b(?:eval|exec)\s*\(\s*(?:base64|atob|codecs\.decode|zlib\.decompress|bytes\.fromhex|Buffer\.from)/i, severity: "block", what: "runs decoded code (the text on screen is not what runs)" },
  { re: /\b(?:base64\s+(?:-d|--decode)|atob\()[^\n]*(?:\|\s*(?:ba)?sh|eval|exec)/i, severity: "block", what: "decodes text and runs it" },
  { re: /\b(?:curl|wget)\b[^\n|]*\|\s*(?:sudo\s+)?(?:ba|z)?sh\b/i, severity: "block", what: "downloads a program and runs it" },
  { re: /\b(?:eval|exec)\s*\(\s*(?:input|sys\.argv|process\.argv|req|request|fetch|urlopen)/i, severity: "block", what: "runs text that comes from outside" },
  { re: /\bnew\s+Function\s*\(/, severity: "block", what: "builds code from text while running" },
  { re: /\\x[0-9a-f]{2}(?:\\x[0-9a-f]{2}){24,}/i, severity: "block", what: "a long block of escaped bytes (hidden text)" },
  { re: /\b(?:eval|exec)\s*\(\s*(?:open|Path|pathlib|fs\.readFileSync|readFileSync)/i, severity: "block", what: "runs the contents of a file it reads (an input file could carry code)" },
  { re: /(?:__import__|importlib\.import_module|require|import)\s*\(\s*(?:sys\.argv|process\.argv|input\()/i, severity: "block", what: "loads code named by its arguments" },
  { re: /\beval\s+["']?\$[@*0-9{]|\b(?:ba|z)?sh\s+-c\s+["']?\$[@*0-9{]|\bsource\s+["']?\$[@*0-9{]/i, severity: "block", what: "runs text it was handed as a command" },
  // Reaching out of the sandbox or taking over the machine.
  { re: /docker\.sock|\/var\/run\/docker|\/proc\/(?:self|1)\/(?:root|ns|environ)|\/sys\/fs\/cgroup/i, severity: "block", what: "touches the container runtime or host internals" },
  { re: /\b(?:sudo|su\s+-|chmod\s+(?:[0-7]?777|[ugo]*\+s)|chown\s+root|setuid|insmod|modprobe|nsenter|unshare|mount\s+-)/i, severity: "block", what: "asks for more rights than a script needs" },
  { re: /\b(?:crontab|systemctl|launchctl|schtasks|reg\s+add|HKEY_)/i, severity: "block", what: "sets something up to run later or at startup" },
  { re: /(?:^|[\s"'`=])(?:~|\$HOME|%USERPROFILE%)?\/?\.(?:ssh|aws|gnupg|kube|config\/gcloud)\b|id_rsa|\.npmrc|\.netrc|credentials\.json/i, severity: "block", what: "reads keys or credentials" },
  // Things that only cost: a miner or a fork bomb.
  { re: /xmrig|stratum\+tcp|cryptonight|minerd|coinhive/i, severity: "block", what: "looks like a crypto miner" },
  { re: /:\(\)\s*\{\s*:\s*\|\s*:\s*&|\bwhile\s+true\s*;?\s*do\s*[^\n]*&\s*done|os\.fork\(\)\s*\n?\s*while|fork\(\)\s*;\s*fork\(\)/i, severity: "block", what: "starts processes without end" },
  // Worth knowing, not a reason to ask every time inside Docker.
  { re: /\b(?:requests|urllib|http\.client|httpx|aiohttp|socket|websocket)\b|\bfetch\s*\(|XMLHttpRequest|\bnet\.connect|\bcurl\b|\bwget\b|\bnc\s+-/i, severity: "note", what: "uses the network (blocked in the Docker sandbox)" },
  { re: /\b(?:subprocess|os\.system|os\.popen|child_process|spawn\(|execSync|Popen)\b/i, severity: "note", what: "starts other programs" },
  { re: /\b(?:os\.environ|process\.env|getenv|printenv)\b/i, severity: "note", what: "reads environment variables (empty inside the sandbox)" },
  { re: /\b(?:rm\s+-rf|shutil\.rmtree|fs\.rmSync|os\.remove|unlink)\b/i, severity: "note", what: "deletes files (only the scratch folder is writable in Docker)" },
];

/** Code-like files outside scripts/ are looked at too: a script can import or source them. */
const CODE_FILE = /\.(?:py|js|mjs|cjs|ts|sh|bash|zsh|ps1|bat|cmd|rb|pl|php|lua)$/i;

/** Looks at every file in scripts/ and every code-like file elsewhere in the skill (text only; a binary in scripts/ is a finding). */
export function scanSkillScripts(entries: { path: string; bytes: Buffer }[]): SafetyReport {
  const findings: Finding[] = [];
  let scanned = 0;
  for (const e of entries) {
    if (!e.path.startsWith("scripts/") && !(CODE_FILE.test(e.path) && e.path !== "SKILL.md")) continue;
    scanned++;
    if (e.bytes.includes(0)) {
      findings.push({ file: e.path, line: 0, severity: "block", what: "is a program or data file, not text a person can read" });
      continue;
    }
    const lines = e.bytes.toString("utf8").split(/\r?\n/);
    // A line that is very long is where something is usually hidden.
    lines.forEach((text, i) => {
      if (text.length > 400 && /[A-Za-z0-9+/=]{300,}/.test(text)) findings.push({ file: e.path, line: i + 1, severity: "block", what: "a very long unbroken line (hidden text)" });
      for (const r of RULES) {
        if (r.re.test(text) && !findings.some((f) => f.file === e.path && f.line === i + 1 && f.what === r.what)) findings.push({ file: e.path, line: i + 1, severity: r.severity, what: r.what });
      }
    });
  }
  const capped = findings.slice(0, 40);
  return { level: capped.some((f) => f.severity === "block") ? "review" : "clean", findings: capped, scanned };
}
