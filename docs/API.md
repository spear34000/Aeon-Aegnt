# Local API (v1)

Used by the web, Windows and Android UIs. Start it with `node src/node/cli.ts serve [--host H] [--port N] [--web DIR]` (default `127.0.0.1:7421`).
Loopback by default. Binding a LAN address is opt-in and prints a warning: requests are **signed and replay-protected but not encrypted** (plain HTTP). Remote/cloud access arrives with the Phase 2 HTTPS runtime and the same API.

## Pairing (decision D6)

1. The owner sees a one-time code in the server console (`pair` + Enter for a new one): 16 base32 characters (80 random bits, shown 4-4-4-4), valid 5 minutes, single use. 5 wrong attempts burn all codes and lock pairing for 60 s. When the server listens beyond this machine and the console is a terminal, it also prints a QR code (see Pairing link).
2. The device creates an **ECDSA P-256** key pair (Android Keystore / WebCrypto non-extractable / JVM) and calls
   `POST /v1/pair` with `{ deviceName, publicKey (base64 SPKI DER), clientNonce (base64, 16+ bytes), proof }`
   where `proof = base64(HMAC-SHA256(key = code (normalised: letters/digits only, upper case), "aeon-pair-client|" + deviceName + "|" + publicKey + "|" + clientNonce))`.
3. Response `201 { deviceId, serverProof, serverName }`. The device must check
   `serverProof == base64(HMAC-SHA256(code, "aeon-pair-server|" + deviceId + "|" + clientNonce + "|" + publicKey))`.
   The code itself never crosses the network, and an eavesdropper can neither learn it nor impersonate the server.

**Pairing link.** The QR code (console, and a paired device's "새 기기 연결" dialog) holds `aeon://pair?u=<address>[&u=<address>…]&c=<code>`: up to four addresses (`http(s)://host[:port]`, no credentials, path, query or fragment; `192.168.x` first, then `10.x`, `172.16–31.x`, `100.64/10`, others; `169.254.x` dropped) and the 16-character code. A phone's camera opens the link in the Aeon app (Android intent filter). **A link only fills in the connect screen**: nothing is sent until the owner taps connect, links are refused on a device that is already paired, and the app only probes (`GET /v1/hello`) addresses on a home or office network. Any app on a phone can send such a link, so every part is validated and unsafe parts are dropped. The encoder is `src/core/qr.ts` (byte mode, error correction M, versions 1–10, at most 213 bytes), with a Kotlin port (`api/Qr.kt`) that is tested module for module against the same vectors.

## Signing every other request

Headers: `X-Aeon-Device`, `X-Aeon-Timestamp` (unix ms, ±60 s), `X-Aeon-Nonce` (8–64 chars, never reused), `X-Aeon-Signature` = base64 ECDSA-SHA256 (DER, or 64-byte IEEE-P1363 as WebCrypto produces) over
```
AEON-V1\n<METHOD>\n<path+query exactly as sent>\n<timestamp>\n<nonce>\n<hex sha256 of the raw body ("" for none)>
```
Any failure returns `401 {"error":"unauthorized"}` with no hint which check failed. Requests containing `..` path segments return 400.

## Endpoints

| Method & path | Purpose |
|---|---|
| `GET /v1/hello` (no auth) | name, api version |
| `POST /v1/pair` (no auth) | pairing, above |
| `GET /v1/status` | task counts, secret backend name, provider health, `setupProblem` (e.g. missing key) |
| `GET /v1/tasks` · `POST /v1/tasks` | list (summaries) · create `{ goal, criteria?[], runAfter? }` |
| `GET /v1/tasks/{id}` | detail incl. plan, steps, verification evidence |
| `POST /v1/tasks/{id}/cancel` · `/verify` · `/confirm` | cancel · re-verify against reality · owner confirmation |
| `GET /v1/approvals?status=pending` · `POST /v1/approvals/{id}/decide` `{decision:"approved"\|"denied"}` | approvals; each can be decided once. Each approval carries `resources` (paths/URLs, shown first 3) and `irreversible` (true for permanent deletes, overwrites, command runs, external sends) |
| `GET /v1/events` (SSE) | audit events; resume with `Last-Event-ID` or `?after=` |
| `GET /v1/usage` | model calls and tokens |
| `GET/PUT /v1/config` | non-secret settings (strictly validated; no secret fields). Changing providers, consent, fallback or endpoint URLs is accepted from the machine itself and from devices allowed to change settings (see Models, devices and files) |
| `GET /v1/secrets` · `PUT/DELETE /v1/secrets/{NAME}` | names only; values are write-only and accepted from the machine itself and from devices allowed to change settings (see Models, devices and files); over the network they must be sealed |
| `GET /v1/devices` · `DELETE /v1/devices/{id}` | list (no keys) · revoke |
| `GET /v1/connections` | what exists right now: built-in tools grouped by namespace (from the running tool registry), MCP servers saved in `config.mcpServers` (state `added`), and skill folders under `<config dir>/skills/*/SKILL.md`. Also `skillsDir` and `canEdit` (true only for the machine running Aeon) |
| `GET /v1/skills` · `GET /v1/skills/{name}?scope=` · `PUT /v1/skills/{name}` `{description, body, license?, compatibility?, metadata?}` · `DELETE /v1/skills/{name}?scope=` | skills in the Agent Skills format from both scopes (`user`: `<config dir>/skills`, `project`: `<workspace>/.agents/skills`). Each row has `scope`, `status` (`tracked`, `modified`, `untracked`), `enabled`, `active` (offered to the model), `inactiveBecause`, `shadowedBy`, `source` (`agent`, `legacy`, `local`, `github` with commit), `hash`, `version`, `scripts`, plus `name`, `description`, `license`, `compatibility`, `metadata`, `allowedTools`, `files` (under `references/`, `assets/`, `scripts/`), `problems` (the SKILL.md does not follow the spec) and `warnings` (an unknown field, a file the instructions point to that is missing). **Skills are created by the agent** (`skill.create`, approved by the owner) or installed by the owner (below); `PUT` corrects a skill in the owner's scope (it stays trusted), `DELETE` removes it and its lock entry. Changing needs the right to change settings; writes are atomic and never follow links |
| `POST /v1/skill-sources/inspect` `{source}` · `POST /v1/skill-sources/install` `{previewId, name, scope, rename?, replace?}` · `GET /v1/skill-sources/updates` · `POST /v1/skills/{name}/update-preview` `{scope}` · `POST /v1/skills/{name}/enable` `{scope, enabled}` · `POST /v1/skills/{name}/trust` `{scope}` | the owner's acts, all need the right to change settings (D20). `inspect` reads a source (`owner/repo[/path][@ref]`, a github.com URL, or a folder on the machine) and returns a preview: every skill found with the whole SKILL.md, the text of each script, the file list, the hash, problems, and where the same name already exists. It installs nothing; the preview lasts 15 minutes. `install` installs one skill from it into `user` or `project`; `409` if the name exists (send `rename` or `replace`). `updates` asks GitHub whether a newer commit exists (needs `onlineLookups`); `update-preview` returns a preview plus the file diff against what is installed. `enable` switches a trusted skill on or off (`409` if it is not approved). `trust` approves a skill that is already in a folder, as it is now |
| `GET /v1/catalog?q=TEXT` · `?collection=ID` · `GET /v1/catalog/collections` | what can be added: a live search of the official MCP registry made by the PC (5-minute cache). Entries with a hosted endpoint are `integration`, package-only entries are `mcp`. A collection is a list of search terms from `catalog/collections.json` (or `<config dir>/collections.json`), not a list of entries. `502` when the registry cannot be reached |
| `GET /v1/icons/{key}` | icon for a connection (`icon` field): `{kind:"image",mime,data}` (PNG/JPEG/WebP/GIF, `.ico` converted) or `{kind:"path",d,color}` (one SVG path on a 24 grid). `404` when none was found |

Static files (the web UI, when `--web` is given) are served without authentication because they are public code; they carry CSP, `X-Frame-Options: DENY` and `nosniff`.

## Models, devices and files

| Method & path | Purpose |
|---|---|
| `GET /v1/providers` | providers from the catalogue (built-ins plus models.dev, cached a day) with address, `hasKey`, `local`, reasoning levels, `icon` (an `/v1/icons/…` path for the provider's logo, null when internet lookups are declined) and the model in use. `GET /v1/providers/{id}/models` lists one provider's models |
| `POST /v1/model` `{provider, model, reasoningEffort?, consent?, custom?}` | puts the model first in line. A provider outside this machine needs `consent: true` once, and again if its address changed. Needs the right to change settings |
| `PUT /v1/secrets/{NAME}` `{sealed}` | keys arrive sealed (see below); a plain `{value}` is accepted from the machine itself only |
| `POST /v1/pairing-codes` `{admin?, epk}` | a paired device lets another in. The code comes back sealed for `epk` |
| `PATCH /v1/devices/{id}` `{name?, admin?}` | rename, or allow/forbid changing settings. A device can always remove itself |
| `POST /v1/files?name=` (raw body, 20 MB) | stores the file as `<workspace>/uploads/<time>-<name>`; the app then names the path in the message |

Sealing ("AEON-SEAL-V1", `src/node/server/seal.ts`): the server has an ECDH P-256 key; a device pins its public half while pairing (vouched for by the pairing code, `serverKeyProof`) and fails closed away from loopback if it is missing. A box is a one-time ECDH key, AES-256-GCM, and key = SHA-256(label, direction, shared secret, one-time key, server key), with associated data `secret|<device>|<NAME>` or `pairing-code|<device>`.

Devices have an `admin` flag (default true): without it a device keeps chat and approvals but cannot change settings, keys, connections or devices. API keys for catalogue providers are stored under `AEON_EP_<ID>_KEY`, a name Aeon derives from the provider id and nothing else uses.

`config.profile` (`userName`, `agentName`, `tone`, `detail`, `language`, `interests` (up to 12 labels), `about`) is added to the system prompt as wording preferences only.

`config.onlineLookups: false` records that the owner declined internet lookups for lists and logos: `/v1/catalog` then answers `{connections: [], disabled: true}`, `/v1/icons` answers 404 and `/v1/providers` serves built-ins plus the last cached catalogue with `online: false`. Changing it needs the right to change settings.

## Connections

Nothing in the connection list is hard-coded in a UI. `config.mcpServers` holds `{ name, title?, url (https, no credentials), websiteUrl?, requires?[] }`; changing it needs the right to change settings, like providers. **Aeon does not run MCP tools yet:** an added server is stored and listed, nothing more. Package-based (stdio) servers cannot be added because host command execution is not allowed (D7). **Skills are used by the agent** (D17): names and descriptions are in its system prompt, `skill.read` opens one on demand, and `skill.create` / `skill.update` propose a new or changed one (the approval carries `detail`, the full text). They only exist where Aeon runs; an added MCP server is still stored and listed only.

Outbound requests made by the PC for this feature (never by a paired device): `registry.modelcontextprotocol.io` (search text), `cdn.jsdelivr.net` (Simple Icons brand index and one SVG per matched title), the publisher's website (page and icon), `github.com/<owner>.png`, and `models.dev/logos/<provider id>.svg` for model provider logos. All go through the SSRF-guarded transport. Icons are cached in `<config dir>/cache/icons` for 30 days (misses for one day).

Provider logos come from `models.dev/logos/<id>.svg` only. The reader (`logoFromSvg`) accepts a square canvas of filled `<path>` elements in `currentColor` and returns `{kind:"path", d, color:"currentColor", size, rule?}`; the catalogue serves a stroked stand-in for providers without a logo and logos made of other shapes, and both are refused, so the app shows the provider's first letter. Icons for providers are drawn in the text colour, so one logo serves light and dark.

Icon order: icons declared in the publisher's manifest, then a brand mark whose name equals the start of the title, then the publisher's site icon, then its GitHub avatar. A community server named after a brand therefore shows that brand's mark; the row also shows the publisher so it is not mistaken for an official one.

## Known limits

Pairing codes are 80 bits (16 base32 characters, 4-4-4-4 display); the 5-attempt lockout limits guessing.

No transport encryption on plain HTTP; one owner, all paired devices have full control; SSE polls the audit table once per second; no push notifications yet (Phase 6).
