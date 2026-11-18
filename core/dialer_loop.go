package core

import (
	"fmt"

	"github.com/metacubex/mihomo/config"
)

// mihomo resolves a dialer-proxy on every dial and follows a chain that leads
// back to its proxy until the stack overflows, which no recover catches; the
// patched mihomo FlClash shipped refused such a config, and so does this.
func dialerProxyLoop(raw *config.RawConfig) error {
	dialers := map[string]string{}
	for _, proxy := range raw.Proxy {
		name, _ := proxy["name"].(string)
		dialer, _ := proxy["dialer-proxy"].(string)
		if name != "" && dialer != "" {
			dialers[name] = dialer
		}
	}
	if len(dialers) == 0 {
		return nil
	}
	members := map[string][]string{}
	for _, group := range raw.ProxyGroup {
		name, _ := group["name"].(string)
		list, _ := group["proxies"].([]any)
		for _, member := range list {
			if text, ok := member.(string); ok {
				members[name] = append(members[name], text)
			}
		}
	}
	for _, proxy := range raw.Proxy {
		name, _ := proxy["name"].(string)
		dialer, ok := dialers[name]
		if !ok {
			continue
		}
		seen := map[string]bool{}
		queue := []string{dialer}
		for len(queue) > 0 {
			next := queue[0]
			queue = queue[1:]
			if next == name {
				return fmt.Errorf("proxy %s: dialer-proxy %s leads back to it", name, dialer)
			}
			if seen[next] {
				continue
			}
			seen[next] = true
			if via, ok := dialers[next]; ok {
				queue = append(queue, via)
			}
			queue = append(queue, members[next]...)
		}
	}
	return nil
}
