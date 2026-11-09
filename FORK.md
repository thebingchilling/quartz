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

All work is committed directly to `main`, the only branch: no feature
branches. The only PRs are the ones `quartz-release` opens itself (an
upstream merge that conflicts, an engine bump). Upstream Android's branch is
`master`; desktop is merged from its release tags.

---

## 1. Repository layout (single repo)

```
/                               ← fork of wgtunnel/android (remote "upstream")
  app/                          ← upstream Android app, only small hook edits (see §11)
  logcatter/ networkmonitor/ pinger/   ← upstream, untouched
  desktop/                      ← wgtunnel/desktop via git subtree (squashed); Windows build, small rebrand edits (§11)
  quartz/                       ← ALL of Quartz's own app files; a push touching quartz/** builds and releases
    android/
      quartz.gradle             ← Quartz overrides (app ID, name, signing, fork sources, engine build); applied by one line in app/build.gradle.kts
      signing/quartz-debug.jks  ← committed debug key
      engines/
        mihomo/                 ← git submodule → MetaCubeX/mihomo, pinned to a release tag (unpatched)
        openvpn3/               ← (step 7) git submodule → OpenVPN/openvpn3
        versions.properties     ← pinned engine releases (read by the app's "Check upstream versions")
      core/                     ← ForkDatabase, Quartz Koin module, startup sync, dialogs, Info tab, tunnel-row badges, upstream version check
      engine-bridge/            ← MultiEngineTunnelProvider, Clash profiles, mihomo VpnService + companion, manifest
      engine-mihomo/            ← cgo/JNI wrapper around mihomo (go/) + Kotlin MihomoEngine and REST API client (src/)
      importer/                 ← detecting importer (share links, Clash YAML, subscriptions, sing-box, templates)
      warp/                     ← WARP generator (logic + screen); assets/warp/awg-payloads.txt = captured I1 packets
      branding/                 ← resource overlay: icon, TV banner, notification glyph (res/), sources and PNGs (icon/)
      (later: engine-openvpn/, groups/, clash-ui/, connection-info/)
    windows/                    ← Quartz files for the desktop app (none needed yet; see its README)
  FORK.md                       ← this file
  .github/workflows/
    quartz-release.yml          ← push to quartz/** or manual → build both apps, one release; "update" input merges upstream
    quartz-workflows.yml        ← keeps every upstream workflow except pr.yml disabled
    quartz-cleanup.yml          ← after a successful run, deletes every other run of that workflow (artifacts, logs) and stale caches; by hand: purges the repo
```

**How fork code is built:** `quartz/android/<name>/src/{main,test}` are not
Gradle modules: `quartz.gradle` adds their `java` (Kotlin), `res` and `assets`
directories to the app's source sets, so fork code uses upstream's types
(repositories, Koin, Compose components) directly and nothing in upstream's
build files changes beyond the one `apply` line. Fork code lives in the
`app.quartz.*` package; strings are prefixed `quartz_`. Add a new directory
to the list in `quartz.gradle`. The mihomo engine's native library is built
by a task in `quartz.gradle` too.

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
| WG Tunnel Android | We follow its **releases**, not every commit. A manual `quartz-release` run with **update** checks for a new release tag (e.g. `5.8.3`), merges it into `main` and publishes a dual release (below). On conflict it opens a PR from `upstream-5.8.3` instead. By hand: `git fetch upstream tag 5.8.3 && git merge 5.8.3` (merge, never rebase). |
| WG Tunnel Desktop | Same, from its release tags (e.g. `v2.4.2`), subtree-merged into `desktop/`; same dual release. On conflict it opens an issue. By hand: `git subtree pull --prefix=desktop --squash upstream-desktop refs/tags/v2.4.2` (remote `upstream-desktop` = `https://github.com/wgtunnel/desktop.git`). |
| openvpn3 | A run with **update** opens a PR moving `quartz/android/engines/openvpn3` to the new tag. Merging it touches `quartz/**`, so it builds a release. |
| mihomo | Same as openvpn3, for `quartz/android/engines/mihomo`. |

- Enable `git config rerere.enabled true` so resolved conflicts are replayed.
- Merge upstream PRs with **Create a merge commit** — never squash or rebase,
  or git loses track of which upstream commits are already in `main`.
- Local setup: `git remote add upstream https://github.com/wgtunnel/android.git`.
- The release workflow needs a `SYNC_TOKEN` secret (PAT with `repo` + `workflow`
  scope), because upstream merges often change `.github/workflows`, which the
  default `GITHUB_TOKEN` can't push. "Allow GitHub Actions to create pull
  requests" must be enabled in the repo's Actions settings.
- Release versions record all three upstreams, e.g. `4.2.1+ovpn3-3.10_mihomo-1.19.x`.

### Builds happen in GitHub Actions only (standing rule)

Code agents don't build, test or package locally in their session: no
Gradle, Go or NDK builds, no local APKs. Make the change, push it to
`main`, and let `quartz-release` build, test and publish it; check that
run's result and logs, and fix forward with another push if it fails. Test
APKs come from the release, not from the session.

### Actions storage (standing rule)

GitHub Actions minutes are not the limit; Actions **storage** is (artifacts
and caches of this private repository count towards it). Code agents and
people working on this repo follow this:

- **Minutes are fine to use; never hold a build back.** Push when work is
  ready, start or re-run workflows by hand when useful, and don't use
  `[skip ci]`: every push that changes `quartz/**` builds a dual release (a
  newer push cancels an unfinished one).
- **One run per workflow:** `quartz-cleanup` runs after every successful run
  and deletes every other run of that workflow (older, failed, cancelled),
  with its artifacts and logs, and keeps only the newest cache for each set of
  paths. Run by hand, it cuts every workflow down to its newest successful
  run. Don't work around it.
- **Keep storage small:** artifacts get a short `retention-days` (3 or less
  for builds; the APKs and installer live on the release), caches use one key
  per set of paths (the mihomo `.so` cache is keyed by submodule commit, so
  old ones are pruned), and nothing large is uploaded that a release already
  holds.
- **The only manual step is updating upstream:** a `quartz-release` run with
  **update** ticked, started by the owner or an agent (when the in-app "Check
  upstream versions" says an upstream is newer). It never runs on its own.
- **Nothing ever runs on a schedule.** Builds start only when a push changes
  the apps (`quartz/**` → `quartz-release`); `quartz-workflows` runs on
  pushes that change `.github/workflows` and `quartz-cleanup` after those
  runs. Upstream's scheduled workflows (`nightly`, `crowdin-contributors`)
  stay disabled by `quartz-workflows`; never add a `schedule:` trigger.
  `pr.yml` (upstream's format check) runs on pull requests, so avoid opening
  PRs here; work goes straight to `main`.

### App UI uses only the app's own components (standing rule)

Every Quartz screen and control is built from components WG Tunnel already
has (`SurfaceRow`, `IconSurfaceButton`, `ThemedSwitch`, `ConfigurationTextBox`,
`GroupLabel`, `DescriptionText`, `CustomBottomSheet`, the auto-tunnel row's
`Button`, Material `IconButton`/icons as upstream uses them) and follows the
patterns upstream screens already use (section header = `GroupLabel` in a
48 dp box; choose-one = `IconSurfaceButton` cards; on/off = `SurfaceRow` +
`ThemedSwitch`; latency text and colours as on the tunnel list). No new
components, cards, borders, chips, colours or layout rules.

### Releases

- No nightlies, no schedule: `quartz-release` runs on **pushes to `main`
  that change `quartz/**`** (Quartz's own app files) and when started by
  hand. Docs, workflow files and upstream folders don't start it; a newer
  push cancels an unfinished push build. **Every run is a dual release:** one GitHub release with the
  Android APKs and the Windows installer, built from the same commit and
  named after both bases, e.g. `quartz-5.8.2-desktop-2.4.1` (`-2`, `-3`, …
  for later builds on the same bases).
- **Updating upstream resources is manual only** (owner or agent): only a
  run with **update** ticked merges new WG Tunnel Android/Desktop releases and opens
  engine bump PRs. Without it the run just builds what's on `main`.
- **In-app check:** Info tab → **Check upstream versions** compares this
  build's bases (baked into `BuildConfig` by `quartz.gradle`: WG Tunnel
  Android/Desktop versions, `engines/versions.properties` for the engines)
  with the latest WG Tunnel Android, WG Tunnel Desktop, mihomo and openvpn3
  releases, and if any is newer offers to open the `quartz-release` page to
  run it with **update**. The app can't start the workflow itself. Tags start with
  `quartz-` so they never trigger upstream's `publish.yml`. Each release is
  marked latest; it carries the installer's `latest.yml`, which the desktop
  updater reads. Older releases (`quartz-android-*`, `quartz-desktop-*`)
  predate this.
- **Upstream automation never runs here.** `quartz-workflows.yml` disables
  every upstream workflow (`nightly`, `crowdin-contributors`, `notifications`,
  `publish*`, `build*`, …) through the Actions API, on each push to `main`
  that touches `.github/workflows` and weekly, so workflows a future upstream
  merge adds are switched off too. Only `quartz-*.yml` and upstream's
  `pr.yml` (format check) stay on. The files themselves are left alone so
  merges don't conflict. Dependabot is off (`.github/dependabot.yml`
  deleted): dependency bumps come from upstream merges.
- For now it ships the **fdroid** flavor: the standalone flavor's updater
  checks `wgtunnel/android` releases (`di/NetworkModule.kt`). To do: point it
  at this repo via a Koin override, then ship standalone.
- Windows: unsigned NSIS installer (SmartScreen warns), built with upstream
  desktop's toolchain (GraalVM via NucleusFramework, Go, MinGW, .NET).
- Releases are **public** (not pre-releases). For now Android builds are
  **public debug builds**, signed with the committed `quartz/android/signing/quartz-debug.jks`
  so each one installs over the last; the desktop updater sees Quartz desktop
  releases as updates. Switch to a private release key in the
  `KEYSTORE` / `SIGNING_*` secrets once the app works (users then reinstall
  once, since the signature changes).

## 3. Engine integration

Upstream routes all tunnel start/stop through `TunnelProvider`
(`app/.../core/tunnel/TunnelProvider.kt`), implemented by
`TunnelBackendProvider` and bound as a Koin `single<TunnelProvider>` in
`di/TunnelModule.kt`. The WireGuard/AmneziaWG backend itself comes from the
external `com.wgtunnel:backend` library (wgtunnel/core).

`MultiEngineTunnelProvider` (in `quartz/android/engine-bridge`) implements
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

### mihomo engine (built)

- `quartz/android/engines/mihomo` is pinned to a release tag (v1.19.33) and **not patched**:
  the TUN comes from the VpnService as `tun.file-descriptor`, and mihomo's
  own sockets are protected through `dialer.DefaultSocketHook` (the hook
  mihomo keeps for Android clients), which calls back into Kotlin's
  `SocketProtector` → `VpnService.protect(fd)`.
- `quartz/android/engine-mihomo/go` (`engine.go`, `jni.c`): start/stop/validate/version.
  Any profile runs as-is; the wrapper forces TUN on, `auto-route`,
  `auto-detect-interface` and `interface-name` off, DNS hijack on, and the
  REST controller on a random loopback port with a random secret.
- `quartz.gradle` builds `libmihomo.so` for `arm64-v8a` and `armeabi-v7a`
  (Go on PATH, NDK pinned as `quartzNdkVersion`), skipped while the submodule
  commit and wrapper are unchanged; `-PprebuiltEngines` skips it. CI installs
  Go and the NDK and caches the `.so` files by the same key. Native libraries
  are compressed in the APK (`useLegacyPackaging`, the strongest the Android
  build offers), and Android x86/x86_64 libraries are left out (phones are
  ARM).
- `MihomoEngine` (Koin single): `start(profile, tunFd, protector)` returns the
  controller address and secret for the Clash UI; `validate(profile)` checks a
  profile without running it (used by the importer).
- Smoke-tested on the JVM with a host build (loads, version, validates good
  and bad profiles); running it on a device needs step 4's VpnService.

### Multi-engine routing (built)

- `MultiEngineTunnelProvider` (`quartz/android/engine-bridge`) is the Koin-bound
  `TunnelProvider`: it wraps upstream's `TunnelBackendProvider` for WireGuard,
  routes tunnels that have an `engine_profile` row to mihomo, stops the other
  engine first, refuses expired tunnels (replacing the step-2 expiry guard)
  and merges mihomo's running tunnel into `backendStatus` as an
  `ActiveTunnel`, so upstream's list, tile and notification show it like any
  tunnel. Clash tunnels run in VPN mode only for now.
- **Shell rows:** a Clash tunnel's upstream row holds a placeholder WireGuard
  config (`# WGT-Engine = mihomo`, random key, loopback endpoint) that
  upstream's ~25 `quickConfig` parsers accept, so **no upstream file needed a
  guard**; it's never connected. The YAML lives in `files/clash/`
  (`ClashProfiles`), with subscription fields in the same `engine_profile`
  row (one table instead of a separate `subscription` table).
- `MihomoVpnService` builds the TUN (172.19.0.1/30 + fdfe:dcba:9876::1/126,
  DNS 172.19.0.2 hijacked into mihomo, MTU 9000) and hands its fd to mihomo;
  `MihomoCompanionService` holds upstream's tunnel notification in the
  foreground, mirroring upstream's VpnService + VpnCompanionService split.
  Both are declared in `quartz/android/engine-bridge/src/main/AndroidManifest.xml`,
  merged through the flavor+build-type source sets.
- The wrapper turns on mihomo's DNS (fake-ip, DoH) when a profile leaves it
  off, since the TUN's DNS goes to it.
- Backups carry `engine_profile` rows and the YAML files.
- Not tested on a device yet (no emulator here). Next: the Import screen and
  Clash screen, which create and show these tunnels.

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
- backups carry our data as extra `quartz/…` entries in upstream's backup zip
  (upstream's own entries are unchanged; see §9 "Implementation status").

## 4. Groups — `quartz/android/groups`

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

One button in the Tunnels top bar tests **everything at once**
(`QuartzDelays`, up to 48 in parallel); it's greyed out while a tunnel is
connected. Every WireGuard/AmneziaWG tunnel's server and every Clash node's
server gets one ICMP ping (1.5 s timeout). Results show on the right of
each row as they arrive, with the tunnel list's thresholds (≤ 50 ms green,
≤ 150 ms yellow, else red, "unreachable") in shades readable on light and
dark themes.

Refresh: pull down on the Tunnels list, or the ⟳ button in its top bar
(`QuartzRefresh`), downloads every Clash subscription again (applies on the
next connect), reloads every Clash tunnel's groups and nodes, and looks up
the exit IP again. Upstream's ping (3 pings, only while disconnected) is
no longer called.

Later, maybe: WireGuard tunnels URL-tested through a temporary mihomo
`wireguard` outbound (real handshake) instead of a ping.

## 6. Connection info — `quartz/android/connection-info`

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

### Implementation status

- Done (first part): under a connected tunnel, upstream's per-peer details
  (endpoint, handshake, transfer per peer) are replaced by the exit: flag +
  IP, "City, Region, Country", "ISP: …", in the same text style as the
  status/uptime lines (`QuartzIpInfo`, one line in `TunnelStatisticsRow.kt`).
  `IpLookup` (`quartz/android/core/…/ip`) asks ipwho.is, then ipapi.co, over
  HTTPS through the tunnel (the app's own traffic uses it), once per
  connection, retrying a few times right after connect; a Clash node switch
  looks it up again. A failed lookup can be retried by tapping it.
- Not yet: line 2 (your network), the tap-to-expand details, provider
  choice and the off switch.

## 7. Import — `quartz/android/importer`

**Upstream's own add paths take everything; there's no separate Import
screen.** The ＋ sheet is stock (file, QR, clipboard, URL, create).
`QuartzImport` hooks in at two points in upstream's `SharedAppViewModel`:
`importTunnelConfigs` (which file, clipboard and QR all reach) hands each
config to `QuartzImport.claim` first, and `importFromUrl` asks
`QuartzImport.claimUrl`. Quartz saves Clash profiles, share links,
subscriptions and sing-box configs as Clash tunnels (Simple template;
subscriptions auto-update every 24 h or the provider's interval), answers
OpenVPN with "not supported yet", and hands WireGuard back to upstream's
import unchanged (a lone `wireguard://` link is converted to a WireGuard
config first). Upstream's file reader accepts any file (not only `.conf` /
`.zip`), read as text.

**Detected:** file (any extension), clipboard, QR code, typed URL, deep
links (`clash://install-config?url=…`, `clashmeta://…`) pasted as text.

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

### Implementation status

Built (`quartz/android/importer`, pure Kotlin, unit-tested):

- `ImportDetector.detect(text, fileName)`: WireGuard/AmneziaWG, `.ovpn`,
  full Clash profile, proxies-only YAML (with `proxy-providers`), share
  links, base64 bodies, subscription URLs (incl. `clash://` / `clashmeta://`
  deep links; `http://user:pass@host:port` is an HTTP proxy, not a
  subscription), sing-box JSON. Unparseable lines are reported as skipped.
- `ShareLinks`: all the schemes above → mihomo proxies (ws/grpc/h2/
  httpupgrade/http transports, TLS, Reality, ss plugins, IPv6 servers).
- `ClashTemplate`: Simple, Rule-based (private + ads + one country direct),
  Custom (`{{proxies}}`, `{{proxy-names}}`); unique proxy names; fake-ip DNS.
- `SubscriptionInfo`: `subscription-userinfo`, `profile-update-interval`,
  `content-disposition` (RFC 6266 `filename*`), `profile-web-page-url`.
- YAML via snakeyaml-engine (Apache-2.0), added in `quartz.gradle`.
- `./gradlew :app:buildMihomoHost` builds the engine for the JVM; unit tests
  then check generated profiles against real mihomo.

Next (with step 4's storage): the Import entry and preview screen, fetching
and auto-updating subscriptions, saving Clash profiles as tunnels.

## 8. Clash (mihomo) UI — `quartz/android/clash-ui`

- **A Clash tunnel is a group on the Tunnels list**, in the app's compact
  list row (`ExpandingRowListItem`, the one upstream's reorder list uses):
  under its row, each proxy group is just a folder icon and its name; the
  main group (first selectable, usually PROXY) starts open, the rest
  closed. Nodes follow, not indented: a dot for the selected one, name with
  protocol under it, latency on the right. Shown whether connected or not:
  live from mihomo while it runs (tap a node to select it), otherwise from
  the profile YAML plus mihomo's cached proxy-provider files, with `use`,
  `filter`, `exclude-filter` and `include-all` applied (`ClashTrees`).
- Tapping a Clash tunnel opens its own screen for the rest: subscription
  (last updated, traffic used/total, expiry, Update now, provider page),
  tunnel settings and export.
- Optional: live connections, logs, provider refresh, DNS choice.

The UI talks to mihomo's `external-controller` REST API on localhost
(`/proxies`, `/configs`, `/connections`, `/providers`, delay tests). That API
is stable, so mihomo updates rarely break the UI; the Go bindings only need
start, stop and "set TUN fd".

### Implementation status

- `MihomoApi` (`quartz/android/engine-mihomo`): groups and nodes in profile order,
  last delay, select, per-node and per-group URL tests, mode get/set,
  providers and provider update, version. Tested against a running host
  build of mihomo (no TUN: the wrapper treats a negative fd as "no TUN").
- Done: `QuartzClashRows` + `ClashTrees` (Tunnels list) and
  `ClashScreen` + `ClashViewModel` (subscription, settings, export).
- Start path: `MihomoRunner` starts `MihomoCompanionService` (foreground);
  the companion then starts `MihomoVpnService`, so a start from the
  background (tile, auto-tunnel) doesn't throw. The VPN service parses the
  profile before it establishes the TUN (parsing downloads missing geodata,
  which would hang behind an unread TUN), then hands the TUN fd to mihomo.
  The JNI protector's old global ref is never freed (mihomo threads from the
  last run may still use it), and a Go panic during start is returned as an
  error instead of killing the app.
- The companion stops itself (after startForeground, via `stopSelfResult`)
  once the runner is Idle or Failed; nothing else stops it, since stopping a
  `startForegroundService` service before its startForeground crashes the
  app. A dying previous VPN service can't reset a new start (only Running
  becomes Idle).
- The Go wrapper clears mihomo's remembered TUN settings on every shutdown:
  mihomo keeps them across `executor.Shutdown()`, and a new TUN with the
  same fd number looked "unchanged", so no TUN listener was opened (connected
  with no traffic).

## 9. WARP generator — `quartz/android/warp`

A Kotlin port of the generator at `thebingchilling/site` (`tools/warp`), so
both produce identical, interchangeable configs. No engine work is needed: the
output is a normal WireGuard or AmneziaWG tunnel. Entry: the **Info** tab →
**WARP generator**. Built only from the app's own components (see the UI
rule in §2): WireGuard / AmneziaWG selection cards with a few words each, an
**Advanced options** switch row revealing the config editor's text boxes,
then **Generate**. Once generated, the only action is **Save to tunnels**
(replaces the WARP tunnel of that kind); saving turns the row into a
"Saved to tunnels ✓" indicator until something changes. The generated
config's details aren't shown. "Your WARP tunnels"
last. No config text, copy, download or QR in the app (the site has those).

**Rule: the configs follow the site's configurator.** Same choice (WireGuard
or AmneziaWG), same advanced options, same defaults and fallbacks, so both
produce identical configs. In the app a blank endpoint means the default and
the dice fills in a random one (the site's Default/Random/Custom). Don't add
options the site doesn't have; change both together.

### Generation

1. Curve25519 key pair on the device (wgtunnel/core's `Key`, not the site's
   GPL-2.0 vendored file).
2. Register with Cloudflare's WARP client API
   (`https://api.cloudflareclient.com/<version>/reg`), trying `v0a737` →
   `v0a1909051800` → `v0i1909051800`; body `{key, install_id: "",
   warp_enabled: true, tos, type: <device type>, locale}`. The app calls
   Cloudflare directly (no CORS proxy).
3. Build the config from the response (peer key, endpoint, v4/v6 addresses).
   Changing an option afterwards rebuilds it from the same registration;
   only **Register a new device** registers again.

**Advanced options** (as on the site): endpoint (default / random from the
24 anycast IPs × 50 ports, with re-roll / custom; editing a random pick makes
it custom), DNS (`1.1.1.1, 1.0.0.1`; empty → the same), MTU 1280, Allowed IPs `0.0.0.0/0, ::/0`, keepalive 0 (= no line),
device type `Linux`, locale `en_US`, and for AmneziaWG Jc/Jmin/Jmax (4 / 40 /
70, swapped if entered the wrong way round). Blank or invalid fields fall
back to the default, never an error.

**IPv4 by default, IPv6 blocked:** the config carries only WARP's IPv4
address and IPv4 DNS, while Allowed IPs keeps `::/0`, so IPv6 traffic goes
into the tunnel and fails at once (apps fall back to IPv4) instead of
leaking around it. The exit is WARP's IPv4, which geolocates to the user's
region; WARP's IPv6 range (`2a09:bac5:…`) is often placed in another
country by IP-check sites. Same on the site.

### AmneziaWG

- `S1 = 0`, `S2 = 0`, `H1..H4 = 1 2 3 4`, locked: Cloudflare's end is stock
  WireGuard, so only client-side junk works.
- `I1`: one of the three captured WARP QUIC Initial packets
  (`assets/warp/awg-payloads.txt`, same as the site); `I2..I5`: each 1–3
  random byte blocks of 16–63 bytes. Picked once per registration.

### Markers, slots and in-place replacement

```ini
[Interface]
# WGT-Generator = warp
# WGT-Slot = warp
# WGT-Variant = awg
# WGT-Expires = 2027-01-30T10:00:00Z
PrivateKey = ...
```

- Comments go **inside `[Interface]`** (upstream keeps them on save). The site
  writes the same markers.
- `ForkDatabase.tunnel_meta` is the source of truth; the comments carry the
  marker through export/import.
- **One WARP and one WARP AWG tunnel, no slot choice.** `WGT-Slot` is always
  `warp`. Saving a new config replaces the tunnel of that kind in place (same
  tunnel ID: name, position, group, auto-tunnel rules, split tunnelling,
  shortcuts stay), reconnects it if running, and deletes the old
  registration with Cloudflare; the first one is created as "WARP" / "WARP
  AWG".
- Importing a marked file (from the site, a backup or another device) does
  the same, with no question: it replaces the existing tunnel of its kind,
  and the imported row is removed.

### Expiry

- **Fixed, not a choice:** 90 days after Cloudflare created the registration
  (or Cloudflare's account TTL, when it sets an earlier one).
- Expired tunnels can't start (any start path, auto-tunnel skips them); a
  WorkManager job stops a running one at expiry and notifies 24 h before and
  at expiry (lockdown-aware); list badge "expires 5d" / "expired". The only
  fix is generating a new one into the slot.

### Implementation status

Built in step 2 (`quartz/android/warp`, `quartz/android/core`):

- **Parity with the site** (`thebingchilling/site` `tools/warp`): same API
  versions and registration body (`type: Linux`, `warp_enabled`), same
  `{result: …}` handling and `success: false` errors, same anycast IP and port
  lists for **Random**, same defaults (DNS `1.1.1.1, 1.0.0.1` + their IPv6 pair, MTU 1280,
  keepalive off), Jmin/Jmax swapped when entered the wrong way round, I2–I5
  as 1–3 blocks of 16–63 random bytes, and the same three captured I1 packets
  (`quartz/android/warp/src/main/assets/warp/awg-payloads.txt`, copied from the site's
  `vendor/awg-payloads.js`). If that file is ever empty the app builds a real
  QUIC v1 Initial instead (`QuicInitial`, checked against RFC 9001's vectors).
  Like the site, an AmneziaWG config too long for a QR code says so.
- The site writes the same `# WGT-…` markers (advanced option **Slot**,
  fixed 90-day `WGT-Expires`), so its configs import into a slot here.
- `CloudflareWarpApi`: also delete; `client_id` is kept for
  Clash's `reserved`. Keys come from wgtunnel/core's `Key` (MIT). Cloudflare's
  unset `account.ttl` (`0001-01-01…`) is ignored.
- `WarpConfigBuilder` / `WarpMarkers`: unit tests check upstream's parser
  reads both variants and keeps the markers through `parseQuickString` →
  `asQuickString`.
- `WarpRepository`: one tunnel per kind, replaced in place (old registration
  deleted, reconnect if running); `QuartzSync` adopts imported marked files
  the same way.
- `ClashExport` (a WARP config as a mihomo `wireguard` proxy, `reserved` from
  `client_id`) is kept for adding WARP to a Clash profile later; the screen
  has no Clash button, since the site doesn't.
- Expiry: `MultiEngineTunnelProvider` refuses expired starts; auto-tunnel never sees expired tunnels as candidates
  (`QuartzAutoTunnel`); `WarpExpiry` keeps one WorkManager job for the next
  warning/expiry (WorkManager handles reboot and clock changes; a check also
  runs on app start), stops the tunnel, notifies (lockdown-aware). Badges via `quartzTunnelDescription`. Groups skipping expired
  members comes with groups (step 5).
- Backups: `tunnel_meta` goes into upstream's backup zip as
  `quartz/tunnel_meta.json` (`.aes` with the backup's password). Restoring a
  backup without it (from WG Tunnel) clears `tunnel_meta`; marked tunnels are
  re-adopted.

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
 Add tunnel (stock) ─────────────────┐   Long-press "Tokyo" ───────┐
 📂 Add from file (any config)       │   ✎ Edit                    │
 📷 Add from QR code                 │   ⧉ Duplicate               │
 📋 Add from clipboard               │   ▣ Move to group…          │
 🔗 Add from URL (incl. subscription)│   ⚡ Test delay             │
 ✎ Create from scratch               │   ⤴ Export                  │
─────────────────────────────────────┘   🗑 Delete                  │
                                        ─────────────────────────────┘
```

### Import preview

Not built: imports go straight in through the stock add paths (see §7), with a
snackbar naming what was added.

### WARP generator (Info tab)

```
┌─────────────────────────────────────┐
│ ← WARP generator                    │
├─────────────────────────────────────┤
│ ┌ WireGuard ──────────────────────┐ │
│ │ A standard WARP config. Works   │ │
│ │ in any WireGuard app.           │ │
│ └─────────────────────────────────┘ │
│ ┌ AmneziaWG ──────────────────────┐ │
│ │ Adds junk packets and …         │ │
│ └─────────────────────────────────┘ │
│ ⚙ Advanced options          [ ○ ]   │
│   (endpoint ⚄, DNS, MTU, allowed    │
│    IPs, keepalive, device type,     │
│    locale, Jc/Jmin/Jmax)            │
│ ⟳ Generate WARP config  [Generate]  │
│   → after generating, only:         │
│ ⤓ Save to tunnels          [Save]   │
│   Replaces WARP (keeps its name…)   │
│ YOUR WARP TUNNELS                   │
│  WARP          WireGuard · expires… │
│  WARP AWG      AmneziaWG · expires… │
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
| `app/build.gradle.kts` | `apply(from = "../quartz/android/quartz.gradle")` (last line) | App ID `app.quartz`, name "Quartz", provider authority `app.quartz.provider`, debug signing with committed `quartz/android/signing/quartz-debug.jks`, LeakCanary excluded (no "Leaks" launcher icon), fork source directories |
| `app/schemas/app.quartz.core.data.ForkDatabase/` | New files only (Room schema export of `ForkDatabase`) | Room migrations |
| `WireGuardAutoTunnel.kt` (Koin startup) | `quartzModule` added last to `modules(…)` | Fork Koin module; overrides `TunnelProvider` |
| `quickConfig` call sites (~9) | Skip WireGuard parsing for non-WG tunnels | Multi-engine |
| `service/autotunnel/AutoTunnelService.kt` | Tunnel flow wrapped in `QuartzAutoTunnel.candidates(…)` (skips expired, done); group targets later | WARP expiry, groups |
| `data/backup/BackupArchive.kt`, `DatabaseBackup.kt` | Optional `quartz/…` zip entries (encrypted like the database); `QuartzBackup.export()` / `restore()` calls | Fork data in backups |
| `ui/navigation/Route.kt` | `Route.Warp` (Info tab), `Route.Clash(id)` (+ `Tab` mapping) | WARP, Clash screens |
| `ui/navigation/components/currentBackStackEntryAsNavbarState.kt` | Title/back button per fork route; Tunnels top bar is just Add, delay test (`rememberQuartzDelayTest`, greyed while connected) and refresh (`rememberQuartzRefresh`), no ⋮ menu (reorder/ping) | Exhaustive `when` over `Route`; simpler top bar |
| `MainActivity.kt` | `entry<Route.Warp>`, `entry<Route.Clash>`; upstream's `Toaster` drawn only `if (QUARTZ_TOASTS)` (false: no toasts) | Nav graph; no toasts |
| `viewmodel/SharedAppViewModel.kt` | `importTunnelConfigs`: `QuartzImport.claim(…)` first; `importFromUrl`: `QuartzImport.claimUrl(url)` first (2 lines) | Stock add paths take Clash/OpenVPN/links/subscriptions |
| `util/FileUtils.kt` | `readConfigsFromUri`: other extensions are read as text instead of refused | Files of any type |
| `ui/screens/tunnels/components/TunnelStatisticsRow.kt` | Per-peer sections replaced by `QuartzIpInfo(activeTunnel)` (1 line) | Connection info: exit IP, location, ISP |
| `ui/screens/tunnels/components/TunnelList.kt` | Row description wrapped in `quartzTunnelDescription(…)` (expiry badge, done); tunnel tap → `quartzTunnelRoute`; `QuartzRowExtras(row)` under each row (a Clash tunnel's groups and nodes); latency (`QuartzLatencyText`) before the switch on the right; list wrapped in `QuartzPullToRefresh`; normal rows wrapped in `ReorderableItem` with a long-press drag handle (`QuartzTunnelDrag`, desktop's `moveDisplayedRows` port in `QuartzTunnelOrder`), and `ListRow`'s `onLongClick` off on phones so the hold reaches the handle: hold and drag reorders, hold and release selects; engine/delay badges, group toggle + dropdown, connection info later | Groups, UI |
| Group create/rename UI (`GroupNameDialog.kt`, `GroupActionsSheet.kt`) | Group type and settings | Groups |
| `AndroidManifest.xml` | Intent filters for files, share and `clash://` deep links | Import |
| `.github/dependabot.yml` | Deleted | No dependency PRs; bumps come with upstream. `quartz-release` keeps it deleted when upstream changes it (and any other file we deleted). |
| `.gitmodules` (engines) | `quartz/android/engines/mihomo` submodule entry (its name is still `engines/mihomo`) | mihomo engine |
| `.gitmodules` | Entry for `desktop/daemon/winsw` (copied from `desktop/.gitmodules`; git only reads the root file). Update it if desktop changes that submodule's URL. | Desktop build |
| `app/.../MainActivity.kt` | `Route.Support` shows `quartz/ui/QuartzInfoScreen` instead of `SupportScreen` (import + one line) | Info tab: About, Updates (this repo's releases), upstream version check, WARP generator |
| `desktop/shared/.../core/profile/AppVariant.kt` | `quartz` names for data/IPC folders, keyring, lock; `qtz` interface prefix | Install next to WG Tunnel Desktop |
| `desktop/composeApp/build.gradle.kts` | `appFsName`/`appDisplayName` = quartz/Quartz, vendor, description; `publish.github` → thebingchilling/quartz | Rebrand, updater |
| `desktop/composeApp/src/jvmMain/composeResources/values/strings.xml` | `app_name`, `tray_open` → Quartz | Window title, tray |
| `desktop/packaging/windows/icon.ico`, `desktop/composeApp/src/jvmMain/composeResources/drawable/appicon.png`, `titleicon.svg` | Replaced with the Quartz icon (generated from `quartz/android/branding/icon`; `titleicon.svg` is the monochrome glyph cropped to `viewBox="27 27 54 54"`). On conflict, keep ours. | Desktop icon, tray, title bar |
| `desktop/composeApp/.../desktop/update/AppUpdater.kt` | `GitHubProvider` → thebingchilling/quartz | Updater must not offer WG Tunnel |

UI rules: prefer resource overlays (`quartz/android/branding`) → new screens in
`quartz/android/*` → small, `[fork-ui]`-prefixed edits to upstream screens, keeping our
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
2. WARP generator, slots and expiry (pure app code; proves the Settings hook). Done (§9 "Implementation status"); groups and Clash parts land with steps 3 and 5.
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
