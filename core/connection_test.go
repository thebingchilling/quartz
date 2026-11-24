package core

import (
	"testing"

	"github.com/metacubex/mihomo/tunnel/statistic"
)

type fakeTracker struct {
	id   string
	info statistic.TrackerInfo
}

func (f *fakeTracker) ID() string                   { return f.id }
func (f *fakeTracker) Info() *statistic.TrackerInfo { return &f.info }
func (f *fakeTracker) Close() error                 { return nil }

func trackersOf(trackers ...*fakeTracker) func(func(sleepTracker)) {
	return func(f func(sleepTracker)) {
		for _, t := range trackers {
			f(t)
		}
	}
}

func ids(trackers []sleepTracker) []string {
	var out []string
	for _, t := range trackers {
		out = append(out, t.ID())
	}
	return out
}

func TestSleptConnectionsAreTheOnesThatReceivedNothing(t *testing.T) {
	silent := &fakeTracker{id: "silent"}
	silent.info.DownloadTotal.Store(100)
	receiving := &fakeTracker{id: "receiving"}
	sendingOnly := &fakeTracker{id: "sending-only"}
	markSleep(trackersOf(silent, receiving, sendingOnly))

	receiving.info.DownloadTotal.Add(1)
	sendingOnly.info.UploadTotal.Add(64)
	opened := &fakeTracker{id: "opened-while-asleep"}

	got := ids(sleptConnections(trackersOf(silent, receiving, sendingOnly, opened)))
	if len(got) != 2 || got[0] != "silent" || got[1] != "sending-only" {
		t.Fatalf("slept connections = %v, want [silent sending-only]", got)
	}
}

func TestSleptConnectionsUseEachBaselineOnce(t *testing.T) {
	silent := &fakeTracker{id: "silent"}
	if got := sleptConnections(trackersOf(silent)); len(got) != 0 {
		t.Fatalf("without a baseline got %v, want none", ids(got))
	}
	markSleep(trackersOf(silent))
	if got := sleptConnections(trackersOf(silent)); len(got) != 1 {
		t.Fatalf("first wake got %v, want [silent]", ids(got))
	}
	if got := sleptConnections(trackersOf(silent)); len(got) != 0 {
		t.Fatalf("second wake reused the baseline: %v", ids(got))
	}
}
