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

## Firebase removed

FlClash's Firebase Analytics and Crashlytics (its developer's project) are gone from the
Android build: no `google-services.json`, no Firebase plugins or libraries, and
`GlobalState.setCrashlytics` does nothing. The Dart settings toggle and disclaimer text
for it are still there, to be removed.

## Builds

`.github/workflows/quartz-clash.yml` builds Android and Windows (x64) in one run on pushes
to `main` and by hand, with FlClash's own `setup.dart`, and publishes both as a Quartz
release `quartz-flclash-0.9.0-<run>`.
Android builds are signed with the committed debug key `.github/signing/debug.keystore`.
FlClash's own `build.yaml` only runs by hand.

## Next

A WARP generator and auto-tunnel features (the earlier WG Tunnel-based Quartz had them; it is
gone from this repository).
