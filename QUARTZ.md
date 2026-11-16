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
- **Auto-tunnel and kill switch** (Settings → Quartz → Auto-tunnel, Android and Windows).
  No rule list: every connection (each named Wi-Fi, "other Wi-Fi", each SIM, "other SIMs",
  Ethernet) and every added app has its own page with **Connect to** (leave as it is,
  disconnect, or a profile with an optional server in one of its groups) and **Kill switch**;
  every tunnel (profile) has a kill switch toggle. An open app wins over the connection
  (most recently used first). It acts only when the connection, the open apps or the
  settings change, so a manual change holds until then (`decideAutoTunnel`, `blocksAll`,
  `stepOpenApps` in Dart, `AutoTunnelRules` in Kotlin; keep them the same). An app counts
  as open while on screen or until it's closed (removed from recents on Android, exited on
  Windows), with 4 s of grace.
  - Kill switch: while no tunnel runs, the connection's or the last tunnel's kill switch
    blocks every app, until the user turns the tunnel off themselves; an app's kill switch
    blocks that app always while no tunnel runs.
  - Android: `AutoTunnelService` (foreground, `specialUse`, back after a reboot; running
    whenever auto-tunnel or any kill switch is on) watches the network and, through
    `AppWatcherService` (accessibility, sees which app is in front in every profile) or
    usage access, the apps. While Quartz's UI runs it asks Dart to switch (`apply` on the
    `autotunnel` channel); otherwise it switches itself from configs Dart renders into
    `files/autotunnel/<profile>.yaml`, leaving `quartz.autoTunnel.applied` for Dart to adopt.
    `KillSwitch` watches `ServiceState.runState` and starts `KillSwitchService`, a VPN
    interface nothing reads (every app but Quartz, or only the kill-switch apps); the real
    tunnel's interface replaces it on start. It can't outlive the app's process, so the page
    links to Android's Always-on VPN + "Block connections without VPN" as well.
  - Windows: `DesktopAutoTunnel` watches connectivity and the foreground program
    (`windows_apps.dart`). The kill switch is WFP filters set by the Helper service
    (`services/helper/src/service/firewall.rs`, `POST /firewall`): in a dynamic session it
    permits loopback, the Core, the TUN adapter (by alias) and DHCP, and blocks everything
    else (or just the kill-switch apps, by full path). They persist while the Helper runs,
    even with Quartz closed; enabling the first kill switch installs the Helper if needed.
- **Config converter** (Settings → Quartz → Config converter, `lib/features/converter`):
  WireGuard/AmneziaWG `.conf` and OpenVPN `.ovpn` files (pasted or opened, several at once)
  become one Clash profile with a PROXY group (and an auto group for more than one).
- **Dashboard cards** resize to any width (2–8 of 8 columns) and height (1–4 rows) from
  the edit mode's corner button; the network card defaults to one full-width row, wraps
  instead of hiding, and opens every field the lookup returned on tap. Its IP is green while
  no tunnel runs.
- **Windows updates** install silently and start Quartz again, like Android's.
- **Standalone Windows EXE** (`Quartz-<version>-windows-amd64-portable.exe`,
  `services/launcher`): one file that embeds the release zip, unpacks it on first run to
  `%LOCALAPPDATA%\Quartz\app\<hash>` (keeping the two newest versions), and starts
  `Quartz.exe` with `QUARTZ_PORTABLE_EXE` set to its own path; the updater then swaps that
  file for the new EXE and restarts.
- **Always a full VPN, never a system proxy:** the system proxy is never set (`proxyState`
  reports it off, which also clears one an older version left), and its switches are gone
  from settings, the dashboard and the tray. Windows turns TUN back on at every launch; if
  the admin prompt for the Helper is declined, Quartz stops instead of running as a proxy.
  Android's VPN is always on, never declares an HTTP proxy and doesn't let apps bypass it.
- **Per-app bypass** (Settings → Quartz → Access control, Android and Windows): a blacklist
  (listed apps go around the tunnel) or a whitelist (only listed apps use it). Android uses
  FlClash's own access control in the VPN service; Windows stores program paths in the same
  setting and puts mihomo rules first in the generated config
  (`lib/features/app_bypass/app_bypass.dart`): `PROCESS-NAME,<exe>,DIRECT` per blacklisted
  program, or one `NOT,((OR,(...))),DIRECT` for a whitelist.

## Android and Windows ship together (standing rule)

Every feature lands on both apps, and every release carries both: the workflow builds them
in one run and publishes only when both succeed. Where a platform can't do something (SIM
choice on Windows), the feature still ships there without that part.

## Builds

Changes are pushed straight to `main` (standing rule), not left on a side branch: a push
to `main` is what builds and publishes the release.

`.github/workflows/quartz-clash.yml` builds Android and Windows (x64) in one run on pushes
to `main` and by hand, with FlClash's own `setup.dart` (`--env stable`, so no PRE label;
arm and arm64 APKs only), and publishes both as the latest Quartz release
`quartz-flclash-0.9.0-<run>`; the release needs both builds.
Android builds are signed with the committed debug key `.github/signing/debug.keystore`.
FlClash's own `build.yaml` only runs by hand.
