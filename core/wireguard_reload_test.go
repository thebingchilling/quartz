package core

import (
	"testing"

	"github.com/metacubex/mihomo/constant"
)

type fakeProxy struct {
	constant.Proxy
	kind   constant.AdapterType
	closed bool
}

func (f *fakeProxy) Type() constant.AdapterType { return f.kind }

func (f *fakeProxy) Close() error {
	f.closed = true
	return nil
}

func TestReloadClosesOnlyTheWireGuardItReplaced(t *testing.T) {
	replaced := &fakeProxy{kind: constant.WireGuard}
	kept := &fakeProxy{kind: constant.WireGuard}
	removed := &fakeProxy{kind: constant.WireGuard}
	other := &fakeProxy{kind: constant.Vless}

	closeReplacedWireGuard(
		map[string]constant.Proxy{"warp": replaced, "home": kept, "gone": removed, "vless": other},
		map[string]constant.Proxy{"warp": &fakeProxy{kind: constant.WireGuard}, "home": kept, "vless": &fakeProxy{kind: constant.Vless}},
	)

	if !replaced.closed || !removed.closed {
		t.Fatalf("replaced closed = %v, removed closed = %v; want both closed", replaced.closed, removed.closed)
	}
	if kept.closed {
		t.Fatal("a WireGuard the reload kept was closed")
	}
	if other.closed {
		t.Fatal("a replaced proxy of another type was closed; mihomo's finalizer handles those")
	}
}
