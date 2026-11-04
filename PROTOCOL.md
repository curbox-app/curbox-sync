# Curbox Sync WiFi protocol (v3)

Curbox Sync is an Android companion that keeps **focus mode** in step between Curbox on the phone
(through the public AIDL API, see `curbox/CURBOX_API.md`) and the Curbox browser extension, over
the local network. Browser extensions cannot listen on sockets, so the phone is the server.

## Transport

- HTTP/1.1 on port `47621`. The phone only serves private, link local and loopback peers.
- `GET /v1/hello` returns `{"app":"curbox-sync","proto":3,"features":["usage","net","pomodoro","groups","tailnet"]}` and is not
  authenticated. Proto 1 phones have no `usage` op and no `net` field; proto 2 phones have no Pomodoro and no
  `groups`/`saveGroups` ops.
- `POST /v1/rpc` takes a sealed request body and returns a sealed reply. `401` means the body did not
  open, the request was stale (±10 min clock window), or its `id` was already used.

## Pairing (short codes, `PairCode.kt` and `src/lib/lan/shortcode.ts`, which must stay byte identical)

The phone shows a 6 character code (Crockford base32, shown as `3Q2 W1S`). It carries the last two
parts of the phone's WiFi address and a 14 bit secret:

    value = third octet << 22 | fourth octet << 14 | secret     (30 bits, 6 chars, most significant first)

Typing normalizes: uppercase, `O`→`0`, `I`/`L`→`1`, `U`→`V`, everything else outside the alphabet dropped.

1. The browser (or another phone) decodes the code and asks `GET /v1/pair` at `<a>.<b>.<third>.<fourth>:47621`
   for every likely first half at once: its own network's, the phone's past ones, then `192.168`,
   `10.0`, `10.1`, `10.10`, `10.42`, `172.16`–`172.31`. The phone answers `{"pub": <SPKI P-256, base64url>}`,
   or 403 while Curbox Sync is not open on screen.
2. It makes its own P-256 key pair and sends `POST /v1/pair` with
   `{pub, proof: HMAC-SHA256(kCode, "b|" + phonePub + "|" + pub), from: {id, name, kind}}`,
   where `kCode = SHA-256("curbox-pair-v1|" + code)`.
3. The phone checks the proof against the code for each of its WiFi addresses (401 when none match;
   5 misses make a new code), then replies `{proof: HMAC(kCode, "p|..."), id, name}` and makes a new code.
4. Both sides take `key = HKDF-SHA256(ikm = ECDH x coordinate, salt = kCode, info = "curbox-lan-v2|" + phonePub + "|" + pub)`.
   The phone saves it as a browser key or, for `kind: "phone"`, a phone key (up to 8 each); the
   browser checks the phone's proof and keeps the key and address as its pairing.

Listening on the WiFi reveals nothing (the key comes from ECDH). The 14 bit secret only proves the
person typing sees the phone; it is guarded by the 5 tries and the app having to be open. Someone
actively intercepting traffic during that minute could still pose as either side.

Keys from the old QR pairing (the phone's own PBKDF2 code key and saved browser keys) keep working
until "Unpair all browsers".

## Crypto (`LanCrypto.kt` and `src/lib/lan/crypto.ts`, which must stay byte identical)

- Key: from short code pairing above (older pairings: PBKDF2-HMAC-SHA256 of a 12 char code, salt `curbox-lan-v1`, 100 000 iterations).
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
| `focus` | `focus`: `{active, groupId, name, endsAt, phase?, pomodoro?}` | Makes Curbox match; adds `result` |
| `groups` | `apps`?: boolean | Proto 3: adds `groups`, each with its `websites` list (and `packages` when `apps` is true) |
| `saveGroups` | `save`: `[{id, name, websites, blockMode, exitable, apps?}]`, `delete`: `[id]` (max 50 each) | Proto 3: creates, updates or removes phone groups; adds `results`: `{id: applied, noop, locked, unavailable or failed}` and `groups` |
| `usage` | `client`: `{id, name}`, `days`: `[{date, totalMs, sites:[{domain, ms}]}]` (max 14 days, 40 sites each) | Stores the days for that browser; adds `known`: `{date: totalMs}` |
| `phoneUsage` | `days`: Curbox `USAGE_HISTORY` days `[{date, screenTimeMs, apps:[{packageName,label,ms,launches}], websites:[{domain,ms}]}]` (max 14) | Phone to phone only (`from.kind: "phone"`): stores the days for that phone; adds `days` with its own recent days. Older phones answer `unknown_op` |
| `browsers` | `keys`: this phone's own browser keys (base64url, max 8) | Phone to phone only: replaces the browser keys that phone shared (they open requests here too); adds `keys` with its own |
| `unpair` | | Phone to phone only: drops the sender's key, saved address and usage, as if Forget was tapped here |

Reply plaintext: `{"ok", "now": phone epochMs, "phone": {connected, apiVersion, active, groupId, name,
endsAt, exitable, exact, changedAt, rev, groups:[{id,name,exitable,blockMode,apps}], caps, groupsHash, phase,
pomodoro?}, "result"?}`

Proto 3 fields: `caps` lists `pomodoro` and `groups` when Curbox has API 7 (empty otherwise). `phase` is
`focus`, `break` or `""`; `pomodoro` is `{phase, focusMinutes, breakMinutes, rounds, completed}` while a cycle
runs, and `endsAt` is then the end of the current phase. `groupsHash` changes whenever a group's name, mode,
exit rule or websites change, so the browser fetches `groups` only then.

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

With `pomodoro` in `caps`, a view also carries the phase and the cycle (rounds, completed rounds, focus
and break minutes), and two views match only when those agree too. Each device advances its own phases,
so when either side's session or phase ends within 15 s the round waits and looks again 20 s later
instead of pushing. A phone without `pomodoro` gets only the focus rounds, and breaks stop focus there.

Focus groups are shared before focus is reconciled (`src/lib/lan/groups.ts`), so a session always finds
its group. The extension keeps the last agreed copy of each linked group pair (`lan.groups`, per pairing
key). Groups pair by link, then id, then name. Sites added on either side are kept and sites removed on
either side are dropped; a field changed on one side takes that side's value (the browser wins a tie).
A group only one side has is created on the other the first time, and removed from the other once both
had agreed on it, unless it was edited meanwhile. If more than half of the linked groups vanish from one
side at once (another Curbox picked, a wiped browser), nothing is deleted and the groups are joined again.
A group running in a session that cannot be ended early is left as is until the session ends, and a
refused edit is tried again next round. Apps and Do Not Disturb never leave the phone.

Triggers: every local `focus` storage change, every focus group edit, the 30 s tick alarm, and a long poll loop while the
worker is awake.

## Curbox API 4

API 7 adds Pomodoro (`focus_phase` and the `pomodoro_*` fields, and the same as `START_FOCUS` arguments),
`FOCUS_GROUP_DETAILS`, `SAVE_FOCUS_GROUP`, `DELETE_FOCUS_GROUP` and a `FOCUS_GROUPS` listener event.

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

## Other phones

A phone can sync with other phones running Curbox Sync, with no browser involved. Phone B types
phone A's short code ("Add another phone"), pairs exactly as a browser does with `from.kind: "phone"`
(A's `id` and `name` come back in the reply), and from then on drives A over the same RPC, exactly like a browser
(`PeerSync.kt`, with the rules ported to Kotlin in `PeerLogic.kt`): a long `wait` on A, plus a round
whenever B's own Curbox changes. Every request B sends carries `from: {id, name, kind: "phone"}`, so A
lists B under "Other phones".

- Focus and Pomodoro reconcile as above. Cycles are shared when both phones run Curbox API 7.
- Focus groups merge as above, and between phones the app lists merge too (`groups` and
  `saveGroups` with `apps: true`, written through `SAVE_FOCUS_GROUP`'s `apps`). Do Not Disturb stays per phone.
- When A's address changes, B tries A's saved addresses (one per network, by `net.id`), then sweeps its
  own /24 ranges for `/v1/hello` at most every 2 minutes.
- If both phones paired with each other, the one with the larger device id stops driving while the other
  one drives it, so the pair is not reconciled twice.
- Every link reconciles against its own phone's Curbox, so a browser paired with A also stays in step
  with B through A.
- "Unpair all browsers" on A also unpairs phones; B then needs A's new code.

## Tailscale

When Tailscale is on, the phone adds `"tailnet": "100.x.y.z:47621"` to every reply (feature `tailnet`)
and its server also accepts peers in 100.64.0.0/10. The extension saves it on the pairing
(`lan.config.tailnet`, not a place), tries it first when the WiFi address stops answering, and every
5 min sends a plain `/v1/hello` there to tell the panel whether sync would work off this WiFi. While
reached over Tailscale, places are recorded with the phone's own `net.address`. Phone to phone: peers
keep it under the `tailnet` place key. Short codes need a shared WiFi for the first pairing.

### Usage and unpairing between phones

The driving phone swaps usage with `phoneUsage` every 10 minutes (and when its app opens), so each phone's
Usage screen adds the other phone's screen time, apps and sites to its own (apps merge by package). Forget on
either phone removes that phone everywhere on this side: its engine, its saved address, the key it used
(phones remember which key each device id used, learned at pairing or on its next request), and its usage.
When this phone drove the pair it also sends `unpair`, so the other phone forgets it too. A phone whose key
no longer opens gets 401 and says it was unpaired. Unpair all also forgets the phones this one paired with.

### Browser chain between phones

Paired phones share their browsers: each sends its own browser keys with `browsers` (never ones it was given),
so a browser paired with phone A also opens requests on phone B. When A is unreachable, the extension's usual
roam (saved addresses, then a sweep with the paired key) finds B and carries on there, without a new code.
Every reply to a phone carries `browsersHash`; when it changes the driving phone swaps keys again, so a new
browser or Unpair all reaches the other phone within one wait. `phoneUsage` also carries `browsers`
(`[{id, name, lastSeen, days}]`, the browsers that sent their days to that phone itself); a relayed day keeps
the larger total. Every reply carries `device: {id, name}`; the extension keeps its group links and usage
totals per answering phone (`lan.device`), since two phones' group ids and stored totals differ.
