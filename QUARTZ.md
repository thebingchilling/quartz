# Quartz

Quartz is built on **FlClash v0.9.0** (copied, not forked: tag `v0.9.0`,
commit `03f9553`, GPL-3.0). Only v0.9.0 is used; later FlClash releases are not merged.

## Stock mihomo

FlClash ships a patched mihomo (`chen08209/Clash.Meta`, branch `FlClash`: v1.19.32 plus
two commits). Here the core uses **stock `github.com/metacubex/mihomo`** as a normal Go
module (`core/go.mod`), so updating mihomo is a version bump plus `go mod tidy`; keep
`core_version` in `build_config.yaml` in step. The `update-mihomo` workflow (Actions → update-mihomo
→ Run workflow, optionally with a tag) does exactly that, checks the Core still builds for every
target, commits to `main` and starts the release build; when a check fails it pushes nothing and
leaves a report on the run for an agent. Standing rule: mihomo stays identical to its upstream
release (no fork, `replace`, vendoring or patch); FlClash's wrapper in `core/` may change when
necessary, and what can live in the app lives there.

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
| Dialer-proxy loop guard | the app: `loopingDialers()` (`lib/common/proxy_list.dart`) refuses such a profile on import and drops the looping dialers from the config it applies |

Dropped (VPN-only use doesn't need them):

- DNS query log (`dns.QueryRecord`): the DNS page and dashboard card are hidden.
- Proxy-only traffic figures: the figures count all traffic and the "only proxy" option is gone.
- `use-position` in proxy groups.
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
- **Network card** (`lib/views/dashboard/widgets/network_detection.dart`): flag, country,
  IP, city/region and ISP, from ip-api.com, ipinfo.io and ipwho.is asked at once
  (`lib/common/ip_details.dart`); a long press shows the details with coordinates and a
  Google Maps link. Requests go the way the app's own traffic goes, on fresh connections, and
  are asked again once the Core's listener has actually started or stopped
  (`routeEpochProvider`), when a profile finishes applying while running, on a node switch, or
  on a tap. Its IP is green while no tunnel runs.
- **WARP generator** (Tools → WARP generator, `lib/features/warp`): registers with
  Cloudflare's WARP API (X25519 keys in Dart, API versions tried in turn), WireGuard or
  AmneziaWG (junk Jc/Jmin/Jmax; I1 from `assets/data/warp-awg-payloads.txt`), endpoint
  default/random/custom, MTU, DNS, keepalive. Each one becomes a Clash profile with a
  `wireguard` proxy in a PROXY group; the list shows days left (90 days, or Cloudflare's
  TTL) with regenerate (new registration into the same profile) and delete (revokes it);
  the profile list greys a WARP profile out once expired, and while one runs a bar above its
  proxies shows the days left and the expiry date with a regenerate button that replaces the
  registration straight away. There is one profile per variant (`warp`, `warp awg`): generating
  a variant that exists regenerates it with the chosen options.
- **Proxy lists as subscriptions** (`validateProfile` in `lib/core/controller.dart`,
  `parseBareSocksList` in `lib/common/proxy_list.dart`): a link that serves share links, or bare
  `host:port` / `host:port:user:pass` lines (read as SOCKS5), becomes a profile; one whose
  proxies sit in no group gets a PROXY group with an Auto pick and `MATCH,PROXY`, since
  stock mihomo leaves them only in its built-in GLOBAL, which the proxies page hides.
- **Node summaries** (`lib/common/node_summary.dart`, `nodeSummariesProvider`): each proxy
  card's second line reads like `VLESS · Vision · Reality`, `Shadowsocks · rc4-md5`,
  `WireGuard · AmneziaWG` or `OpenVPN · UDP`: the protocol and what sets its nodes apart, no more,
  wrapping onto a second line rather than being cut off. The app reads them from the config it applied
  (overrides and scripts included) and each proxy provider's file, renamed the way the
  provider's `override` renames them; share-link provider files go through the Core's existing
  `convertProxies`. Nothing in `core/` is involved.
- **Windscribe** (Tools → Windscribe, `lib/features/windscribe`): Windscribe's public server
  list as a Clash profile of `openvpn` proxies with the OpenVPN credentials from its config
  generator. The page picks one location (New Zealand first); the profile then holds only that
  country's servers in its one Windscribe group, so the proxies page offers just those. The page
  lists updating the server list, protocol and port first, then the locations, with the
  credentials last; on the proxies page a bar has buttons to refresh the server list and change
  protocol or port (a rewrite from the saved list, no download) above the country. The list
  refreshes at startup when it is over 24 hours old, or by hand.
- **No excess logins (standing rule):** nothing contacts Windscribe or WARP on its own beyond
  that daily list refresh. Delay tests run only when asked, every node of a group together, with OpenVPN logins started a second apart; a failed test runs once more on the tunnel the first attempt set
  up, never an OpenVPN one; a rewrite that leaves the Windscribe profile unchanged doesn't re-apply it (an apply
  reconnects); converted OpenVPN nodes stay out of the automatic url-test group; WARP's
  registration API is only called to generate, regenerate or delete.
- **Auto-tunnel and kill switch** (Settings → Quartz → Auto-tunnel, Android and Windows): a
  list of **rules**, each IF networks (any Wi-Fi, a named Wi-Fi with `*`/`?` wildcards, any
  mobile data, a SIM) AND or OR apps (any of them open) THEN connect its own tunnel (a profile
  with an optional server) or disconnect, and WHEN IT ENDS disconnect, leave the tunnel as it is
  or connect another. An empty condition isn't asked; there is no default tunnel and no built-in
  rule. Every choice opens a page of rows to tap, not a radio dialog. Rules can sit in **categories** that turn on and off together.
  Rules with apps are checked first for the most recently opened app, then the rest from the top
  (long press to reorder); the first match wins and becomes the rule in effect. When it stops
  matching, the next match takes over; if none does, its end action runs, and with no rule in
  effect nothing changes. With no network the tunnel stops, unless the rule in effect has a kill
  switch. A tunnel the user starts holds until the user stops it, and then the rules judge again
  at once (`decideAutoTunnel`, `blocksAll`, `stepOpenApps` in Dart, `AutoTunnelRules` in Kotlin;
  keep them the same). Settings from the version with one default tunnel and kill switch keep
  their rules, take that tunnel and kill switch, and lose the seeded "any Wi-Fi: disconnect".
  The app picker opens on user apps with the chosen ones first (on Windows: Start menu programs
  and running ones, plus Browse for an `.exe`; Windows access control uses the same picker); Other profiles lists every app of the
  work profile, Secure Folder (Samsung users 150 and up, asked for by ID) and Dual Messenger (95)
  that `LauncherApps` reveals, apps also installed outside them included, labelled with where they
  are, plus those app detection has seen. On Windows the page asks for location when Windows 11
  hides the Wi-Fi name a rule needs. An app counts as open while on screen or until it's closed (removed from recents on Android,
  exited on Windows), with 4 s of grace; networks settle for 2 s before a decision.
  - Kill switch: per rule, or for every connecting rule of a category. While that rule is in
    effect and its tunnel isn't up (connecting, dropped, no network, or stopped by the user)
    every app is blocked, until the tunnel is back or a rule without one takes over.
  - Battery: app detection (which polls usage data) only runs while a rule has apps, and the
    service parses its settings once per change instead of on every network callback.
  - Android: `AutoTunnelService` (foreground, `specialUse`, back after a reboot; running
    whenever auto-tunnel is on) watches the network and, through
    `AppWatcherService` (accessibility, sees which app is in front in every profile) or
    usage access, the apps. While Quartz's UI runs it asks Dart to switch (`apply` on the
    `autotunnel` channel); otherwise it switches itself from configs Dart renders into
    `files/autotunnel/<profile>.yaml` (keyed by `TunnelTarget.key`, the current profile
    included), leaving the decision in `quartz.autoTunnel.applied` for Dart to adopt.
    `AutoTunnelState` holds the rule in effect and tells the rules' starts and stops from the
    user's. `KillSwitch` watches `ServiceState.runState` and starts `KillSwitchService`, a VPN
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
- **Windows updates** install silently and start Quartz again, like Android's.
- **Standalone Windows EXE** (`quartz-windows-amd64-portable.exe`,
  `services/launcher`): one file that embeds the release zip, unpacks it on first run to
  `%LOCALAPPDATA%\Quartz\app\<hash>` (keeping the two newest versions), and starts
  `Quartz.exe` with `QUARTZ_PORTABLE_EXE` set to its own path; the updater then swaps that
  file for the new EXE and restarts. Like the installed app, it is meant to run all the time
  in the tray, where auto-tunnel works: the first run of either turns start at login on (the
  Run entry passes `--autostart`, which opens straight to the tray), closing the window hides
  it to the tray, and launching it again shows the window.
  The launcher drops the EXE's mark of the web once Windows has let it start, so SmartScreen
  asks only on the first launch (an unsigned EXE can't avoid that one).
- **Installer and standalone EXE coexist:** they share the data directory (one runs at a
  time, the single-instance lock sends the other away) and one Helper service. The Helper's
  `install` copies itself and the Core to `%ProgramFiles%\Quartz Helper` and registers from
  there, so either copy finds it, a pruned standalone version never held it, and no
  SYSTEM service runs from user-writable app data; only a Core change asks for admin again.
  Autostart and `clash://` links point at the standalone EXE itself (`appLaunchPath`), not
  the versioned folder it unpacks into. Uninstalling removes the service and that folder.
- **Always a full VPN, never a system proxy:** the system proxy is never set (`proxyState`
  reports it off, which also clears one an older version left), and its switches are gone
  from settings, the dashboard and the tray. Windows turns TUN back on at every launch; if
  the admin prompt for the Helper is declined, Quartz stops instead of running as a proxy.
  Android's VPN is always on, never declares an HTTP proxy and doesn't let apps bypass it.
- **No notification prompt to run (standing rule):** starting never asks for notification
  permission or waits on one. Android runs the VPN's foreground service without it and only
  hides the notification; `POST_NOTIFICATIONS` stays declared so it can be turned on in
  system settings.
- **IPv6 on (standing rule):** the Core's `ipv6`, the Android VPN's IPv6 route and the DNS a
  profile without its own is given all default to on (FlClash had them off, which left IPv6 traffic
  outside Android's VPN); existing installs are switched on once at startup. Don't turn it off.
- **Access control tabs** (Android): User apps and System apps, chosen apps first. Secure Folder and
  the work profile have no tab: Android gives each profile its own VPN, so Quartz's VPN never
  carries their apps' traffic; installing Quartz inside that profile is what covers them.
- **Proxies page:** an automatic group (url-test, fallback, load-balance) that another group
  offers, like a subscription's Auto next to its servers, isn't shown as a group of its own; it
  stays a choice in that group. Picking a server inside a subgroup also picks that subgroup in
  every selector above it.
- **Battery:** the Core's connection poll (for the request log and route probes) sleeps while
  neither is wanted; the app turns request events off while hidden (`setRequestsWanted`).
- **Size:** `GEOIP.dat` and `ASN.mmdb` aren't bundled; mihomo downloads either the first time a
  profile needs it (geodata mode, IP-ASN rules). `GEOIP.metadb` and `GEOSITE.dat` still are.
- **System DNS with the VPN off (Android):** `SystemDnsWatcher` feeds the Core the phone's DNS
  servers for the life of the process; the VPN service's observer only ran while connected, so
  servers given by hostname (all of Windscribe's) failed delay tests until the first connect.
- **No connect/disconnect toasts:** the service no longer shows "Starting VPN..." and "Stopping
  VPN..."; toasts that report a failure stay.
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
`quartz-flclash-0.9.0-<run>`; the release needs both builds. Its files are
`quartz-android-arm64-v8a.apk`, `quartz-android-armeabi-v7a.apk`,
`quartz-windows-amd64-setup.exe` and `quartz-windows-amd64-portable.exe`; the Windows zip is only the portable EXE's input. The
endings are what the updater matches, including in builds already installed, so keep them.
No 32-bit Windows build: Flutter has none.
Android builds are signed with the committed debug key `.github/signing/debug.keystore`.
