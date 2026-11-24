package core

import (
	"sync"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// Stock mihomo doesn't count proxied traffic apart from direct, so
// onlyStatisticsProxy no longer changes the figures.
func handleGetTraffic(onlyStatisticsProxy bool) Traffic {
	up, down := statistic.DefaultManager.Now()
	return Traffic{Up: up, Down: down}
}

func handleGetTotalTraffic(onlyStatisticsProxy bool) Traffic {
	up, down := statistic.DefaultManager.Total()
	return Traffic{Up: up, Down: down}
}

func handleResetTraffic() {
	statistic.DefaultManager.ResetStatistic()
}

func handleGetConnections() *statistic.Snapshot {
	return statistic.DefaultManager.Snapshot()
}

func handleGetConnectionCount() int {
	count := 0
	statistic.DefaultManager.Range(func(statistic.Tracker) bool {
		count++
		return true
	})
	return count
}

func handleCloseConnections() bool {
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})
	return true
}

func handleResetConnections() bool {
	resolver.ResetConnection()
	return true
}

// A connection that went out over a network the device has left hangs until
// TCP gives up on it, and so do the DNS clients' pooled connections.
func handleResetNetwork() {
	handleCloseConnections()
	resolver.ResetConnection()
}

type sleepTracker interface {
	ID() string
	Info() *statistic.TrackerInfo
	Close() error
}

var (
	sleepMu       sync.Mutex
	sleepReceived map[string]int64
)

func rangeTrackers(f func(sleepTracker)) {
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		f(c)
		return true
	})
}

func markSleep(each func(func(sleepTracker))) {
	received := map[string]int64{}
	each(func(c sleepTracker) {
		received[c.ID()] = c.Info().DownloadTotal.Load()
	})
	sleepMu.Lock()
	sleepReceived = received
	sleepMu.Unlock()
}

// Carriers drop idle mappings in sleep silently; a connection that received data is alive, so calls survive.
func sleptConnections(each func(func(sleepTracker))) []sleepTracker {
	sleepMu.Lock()
	received := sleepReceived
	sleepReceived = nil
	sleepMu.Unlock()
	var stale []sleepTracker
	each(func(c sleepTracker) {
		before, known := received[c.ID()]
		if known && c.Info().DownloadTotal.Load() == before {
			stale = append(stale, c)
		}
	})
	return stale
}

func handleMarkSleep() {
	markSleep(rangeTrackers)
}

// Read the baseline before a markSleep that may follow at once; only the closing goes async.
func handleResetSleptConnections() {
	stale := sleptConnections(rangeTrackers)
	safeGo("resetSleptConnections", func() {
		for _, c := range stale {
			_ = c.Close()
		}
		resolver.ResetConnection()
	})
}

func handleCloseConnection(connectionId string) bool {
	c := statistic.DefaultManager.Get(connectionId)
	if c == nil {
		return false
	}
	_ = c.Close()
	return true
}
