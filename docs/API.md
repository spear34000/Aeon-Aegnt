# Local API (v1)

Used by the web, Windows and Android UIs. Start it with `node src/node/cli.ts serve [--host H] [--port N] [--web DIR]` (default `127.0.0.1:7421`).
Loopback by default. Binding a LAN address is opt-in and prints a warning: requests are **signed and replay-protected but not encrypted** (plain HTTP). Remote/cloud access arrives with the Phase 2 HTTPS runtime and the same API.

## Pairing (decision D6)

1. The owner sees a one-time code in the server console (`pair` + Enter for a new one): 19 base32 characters (95 random bits), valid 5 minutes, single use. 5 wrong attempts burn all codes and lock pairing for 60 s.
2. The device creates an **ECDSA P-256** key pair (Android Keystore / WebCrypto non-extractable / JVM) and calls
   `POST /v1/pair` with `{ deviceName, publicKey (base64 SPKI DER), clientNonce (base64, 16+ bytes), proof }`
   where `proof = base64(HMAC-SHA256(key = code (normalised: letters/digits only, upper case), "aeon-pair-client|" + deviceName + "|" + publicKey + "|" + clientNonce))`.
3. Response `201 { deviceId, serverProof, serverName }`. The device must check
   `serverProof == base64(HMAC-SHA256(code, "aeon-pair-server|" + deviceId + "|" + clientNonce + "|" + publicKey))`.
   The code itself never crosses the network, and an eavesdropper can neither learn it nor impersonate the server.

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
| `GET/PUT /v1/config` | non-secret settings (strictly validated; no secret fields). Changing providers, consent, fallback or endpoint URLs is accepted **only from the machine running Aeon** |
| `GET /v1/secrets` · `PUT/DELETE /v1/secrets/{NAME}` | names only; values are write-only and accepted **only from the machine running Aeon** (loopback), never over the LAN |
| `GET /v1/devices` · `DELETE /v1/devices/{id}` | list (no keys) · revoke |

Static files (the web UI, when `--web` is given) are served without authentication because they are public code; they carry CSP, `X-Frame-Options: DENY` and `nosniff`.

## Known limits

Pairing codes are 80 bits (16 base32 characters, 4-4-4-4 display); the 5-attempt lockout limits guessing.

No transport encryption on plain HTTP; one owner, all paired devices have full control; SSE polls the audit table once per second; no push notifications yet (Phase 6).
