# Quartz

Quartz is built on **FlClash v0.9.0** (copied, not forked: tag `v0.9.0`,
commit `03f9553`, GPL-3.0). Only v0.9.0 is used; later FlClash releases are not merged.

## Stock mihomo

FlClash ships a patched mihomo (`chen08209/Clash.Meta`, branch `FlClash`: v1.19.32 plus
two commits). Here the core uses **stock `github.com/metacubex/mihomo`** as a normal Go
module (`core/go.mod`), so updating mihomo is a version bump plus `go mod tidy`; keep
`core_version` in `build_config.yaml` in step.

What the patched mihomo did, and what replaces it (`core/stock.go`):

| Fork hook | Now |
|---|---|
| `tunnel.AllProxies`, `ProvidersSnapshot`, `RuleProvidersSnapshot` | `allProxies()`, `tunnel.Providers()`, `tunnel.RuleProviders()` |
| `config.GetProxyNameList` | `proxyNameList()`: members of mihomo's GLOBAL group |
| `GetSubscriptionInfo` | `subscriptionInfo()`: from the provider's JSON |
| `listener.StopListener` | `stopListeners()` |
| Apply without listeners/TUN | `applyMihomoConfig()`: masks ports and TUN while stopped, never mihomo's TUN on Android |
| Geo updater with cancel + status hook | `startGeoUpdater()` / `stopGeoUpdaterNow()` |
| Provider loaded hook | reported after apply (stock waits for providers there) |
| New-connection hook | `startConnectionPoll()` (request log) and `routedConn.Read` (probe routes) |
| `features.Android` / `cmfa` changes | `isAndroid`; the Android core builds with stock's `cmfa` tag (`android/core/build.gradle.kts`) |
| `DefaultTestURL` settable | the core passes its own test URL |

Dropped (VPN-only use doesn't need them):

- DNS query log (`dns.QueryRecord`): the DNS page stays empty.
- Proxy-only traffic figures: the "only proxy" statistics option counts all traffic.
- `use-position` in proxy groups.
- The dialer-proxy loop guard: a profile whose `dialer-proxy` chain loops back on itself
  can crash the core.
- Relaxed certificates for mihomo's own downloads when certificate checks are off
  (`relaxCertVerify` is unused).
- Live delay pushes from mihomo's own URL tests; the core's delay tests return results.

## Quartz changes

- **Name:** Quartz, `app.quartz` on Android, `Quartz.exe` on Windows; FlClash's logo stays.
  Kotlin packages and internal names (`FlClashCore`, `FlClashHelperService`) are unchanged.
- **No Firebase, no forced disclaimer:** no `google-services.json`, Firebase plugins or
  libraries; the Crashlytics setting, the data-collection tip and the disclaimer that had to
  be accepted at first launch are gone (`lib/views/disclaimer.dart` is no longer reachable).
- **Updater** (`lib/common/updater.dart`): reads the latest release of
  `thebingchilling/quartz`, compares its build number (the `-<run>` of
  `quartz-flclash-<version>-<run>`, which CI also writes as the app's build number) and
  downloads the APK for the device's ABI (opened with the system installer, permission
  `REQUEST_INSTALL_PACKAGES`) or the Windows installer (run with `/SILENT`).
- **Defaults:** system theme, dynamic colour, tonal spot, docked navigation bar, Google Sans
  Flex (`assets/fonts`, OFL) as the app font; picking "Default" in the font list uses it.
- **Network card** (`lib/views/dashboard/widgets/network_detection.dart`): two rows high,
  flag, country, IP, city/region and ISP, from ipwho.is, then ipapi.co, then ip-api.com
  (`lib/common/ip_details.dart`). Requests go the way the app's own traffic goes, so it
  answers whether or not the proxy runs; it looks again on connect/disconnect, a node
  switch, or a tap.
- **WARP generator** (Tools → WARP generator, `lib/features/warp`): registers with
  Cloudflare's WARP API (X25519 keys in Dart, API versions tried in turn), WireGuard or
  AmneziaWG (junk Jc/Jmin/Jmax; I1 from `assets/data/warp-awg-payloads.txt`), endpoint
  default/random/custom, MTU, DNS, keepalive. Each one becomes a Clash profile with a
  `wireguard` proxy in a PROXY group; the list shows days left (90 days, or Cloudflare's
  TTL) with regenerate (new registration into the same profile) and delete (revokes it).
- **Auto-tunnel** (Android, Tools → Auto-tunnel): `AutoTunnelService` (foreground,
  `specialUse`) watches the network under the VPN and starts/stops it through
  `ServiceState` like the tile: Wi-Fi by name as a blacklist ("every Wi-Fi except these")
  or whitelist ("only these"), mobile data on every SIM or chosen ones (dual SIM), Ethernet.
  It acts when the network or the rules change, so a manual toggle holds until then.
  Wi-Fi names need location access, all the time for the background; Android doesn't let
  apps list saved networks, so the picker offers nearby ones or a typed name. Rules live in
  native preferences (`AutoTunnel`), and the service comes back after a reboot. Windows has
  no auto-tunnel yet.

## Builds

`.github/workflows/quartz-clash.yml` builds Android and Windows (x64) in one run on pushes
to `main` and by hand, with FlClash's own `setup.dart` (`--env stable`, so no PRE label;
arm and arm64 APKs only), and publishes both as the latest Quartz release
`quartz-flclash-0.9.0-<run>`; the release needs both builds.
Android builds are signed with the committed debug key `.github/signing/debug.keystore`.
FlClash's own `build.yaml` only runs by hand.

## Next

A WARP generator and auto-tunnel features (the earlier WG Tunnel-based Quartz had them; it is
gone from this repository).
