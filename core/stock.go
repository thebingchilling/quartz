package core

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"runtime"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/component/updater"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
	cp "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/listener"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// Everything here is built on stock mihomo's public API, in place of the hooks
// FlClash's patched mihomo added, so mihomo updates with a version bump.

const isAndroid = runtime.GOOS == "android"

const globalGroupName = "GLOBAL"

// allProxies is every proxy the tunnel can route to: the config's own and the
// ones its providers carry.
func allProxies() map[string]constant.Proxy {
	declared := tunnel.Proxies()
	all := make(map[string]constant.Proxy, len(declared))
	for name, proxy := range declared {
		all[name] = proxy
	}
	for _, p := range tunnel.Providers() {
		for _, proxy := range p.Proxies() {
			all[proxy.Name()] = proxy
		}
	}
	return all
}

// proxyNameList is the config's proxies and groups in profile order, which
// mihomo keeps as the members of its GLOBAL group.
func proxyNameList() []string {
	global, ok := adapterAs[outboundgroup.ProxyGroup](tunnel.Proxies()[globalGroupName])
	if !ok {
		return nil
	}
	members := global.Proxies()
	names := make([]string, 0, len(members))
	for _, member := range members {
		names = append(names, member.Name())
	}
	return names
}

// subscriptionInfo reads what stock mihomo only exposes through the provider's JSON.
func subscriptionInfo(p *provider.ProxySetProvider) *provider.SubscriptionInfo {
	data, err := json.Marshal(p)
	if err != nil {
		return nil
	}
	var view struct {
		SubscriptionInfo *provider.SubscriptionInfo `json:"subscriptionInfo"`
	}
	if json.Unmarshal(data, &view) != nil {
		return nil
	}
	return view.SubscriptionInfo
}

// stopListeners closes every inbound listener and mihomo's own TUN. LastTunConf
// is cleared so the next start opens the TUN again even with the same settings.
func stopListeners() {
	listener.PatchInboundListeners(map[string]constant.InboundListener{}, tunnel.Tunnel, true)
	listener.ReCreateHTTP(0, tunnel.Tunnel)
	listener.ReCreateSocks(0, tunnel.Tunnel)
	listener.ReCreateRedir(0, tunnel.Tunnel)
	listener.ReCreateTProxy(0, tunnel.Tunnel)
	listener.ReCreateMixed(0, tunnel.Tunnel)
	listener.ReCreateShadowSocks("", tunnel.Tunnel)
	listener.ReCreateVmess("", tunnel.Tunnel)
	listener.ReCreateTuic(LC.TuicServer{}, tunnel.Tunnel)
	listener.Cleanup()
	listener.LastTunConf = LC.Tun{}
}

// applyMihomoConfig applies cfg without letting mihomo open what the host owns:
// no listeners while stopped, and never mihomo's own TUN on Android, where the
// TUN comes from the VpnService. updateListeners opens them afterwards.
// Stock mihomo waits for providers to load here, so they're reported loaded after.
func applyMihomoConfig(cfg *config.Config) {
	general := *cfg.General
	if !isRunning.Load() {
		general.Port, general.SocksPort, general.RedirPort, general.TProxyPort, general.MixedPort = 0, 0, 0, 0, 0
		general.ShadowSocksConfig, general.VmessConfig = "", ""
		general.TuicServer = LC.TuicServer{}
		general.Tun.Enable = false
	}
	if isAndroid {
		general.Tun.Enable = false
	}
	masked := *cfg
	masked.General = &general
	if !isRunning.Load() {
		masked.Listeners = map[string]constant.InboundListener{}
	}
	hubApplyConfig(&masked)
	for name, p := range tunnel.Providers() {
		if p.VehicleType() != cp.Compatible {
			onProviderLoaded(name)
		}
	}
	for name, p := range tunnel.RuleProviders() {
		if p.VehicleType() != cp.Compatible {
			onProviderLoaded(name)
		}
	}
}

var (
	hubApplyConfig = hub.ApplyConfig
	statFile       = os.Stat
)

func hasPendingProbeRoutes() bool {
	pending := false
	pendingProbeRoutes.Range(func(_, _ any) bool {
		pending = true
		return false
	})
	return pending
}

func onProviderLoaded(name string) {
	scheduleReclaimOwnership()
	scheduleLoadMemoryRelease()
	sendMessage(LoadedMessage, name)
}

// The GEO auto-updater: stock's can't be stopped, so the core runs its own.
var (
	geoUpdaterMu     sync.Mutex
	geoUpdaterCancel context.CancelFunc
)

func startGeoUpdater() {
	geoUpdaterMu.Lock()
	defer geoUpdaterMu.Unlock()
	stopGeoUpdaterLocked()
	interval := time.Duration(updater.GeoUpdateInterval()) * time.Hour
	if interval <= 0 {
		log.Infoln("[GEO] Invalid update interval: %v", interval)
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	geoUpdaterCancel = cancel
	safeGo("geoUpdater", func() {
		if last, err := geoUpdatedAt(); err == nil && time.Since(last) >= interval {
			runGeoUpdate()
		}
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				runGeoUpdate()
			}
		}
	})
}

func stopGeoUpdaterNow() {
	geoUpdaterMu.Lock()
	defer geoUpdaterMu.Unlock()
	stopGeoUpdaterLocked()
}

func stopGeoUpdaterLocked() {
	if geoUpdaterCancel != nil {
		geoUpdaterCancel()
		geoUpdaterCancel = nil
	}
}

const geoUpdateAll = "ALL"

func runGeoUpdate() {
	onGeoUpdate(geoUpdateAll, true, false, nil)
	err := updater.UpdateGeoDatabases()
	skipped := errors.Is(err, updater.ErrGetDatabaseUpdateSkip)
	if skipped {
		err = nil
	}
	onGeoUpdate(geoUpdateAll, false, skipped, err)
}

func geoUpdatedAt() (time.Time, error) {
	for _, file := range []string{constant.Path.GeoIP(), constant.Path.MMDB(), constant.Path.ASN(), constant.Path.GeoSite()} {
		if info, err := statFile(file); err == nil {
			return info.ModTime(), nil
		}
	}
	return time.Time{}, errors.New("no GEO database yet")
}

// Stock mihomo has no hook for new connections, so they're found by polling:
// for the request log and for probes waiting to learn their route.
const connectionPollInterval = 300 * time.Millisecond

var connectionPoll sync.Once

func startConnectionPoll() {
	connectionPoll.Do(func() {
		safeGo("connectionPoll", func() {
			seen := map[string]struct{}{}
			ticker := time.NewTicker(connectionPollInterval)
			defer ticker.Stop()
			for range ticker.C {
				listening := hasEventListener()
				probing := hasPendingProbeRoutes()
				if !listening && !probing {
					clear(seen)
					continue
				}
				alive := make(map[string]struct{}, len(seen))
				statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
					id := c.ID()
					alive[id] = struct{}{}
					if _, known := seen[id]; !known {
						if probing {
							notifyProbeRoute(c)
						}
						if listening {
							sendMessage(RequestMessage, c)
						}
					}
					return true
				})
				seen = alive
			}
		})
	})
}
