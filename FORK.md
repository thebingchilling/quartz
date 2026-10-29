# Fork plan

This repository is a fork of [wgtunnel/android](https://github.com/wgtunnel/android)
that also carries [wgtunnel/desktop](https://github.com/wgtunnel/desktop) under
`desktop/` (built for Windows). It keeps the stock WG Tunnel apps and adds:

- an **OpenVPN** engine (openvpn3 core)
- a **mihomo** (Clash Meta) engine, with import of any Clash file, link or
  subscription
- **groups** that pick one tunnel from a list (Clash-style), for any engine
- **delay tests** for tunnels and groups
- **connection info** (exit IP, flag, city, ISP) on the connected tunnel
- a **WARP config generator** in Settings, with expiry, in-place regeneration,
  and standard or obfuscated (AmneziaWG) output

The goal is to change upstream files as little as possible, so that WG Tunnel
(Android and Desktop), openvpn3 and mihomo can each be updated independently.

All work is committed directly to `main`. Upstream Android's branch is
`master`; desktop is merged from its release tags.

---

## 1. Repository layout (single repo)

```
/                               ← fork of wgtunnel/android (remote "upstream")
  app/                          ← upstream, only small hook edits (see §11)
  logcatter/ networkmonitor/ pinger/   ← upstream, untouched
  desktop/                      ← wgtunnel/desktop via git subtree (squashed); Windows build
  engines/
    openvpn3/                   ← git submodule → OpenVPN/openvpn3, pinned to a release tag
    mihomo/                     ← git submodule → MetaCubeX/mihomo, pinned to a release tag
    patches/openvpn3/*.patch    ← our patches, applied at build time (never commit inside a submodule)
    patches/mihomo/*.patch
  fork/
    engine-api/                 ← engine interface shared by OpenVPN and mihomo
    engine-openvpn/             ← JNI/SWIG wrapper around openvpn3 ClientAPI (NDK/CMake)
    engine-mihomo/              ← gomobile/cgo wrapper around mihomo
    engine-bridge/              ← MultiEngineTunnelProvider, ForkDatabase, Koin override
    groups/                     ← group types, member selection, delay tests
    importer/                   ← detecting importer (files, links, subscriptions, QR, deep links)
    clash-ui/                   ← Clash screens
    connection-info/            ← geo-IP lookup and connected-row info
    warp/                       ← WARP generator (logic + screen)
    branding/                   ← resource overlay: icon, TV banner, notification glyph (res/), sources and PNGs (icon/)
  quartz.gradle                 ← Quartz overrides (app ID, name, provider authority); applied by one line in app/build.gradle.kts
  FORK.md                       ← this file
  .github/workflows/
    quartz-release.yml          ← weekly: new WG Tunnel Android/Desktop release → merge, build, release; engine bump PRs
    engine-bump.yml             ← new openvpn3/mihomo release tag → PR (or Renovate)
```

Submodules are used for the engines so a version bump is a one-line pointer
change and upstream engine history stays out of this repo. Clone with
`git clone --recursive`.

### Build time

- Engine modules skip the native build when the submodule commit hasn't
  changed; `.so` outputs are cached by submodule commit (locally and in CI via
  `actions/cache`).
- `-PprebuiltEngines` uses cached `.so` files for UI-only work.
- NDK, Go and gomobile versions are pinned in `gradle.properties` and CI.

## 2. Keeping up with upstream

| Source | How updates come in |
|---|---|
| WG Tunnel Android | We follow its **releases**, not every commit. `quartz-release.yml` checks weekly for a new release tag (e.g. `5.8.3`), merges it into `main` and publishes `quartz-android-5.8.3`. On conflict it opens a PR from `upstream-5.8.3` instead. By hand: `git fetch upstream tag 5.8.3 && git merge 5.8.3` (merge, never rebase). |
| WG Tunnel Desktop | Same, from its release tags (e.g. `v2.4.2`), subtree-merged into `desktop/` and published as `quartz-desktop-2.4.2`. On conflict it opens an issue. By hand: `git subtree pull --prefix=desktop --squash upstream-desktop refs/tags/v2.4.2` (remote `upstream-desktop` = `https://github.com/wgtunnel/desktop.git`). |
| openvpn3 | The weekly run opens a PR moving `engines/openvpn3` to the new tag. Check `patches/openvpn3` still apply and merge. **No release**: it ships with the next WG Tunnel release. |
| mihomo | Same as openvpn3, for `engines/mihomo`. |

- Enable `git config rerere.enabled true` so resolved conflicts are replayed.
- Merge upstream PRs with **Create a merge commit** — never squash or rebase,
  or git loses track of which upstream commits are already in `main`.
- Local setup: `git remote add upstream https://github.com/wgtunnel/android.git`.
- The release workflow needs a `SYNC_TOKEN` secret (PAT with `repo` + `workflow`
  scope), because upstream merges often change `.github/workflows`, which the
  default `GITHUB_TOKEN` can't push. "Allow GitHub Actions to create pull
  requests" must be enabled in the repo's Actions settings.
- Release versions record all three upstreams, e.g. `4.2.1+ovpn3-3.10_mihomo-1.19.x`.

### Releases

- No nightlies. Weekly, Quartz releases follow WG Tunnel releases, each app
  independently: `quartz-android-5.8.3` for WG Tunnel 5.8.3,
  `quartz-desktop-2.4.2` for WG Tunnel Desktop v2.4.2. Engine updates never
  trigger a release. Running `quartz-release` by hand with "force" publishes
  an extra build on the same base (`-2`, `-3`, …). Tags start with `quartz-`
  so they never trigger upstream's `publish.yml`. Android releases are
  created with `--latest=false`, so "latest" is always a desktop release
  (the desktop updater reads it). Disable upstream's `nightly` and
  `crowdin-contributors` workflows in the Actions UI (not by editing them).
- For now it ships the **fdroid** flavor: the standalone flavor's updater
  checks `wgtunnel/android` releases (`di/NetworkModule.kt`). To do: point it
  at this repo via a Koin override, then ship standalone.
- Windows: unsigned NSIS installer (SmartScreen warns), built with upstream
  desktop's toolchain (GraalVM via NucleusFramework, Go, MinGW, .NET).
- For now Android builds are **debug-signed** (`signing/quartz-debug.jks`,
  committed, public); everything is marked pre-release, which also keeps the
  desktop updater quiet. Switch to a private release key in the
  `KEYSTORE` / `SIGNING_*` secrets once the app works.

## 3. Engine integration

Upstream routes all tunnel start/stop through `TunnelProvider`
(`app/.../core/tunnel/TunnelProvider.kt`), implemented by
`TunnelBackendProvider` and bound as a Koin `single<TunnelProvider>` in
`di/TunnelModule.kt`. The WireGuard/AmneziaWG backend itself comes from the
external `com.wgtunnel:backend` library (wgtunnel/core).

`MultiEngineTunnelProvider` (in `fork/engine-bridge`) implements
`TunnelProvider` and:

- routes WireGuard/AmneziaWG tunnels to the existing backend unchanged, and
  OpenVPN/mihomo tunnels to our engines;
- merges `backendStatus` and `events` from all engines into the streams the UI
  already reads;
- allows one engine at a time (Android allows one active VPN), stopping the
  running engine before starting another;
- refuses to start expired tunnels (see §9).

It replaces upstream's binding by loading our Koin module after upstream's
(`allowOverride`), so `TunnelModule.kt` is not edited.

**Open issue:** `TunnelProvider` uses WireGuard-specific types
(`com.wgtunnel.backend.Tunnel`, `com.wgtunnel.parser.Config`, `BackendMode`).
Either adapt to them inside the bridge, or make a small change to generalise
the interface. Consider proposing a "pluggable backends" PR upstream, which
would remove most of this fork's maintenance.

**VpnService / kill switch:** the VpnService belongs to the upstream backend
library. Our engines get their own VpnService that creates the TUN and passes
the fd in: openvpn3 via `tun_builder` callbacks, mihomo by fd as
ClashMetaForAndroid does. Lockdown (`setLockDown`) is implemented by the
WireGuard backend, so a kill switch for the new engines must be built
separately.

### Data model

`TunnelConfig` (Room) stores WireGuard text in `quickConfig`; adding columns
would clash with upstream's migration numbers. Instead:

- every tunnel, including OpenVPN and Clash, keeps a shell `TunnelConfig` row,
  so auto-tunnel rules, groups, the tile and notifications work unchanged;
- engine-specific and fork data lives in our own Room database
  (`ForkDatabase`):
  - `engine_profile(tunnel_id, engine, payload)`
  - `tunnel_meta(tunnel_id, source, warp_slot, warp_variant, expires_at,
    warp_reg_id, warp_token, …)`
  - `group_meta(group_id, type, test_url, interval, tolerance, pinned_tunnel_id)`
  - `subscription(tunnel_id, url, user_agent, interval, wifi_only, via_vpn,
    last_ok_at, last_error, userinfo…)`
- the ~9 places that read `quickConfig` are guarded so non-WireGuard tunnels
  skip WireGuard parsing (one small, separate commit);
- backups add a separate file for our data rather than changing upstream's
  format.

## 4. Groups — `fork/groups`

Upstream already has groups (`TunnelGroup`: name, position, expanded) that
act as collapsible folders, and a tunnel belongs to at most one
(`TunnelConfig.groupId`). We **extend those groups** with a type rather than
adding a second groups feature.

**A tunnel is in exactly one place:** loose in the list, or in one group.
"Add to group…" on a grouped tunnel becomes "Move to group…".

| Type | Behaviour |
|---|---|
| **Folder** | Upstream behaviour (default). Organises the list; each tunnel has its own toggle. |
| **Select** | One toggle for the group; the user picks the member from a dropdown. |
| **Auto (fastest)** | Uses the member with the lowest delay; re-tests on an interval and switches only when another member is faster by more than a tolerance. |
| **Fallback** | Uses the first healthy member in list order; moves on when it fails a health check. |

- Members can be any engine: WireGuard, AmneziaWG, OpenVPN, Clash, WARP.
- The group type and settings live in `ForkDatabase.group_meta`, keyed by
  upstream's group ID.
- Changing member while connected switches live (brief reconnect, since
  Android allows one VPN). Switching nodes inside a Clash member is instant
  via mihomo's API.
- In Auto/Fallback, tapping a member pins it (Auto paused) until unpinned.
- Expired members are greyed out, can't be selected, and are skipped by
  Auto/Fallback.
- Auto-tunnel rules (SSID, mobile data, ethernet) can target a group.
- Quick Settings tile, shortcuts and notification show "Group → member"; the
  notification gets a **Next** action.
- Fallback health uses upstream's tunnel monitoring/recovery, not a second
  health system.
- Group export includes the type and member order.

## 5. Delay tests

A test per tunnel row, "Test all" per group, and ⚡ in the top bar for
everything. Badge colours: 🟢 < 300 ms, 🟡 < 800 ms, 🔴 slower, ⚪ timeout.
Results fade when older than ~10 minutes. Test URL is configurable (default
`https://cp.cloudflare.com/generate_204`).

| Member | Method | Accuracy |
|---|---|---|
| Clash nodes | mihomo URL test (`/proxies/{name}/delay`) | Real end-to-end |
| WireGuard / AmneziaWG / WARP | Run the config as a temporary mihomo `wireguard` outbound and URL-test through it, without touching the active VPN | Real handshake + end-to-end |
| OpenVPN (not connected) | TCP connect (TCP servers) or ICMP ping (UDP servers), via upstream's `pinger` | Reachability; labelled "ping" |
| Connected tunnel | URL test through the live tunnel | Real |

## 6. Connection info — `fork/connection-info`

Shown **only on the connected tunnel's row**, as upstream does today. Other
rows show just name, engine badge and delay.

```
🇦🇺 Sydney, NSW · 203.0.113.24 · Vultr
via Wi-Fi “Home” · Spark NZ
↓ 12.4 MB  ↑ 1.1 MB · handshake 8s ago      ← upstream's existing stats
```

- Line 1 (exit): flag (from country code), city/region, exit IP, exit ISP.
- Line 2 (your network): network type and SSID (from upstream's network
  monitor), underlying ISP.
- Tap to expand: IPv4/IPv6 exit, ASN, timezone, last checked, refresh, copy,
  delay test, optional leak check.
- Refreshes on connect, member/node switch, network change, pull-to-refresh;
  cached otherwise.
- The exit lookup must go through the tunnel (for Clash, via mihomo's local
  proxy port); the underlying-ISP lookup goes outside it. With lockdown on,
  line 2 shows only the network name.
- **Geo-IP is on by default**, using ipwho.is (no account). Settings →
  Connection info offers other providers (ipinfo.io, ip-api.com, Cloudflare
  trace — IP + country only) and an off switch.

## 7. Import — `fork/importer`

One **Import** entry detects what it was given.

**Sources:** file picker (`.yaml` `.yml` `.txt` `.conf` `.ovpn` `.json`, any
name), Open with / Share to the app, clipboard, QR code, typed URL, deep
links (`clash://install-config?url=…`, `clashmeta://…`).

| Content | Result |
|---|---|
| WireGuard / AmneziaWG `.conf` | WG/AWG tunnel (upstream import) |
| `.ovpn` | OpenVPN tunnel |
| Clash / mihomo YAML (full config) | Clash profile, as-is |
| YAML with only `proxies:` / `proxy-providers:` | Clash profile via template |
| Share links: `vmess` `vless` `trojan` `ss` `ssr` `hysteria` `hysteria2` `tuic` `anytls` `socks5` `http(s)` | Converted to mihomo proxies, via template |
| Base64 subscription | Decoded, then as share links |
| Subscription URL | Auto-refreshing subscription; content can be any of the above |
| sing-box JSON outbounds | Converted where mihomo has an equivalent; others listed as skipped |

- A single `wireguard://` link or WG `.conf` asks: **Add as WireGuard
  tunnel** (default) or **Add as Clash node**.
- WARP-tagged files whose slot already exists ask to replace or import
  separately (§9).

**Subscriptions:** sent with a mihomo/clash.meta User-Agent (editable per
subscription). Reads `subscription-userinfo` (used/total/expiry),
`profile-update-interval`, `content-disposition` (name),
`profile-web-page-url`. Per-subscription auto-update interval, Wi-Fi only,
update via VPN or direct, **Update now**. Failed updates keep the last good
config and show the error.

**Templates** for bare proxy lists: Simple (Select + Auto, all traffic via
proxy), Rule-based (domestic direct, rest via proxy, geo rule sets), Custom
(user YAML with `{{proxies}}`).

The import preview shows detected type, node count, provider usage/expiry,
name, template, auto-update, User-Agent, target group, and converted/skipped
counts before saving.

## 8. Clash (mihomo) UI — `fork/clash-ui`

- A Clash tunnel row has its own dropdown listing its proxy groups and nodes,
  with delay per node and the mode switch (Rule / Global / Direct).
- Subscription footer: last updated, traffic used/total, expiry, Update now.
- Search and sort by delay (for subscriptions with 100+ nodes).
- Optional: live connections, logs, provider refresh, DNS choice.

The UI talks to mihomo's `external-controller` REST API on localhost
(`/proxies`, `/configs`, `/connections`, `/providers`, delay tests). That API
is stable, so mihomo updates rarely break the UI; the Go bindings only need
start, stop and "set TUN fd".

## 9. WARP generator — `fork/warp`

A Kotlin port of the generator at `thebingchilling/site` (`tools/warp`), so
both produce identical, interchangeable configs. No engine work is needed: the
output is a normal WireGuard or AmneziaWG tunnel. Entry: Settings → Tunnel →
**WARP generator**, and **Generate WARP** in the ＋ sheet.

### Generation

1. Generate a Curve25519 key pair on the device (use an X25519 library, not
   the site's GPL-2.0 vendored file).
2. Register with Cloudflare's WARP client API
   (`https://api.cloudflareclient.com/<version>/reg`), falling back through
   `v0a737` → `v0a1909051800` → `v0i1909051800`. Optional WARP+ licence key.
   The app calls Cloudflare directly; no CORS proxy needed.
3. Build the config from the response (peer key, endpoint, v4/v6 addresses).
4. Options: endpoint (default / random from the 24 anycast IPs × 50 ports /
   custom), MTU (default 1280), DNS, AllowedIPs, keepalive, target group.

### Standard vs obfuscated

- **WireGuard:** plain config, saved as `ConfigType.WG`.
- **AmneziaWG (obfuscated):** same keys plus, saved as `ConfigType.AM`:
  - `Jc` / `Jmin` / `Jmax` — default 4 / 40 / 70, with presets
    (light / medium / heavy / custom);
  - `S1 = 0`, `S2 = 0`, `H1..H4 = 1 2 3 4` — **locked**: Cloudflare's end is
    stock WireGuard, so only client-side junk works;
  - `I1` — one of the real captured WARP QUIC Initial packets (from
    Skiro1/warp-awg-gen, as in the site's `vendor/awg-payloads.js`);
  - `I2..I5` — each 1–3 random byte blocks of 16–63 bytes;
  - I1–I5 picked once per registration.
- **Both:** two tunnels from one registration.
- WG Tunnel's parser (wgtunnel/core) already reads and writes Jc/Jmin/Jmax,
  S1–S4, H1–H4 and I1–I5, so both import through upstream's normal path.

After generating: **Import**, **Export .conf**, **QR**, **→ Clash** (add as a
`wireguard` node with `amnezia-wg-option` to a chosen Clash profile).

### Markers, slots and in-place regeneration

```ini
[Interface]
# WGT-Generator = warp
# WGT-Slot = warp
# WGT-Variant = awg
# WGT-Expires = 2026-12-01T00:00:00Z
PrivateKey = ...
```

- Comments go **inside `[Interface]`**: upstream reads the first comment above
  it as the tunnel name, and keeps `[Interface]` comments on save.
- `ForkDatabase.tunnel_meta` is the source of truth; comments carry the
  marker through export/import.
- **Slot + variant** (`wg` / `awg`) identifies one tunnel. Default slot is
  `warp`; the generator's Slot field allows others (e.g. `warp-phone`).
- **Regenerate replaces in place:** new keys, registration and expiry are
  written into the same tunnel ID, keeping name, position, group and member
  order, auto-tunnel rules, split tunnelling, and tile/shortcut targets.
  "Both" replaces both variants. If no tunnel matches, one is created.
- If the tunnel is connected, it reconnects on the new keys.
- After a successful replace, the old registration is deleted with Cloudflare
  (option, default on).
- Importing a file whose slot exists asks **Replace existing** or **Import as
  separate** (gets a new slot, e.g. `warp-2`) — never a silent overwrite.
- Hand-editing keeps the tag; **Detach from generator** removes it so
  regenerate no longer touches that tunnel.
- The site's generator should write the same marker comments.

The button reads **Generate** for an empty slot and **Regenerate** when the
slot has tunnels; the screen lists the tunnels in the selected slot.

### Expiry

- Choices: 1 / 7 / 30 / 90 days, custom date, or never. Effective expiry is
  the earlier of that and Cloudflare's `account.ttl` (when set).
- Not editable afterwards, and no "extend": the date exists only in the app.
  The only action on an expired tunnel is **Regenerate**.

Enforcement:

1. `MultiEngineTunnelProvider.startTunnel()` refuses expired tunnels (covers
   manual, tile, shortcut, group and auto-tunnel starts).
2. A WorkManager job at `expires_at` stops the tunnel if running; the check
   also runs on app start, boot and clock/timezone change.
3. Auto-tunnel and groups skip expired tunnels.
4. Notifications 24 h before expiry and at expiry. With lockdown on, traffic
   stays blocked after the stop; the notification says so.
5. List badge ("expires 5d" / "expired").
6. Re-importing a file whose `WGT-Expires` is past imports it as expired.
7. Optional **Revoke registration on expiry**: delete the registration with
   Cloudflare when the expiry job fires, so the keys stop working everywhere.

Limits: without revocation, expiry only stops this app. The WARP API is
unofficial, so the client sits behind a small interface.

## 10. UI screens

### Tunnels tab — nothing connected

```
┌─────────────────────────────────────────────┐
│ Tunnels                          ⚡  ⋮   ＋  │
├─────────────────────────────────────────────┤
│ ▾ Work VPNs · Auto                    [ ○ ] │
│     ↳ Sydney WG  WG            42 ms     ▾  │
│ ▾ Streaming · Select                  [ ○ ] │
│     ↳ My Clash sub  Clash      61 ms     ▾  │
│ ▾ Old stuff · Folder                        │
│     Home server   WG           18 ms  [ ○ ] │
│     Uni VPN       OVPN        ping 90 [ ○ ] │
│ ─────────────────────────────────────────── │
│ WARP (obfs)  AWG · expires 5d  33 ms  [ ○ ] │
│ Tokyo         OVPN           timeout  [ ○ ] │
├─────────────────────────────────────────────┤
│   🏠 Tunnels   ⚡ Auto   ⚙ Settings   ? Help │
└─────────────────────────────────────────────┘
```

### Tunnels tab — a group connected

```
┌─────────────────────────────────────────────┐
│ ▾ Work VPNs · Auto                    [ ●━] │
│     ↳ Sydney WG  WG  · auto    42 ms     ▾  │
│     🇦🇺 Sydney, NSW · 203.0.113.24 · Vultr   │
│     via Wi-Fi “Home” · Spark NZ             │
│     ↓ 12.4 MB  ↑ 1.1 MB · handshake 8s ago  │
│ ▾ Streaming · Select                  [ ○ ] │
│     ↳ My Clash sub  Clash      61 ms     ▾  │
│ WARP (obfs)  AWG · expires 5d  33 ms  [ ○ ] │
└─────────────────────────────────────────────┘
```

### Group dropdown

```
┌─────────────────────────────────────────────┐
│ Work VPNs · Auto                         ✕  │
│ 🔍 Search                    ↕ Delay  ⚡ Test│
├─────────────────────────────────────────────┤
│ ● Sydney WG       WG     · auto      42 ms  │
│ ○ WARP (obfs)     AWG    · exp 5d    61 ms  │
│ ○ Tokyo OVPN      OVPN              ping 138│
│ ○ My Clash sub    Clash               —   ▸ │
│ ○ Old server      WG               timeout  │
│ ⊘ WARP (old)      WG     · expired          │
├─────────────────────────────────────────────┤
│ 📌 Tapping a member pins it (Auto paused)   │
│            ＋ Add members   ✎ Edit group     │
└─────────────────────────────────────────────┘
```

### Clash tunnel — its own groups and nodes

```
┌─────────────────────────────────────────────┐
│ ← My Clash sub              Rule ▾   ⚡ Test │
├─────────────────────────────────────────────┤
│ ▾ Proxy · select            → HK 01   58 ms │
│     ● HK 01                           58 ms │
│     ○ HK 02                           71 ms │
│     ○ JP 01                          112 ms │
│ ▸ Streaming · url-test      → SG 02   64 ms │
│ ▸ Fallback · fallback       → HK 01   58 ms │
├─────────────────────────────────────────────┤
│ Subscription: updated 2h ago · 41/100 GB    │
│ expires 12 Dec 2026         ⟳ Update now    │
└─────────────────────────────────────────────┘
```

### Group editor

```
┌─────────────────────────────────────────────┐
│ ← Edit group                         Save   │
├─────────────────────────────────────────────┤
│ Name        [ Work VPNs                   ] │
│ Type        ( ) Folder  ( ) Select          │
│             (•) Auto    ( ) Fallback        │
│ ─ Auto settings ─────────────────────────── │
│ Test URL    [ cp.cloudflare.com/generate_204]│
│ Re-test     [ every 5 min ▾ ]               │
│ Tolerance   [ 50 ms ]                       │
│ ─ Members (drag to set order) ───────────── │
│ ≡ Sydney WG        WG                    ✕  │
│ ≡ WARP (obfs)      AWG                   ✕  │
│ ≡ Tokyo OVPN       OVPN                  ✕  │
│                ＋ Add members               │
└─────────────────────────────────────────────┘
```

### ＋ sheet and long-press menu

```
 Add tunnel ─────────────────────────┐   Long-press "Tokyo" ───────┐
 📥 Import (file, link, sub, QR…)    │   ✎ Edit                    │
 📋 Paste from clipboard             │   ⧉ Duplicate               │
 📷 Scan QR code                     │   ▣ Move to group…          │
 ☁ Generate WARP                     │   ⚡ Test delay             │
 ✎ Create WireGuard from scratch     │   ⤴ Export                  │
─────────────────────────────────────┘   🗑 Delete                  │
                                        ─────────────────────────────┘
```

### Import preview

```
┌─────────────────────────────────────────────┐
│ ← Import                                    │
├─────────────────────────────────────────────┤
│ Detected: Subscription · base64 · 38 nodes  │
│ Provider: ExampleVPN  · 41 / 100 GB         │
│           expires 12 Dec 2026               │
│ ─────────────────────────────────────────── │
│ Name        [ ExampleVPN                  ] │
│ Template    [ Rule-based ▾ ]                │
│ Auto-update [ every 12 h ▾ ]  ☐ Wi-Fi only  │
│ User-Agent  [ clash.meta ▾ ]                │
│ Group       [ none ▾ ]                      │
│ ─────────────────────────────────────────── │
│ 36 converted · 2 skipped (unsupported) ▸    │
│                 [ Add ]                     │
└─────────────────────────────────────────────┘
```

### Settings and WARP generator

```
┌───────────────────────────┐   ┌─────────────────────────────────────┐
│ Settings                  │   │ ← WARP generator                    │
├───────────────────────────┤   ├─────────────────────────────────────┤
│ Tunnel                    │   │ Slot     [ warp ▾ ] (replaces)      │
│  Backend mode    VPN ›    │   │ Type     ( ) WG  ( ) AWG  (•) Both  │
│  DNS settings      ›      │   │ Expiry   [ 30 days ▾ ]              │
│  Tunnel globals    ›      │   │ ☑ Delete old registration on regen  │
│  ☁ WARP generator  ›  NEW │   │ ☐ Revoke registration on expiry     │
│  Tunnel recovery   ›      │   │ Endpoint [ Default ▾ ]              │
│  Tunnel monitoring ›      │   │ ▸ Advanced (MTU, DNS, keepalive,    │
│  Connection info   ›  NEW │   │   Jc/Jmin/Jmax preset)              │
│  Android integrations ›   │   │ WARP+ key [ optional              ] │
│ General                   │   │ Group    [ none ▾ ]                 │
│  Appearance        ›      │   │ ─ In this slot ──────────────────── │
│  Security          ›      │   │  WARP         WG  · exp 28 Nov      │
│  …                        │   │  WARP (obfs)  AWG · exp 28 Nov · ●  │
└───────────────────────────┘   │          [ ⟳ Regenerate ]           │
                                └─────────────────────────────────────┘
```

### Expanded connection info

```
┌─────────────────────────────────────────────┐
│ Connection                     ⟳  checked 1m│
├─────────────────────────────────────────────┤
│ Exit                                        │
│  🇦🇺 Sydney, New South Wales                 │
│  IPv4  203.0.113.24                    ⧉    │
│  IPv6  2001:db8::24                    ⧉    │
│  ISP   Vultr · AS20473                      │
│  Time  Australia/Sydney                     │
│ Your network                                │
│  Wi-Fi “Home” · Spark NZ · 49.224.x.x       │
│ ─────────────────────────────────────────── │
│  Delay 42 ms · ↓ 12.4 MB ↑ 1.1 MB           │
│  [ ⚡ Test delay ]   [ 🔍 Leak check ]        │
└─────────────────────────────────────────────┘
```

Every tunnel row also shows an engine badge (WG, AWG, OVPN, Clash).

## 11. Upstream files we touch

Keep this list current; it is the checklist when an upstream merge
conflicts.

| File | Change | Why |
|---|---|---|
| `app/build.gradle.kts` | `apply(from = "../quartz.gradle")` (last line) | App ID `app.quartz`, name "Quartz", provider authority `app.quartz.provider`, debug signing with committed `signing/quartz-debug.jks` |
| Koin startup | Load fork module after upstream's | Override `TunnelProvider` |
| `quickConfig` call sites (~9) | Skip WireGuard parsing for non-WG tunnels | Multi-engine |
| Auto-tunnel candidate selection | Skip expired tunnels; allow group targets | WARP expiry, groups |
| `ui/navigation/Route.kt` | Routes to fork screens | Clash, WARP, import, groups |
| `ui/screens/settings/SettingsScreen.kt` | "WARP generator" and "Connection info" entries | Settings |
| `ui/screens/tunnels/components/TunnelImportSheet.kt` | Replace entries with detecting Import + Generate WARP | Import |
| `ui/screens/tunnels/components/TunnelList.kt` | Engine/expiry/delay badges, group toggle + dropdown, connection info on connected row | Groups, UI |
| Group create/rename UI (`GroupNameDialog.kt`, `GroupActionsSheet.kt`) | Group type and settings | Groups |
| `AndroidManifest.xml` | Intent filters for files, share and `clash://` deep links | Import |
| `desktop/shared/.../core/profile/AppVariant.kt` | `quartz` names for data/IPC folders, keyring, lock; `qtz` interface prefix | Install next to WG Tunnel Desktop |
| `desktop/composeApp/build.gradle.kts` | `appFsName`/`appDisplayName` = quartz/Quartz, vendor, description; `publish.github` → thebingchilling/quartz | Rebrand, updater |
| `desktop/composeApp/src/jvmMain/composeResources/values/strings.xml` | `app_name`, `tray_open` → Quartz | Window title, tray |
| `desktop/packaging/windows/icon.ico`, `desktop/composeApp/src/jvmMain/composeResources/drawable/appicon.png`, `titleicon.svg` | Replaced with the Quartz icon (generated from `fork/branding/icon`; `titleicon.svg` is the monochrome glyph cropped to `viewBox="27 27 54 54"`). On conflict, keep ours. | Desktop icon, tray, title bar |
| `desktop/composeApp/.../desktop/update/AppUpdater.kt` | `GitHubProvider` → thebingchilling/quartz | Updater must not offer WG Tunnel |

UI rules: prefer resource overlays (`fork/branding`) → new screens in
`fork/*` → small, `[fork-ui]`-prefixed edits to upstream screens, keeping our
additions in separate composables called from a single line.

## 12. Licensing

- WG Tunnel: MIT.
- mihomo: GPLv3. openvpn3: AGPLv3. Bundling them means this app is
  distributed under GPL-compatible terms with source available. (openvpn3 is
  chosen over ics-openvpn partly because GPLv2-only doesn't combine cleanly
  with GPLv3.)
- WARP payloads (Skiro1/warp-awg-gen) are MIT; the site's vendored
  Curve25519 code is GPL-2.0, so the Kotlin port uses a library instead.
- Rebrand (name, icon, applicationId) via the overlay; don't ship as
  "WG Tunnel".

## 13. Build order

1. Repo setup: upstream remote, `quartz.gradle`, branding (name, ID and icon done), `quartz-release.yml`. Confirm a clean merge from upstream.
2. WARP generator, slots and expiry (pure app code; proves the Settings hook).
3. Engine submodules + mihomo engine, detecting importer, Clash UI on the
   REST API.
4. `MultiEngineTunnelProvider`, `ForkDatabase`, `quickConfig` guards — wire
   Clash into the tunnel list and auto-tunnel.
5. Groups (types, dropdown, editor) and delay tests.
6. Connection info.
7. OpenVPN engine and import.
8. Kill switch for non-WireGuard engines; test auto-tunnel and groups across
   all engine types.
9. CI: release-following merge + release; engine bump PRs on new openvpn3/mihomo tags.
