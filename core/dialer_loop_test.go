package core

import (
	"os"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/config"
)

func rawConfigOf(t *testing.T, text string) *config.RawConfig {
	t.Helper()
	raw, err := config.UnmarshalRawConfig([]byte(text))
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func TestDialerProxyLoopThroughAGroupIsRefused(t *testing.T) {
	raw := rawConfigOf(t, `
proxies:
  - {name: a, type: socks5, server: 1.1.1.1, port: 1, dialer-proxy: chain}
  - {name: b, type: socks5, server: 2.2.2.2, port: 2, dialer-proxy: a}
proxy-groups:
  - {name: chain, type: select, proxies: [DIRECT, b]}
`)

	err := dialerProxyLoop(raw)

	if err == nil || !strings.Contains(err.Error(), "leads back") {
		t.Fatalf("err = %v, want a loop", err)
	}
}

func TestDialerProxyChainWithoutALoopIsKept(t *testing.T) {
	raw := rawConfigOf(t, `
proxies:
  - {name: a, type: socks5, server: 1.1.1.1, port: 1, dialer-proxy: hop}
  - {name: b, type: socks5, server: 2.2.2.2, port: 2}
proxy-groups:
  - {name: hop, type: select, proxies: [b, DIRECT]}
  - {name: out, type: select, proxies: [a, hop]}
`)

	if err := dialerProxyLoop(raw); err != nil {
		t.Fatalf("err = %v, want none", err)
	}
}

func TestHandleValidateConfigRefusesADialerLoop(t *testing.T) {
	path := t.TempDir() + "/config.yaml"
	text := "proxies:\n  - {name: a, type: socks5, server: 1.1.1.1, port: 1, dialer-proxy: a}\n"
	if err := os.WriteFile(path, []byte(text), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := handleValidateConfig(path); err == nil {
		t.Fatal("a proxy dialing through itself validated")
	}
}
