# Curbox Sync WiFi protocol (v2)

Curbox Sync is an Android companion that keeps **focus mode** in step between Curbox on the phone
(through the public AIDL API, see `curbox/CURBOX_API.md`) and the Curbox browser extension, over
the local network. Browser extensions cannot listen on sockets, so the phone is the server.

## Transport

- HTTP/1.1 on port `47621`. The phone only serves private, link local and loopback peers.
- `GET /v1/hello` returns `{"app":"curbox-sync","proto":2,"features":["usage","net"]}` and is not authenticated.
  Proto 1 phones have no `usage` op and no `net` field.
- `POST /v1/rpc` takes a sealed request body and returns a sealed reply. `401` means the body did not
  open, the request was stale (±10 min clock window), or its `id` was already used.

## Pairing

The browser cannot accept connections, so the browser shows the code and finds the phone itself:

1. The extension makes a fresh 12 char code and shows `curboxsync://pair?c=<CODE>` as a QR code.
2. CurboxSync scans it ("Scan browser code", or any camera app opening the link) and adds that code's
   key to its list of browser keys (up to 8; the phone's own typed code also stays valid).
3. Meanwhile the extension sweeps /24 ranges for `GET /v1/hello` (64 parallel probes, 1.5 s each),
   likeliest first: this computer's range when WebRTC reveals it, the last paired address, then common
   home router ranges. The likely ranges are revisited every other round. Each phone found is then
   tried with a sealed `state` request until it opens it (after the scan), and that address is kept.
4. The pending code expires after 10 minutes.

Typing the phone's address and its own code still works as a fallback.

## Crypto (`LanCrypto.kt` and `src/lib/lan/crypto.ts`, which must stay byte identical)

- Pairing code: 12 chars from `A–Z2–7`, shown as `ABCD EFGH JKLM`. Normalization: uppercase, keep only alphabet chars.
- Key: PBKDF2-HMAC-SHA256(code, salt `curbox-lan-v1`, 100 000 iterations, 32 bytes).
- Blob: base64url without padding of `nonce[12] || AES-256-GCM(ciphertext || tag)`.
- AAD: `curbox-lan-v1|req` for requests, `curbox-lan-v1|res|<request id>` for replies, so each reply
  is bound to the request it answers.
- The shared test vector lives in `LanCryptoTest.kt` and `crypto.test.ts`.

## Messages

Request plaintext: `{"id": hex, "ts": epochMs, "op": ..., ...}`

| op | Extra fields | Phone behavior |
| --- | --- | --- |
| `state` | | Re-reads Curbox and replies |
| `wait` | `since`: rev, `wait`: ms (max 25 000) | Replies as soon as `rev != since`, or when the wait ends |
| `focus` | `focus`: `{active, groupId, name, endsAt}` | Makes Curbox match; adds `result` |
| `usage` | `client`: `{id, name}`, `days`: `[{date, totalMs, sites:[{domain, ms}]}]` (max 14 days, 40 sites each) | Stores the days for that browser; adds `known`: `{date: totalMs}` |

Reply plaintext: `{"ok", "now": phone epochMs, "phone": {connected, apiVersion, active, groupId, name,
endsAt, exitable, exact, changedAt, rev, groups:[{id,name,exitable}]}, "result"?}`

From proto 2 every reply also carries `"net": {id, label, address}`: the network the phone is on
(`id` is its range and router, for example `192.168.1.0/24@192.168.1.1`, so it needs no location
permission), the name the user gave it, and the phone's address there.

`result` is one of `applied`, `noop`, `no_group` (no phone group with that id or name), `locked`
(the phone session cannot be ended early), `unavailable` or `failed`.

## Reconciliation (extension side, `src/lib/lan/reconcile.ts`)

The extension remembers the last agreed `{local, phone}` view. On each round it checks which side
moved since then: only local moved means push, only phone moved means pull, both moved means the newer
change wins (`phone.changedAt` corrected by clock skew vs `lan.localChangedAt`). Two views are the same
when both are off, or the group matches (id, else name, case insensitive) and the ends are within
90 s (Curbox before API 4 only reports whole minutes). A `locked` push makes the browser follow the
phone. An unmatched group is reported to the user and left as is, without retrying.

Triggers: every local `focus` storage change, the 30 s tick alarm, and a long poll loop while the
worker is awake.

## Curbox API 4

Curbox Sync works with API 3, but API 4 (added for this) gives exact sync: `FOCUS_ACTIVE` adds
`focus_group_id` and `focus_ends_at`, and `START_FOCUS` takes an optional `ends_at`.

API 6 lets the phone side run lighter. Curbox Sync registers a change listener, so it reads Curbox
when focus changes (plus once at the session end and every 5 min as a safety net) instead of every
2 to 15 s. If the app is excluded from battery optimization it also calls `HOST_COMPANION`: Curbox's
blocker service binds `CompanionService`, which keeps the process alive, and the foreground service
and its notification stop. If Curbox unbinds, the foreground service starts again. On older Curbox
versions, nothing changes.

## Several networks

The phone keeps each network a browser synced on (`networks.json`, up to 12) and the user can name
or forget them. The extension keeps `lan.places`: the phone's address on each network, by `net.id`.
When the paired address stops answering, the extension first tries every saved address, then sweeps
the saved networks' ranges and the common ones with the paired key, and moves to the address that
opens it. One range is swept per failed round, and after a full pass it waits 5 minutes before the
next. Pairing and the last agreed focus view are kept the whole time, so a change made while away is
reconciled as soon as the devices meet again on any network.

## Usage

The extension sends its daily site totals with `usage` (28 days kept, up to 7 days per request, at
most once a minute) and keeps the phone's `known` map, resending any day whose total differs, so a
reinstalled phone is refilled. Curbox Sync reads the phone's own history through Curbox API 5
`list("USAGE_HISTORY")` (only today's total from `SCREENTIME_TODAY` on older Curbox) and shows both
together. Usage never leaves the phone.
