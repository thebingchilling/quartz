// Package main is Quartz's Android wrapper around mihomo, built with
// -buildmode=c-shared into libmihomo.so. Kotlin calls it through jni.c
// (app.quartz.engine.mihomo.MihomoNative). Nothing in quartz/android/engines/mihomo is
// patched: the TUN comes in as a file descriptor (tun.file-descriptor) and
// mihomo's own sockets are protected from the VPN through
// dialer.DefaultSocketHook, the hook mihomo keeps for Android clients.
package main

/*
#include <stdlib.h>
int quartz_protect(int fd);
*/
import "C"

import (
	"errors"
	"os"
	"sync"
	"syscall"
	"unsafe"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/config"
	C2 "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/hub/route"
)

var mu sync.Mutex

func init() {
	// Built with the cmfa tag (mihomo's mode for running inside an Android app), which also turns
	// on "embed mode": that hides PATCH /configs, which the Clash screen uses to switch mode.
	route.SetEmbedMode(false)
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		var protectErr error
		err := conn.Control(func(fd uintptr) {
			if C.quartz_protect(C.int(fd)) == 0 {
				protectErr = errors.New("VpnService.protect failed")
			}
		})
		if err != nil {
			return err
		}
		return protectErr
	}
}

// start runs the profile at configPath with the TUN at tunFd, serving the
// REST API on controller (host:port) with secret. Returns "" or an error.
func start(homeDir, configPath string, tunFd int, controller, secret string) string {
	mu.Lock()
	defer mu.Unlock()
	executor.Shutdown()

	C2.SetHomeDir(homeDir)
	C2.SetConfig(configPath)
	buf, err := os.ReadFile(configPath)
	if err != nil {
		return err.Error()
	}
	raw, err := config.UnmarshalRawConfig(buf)
	if err != nil {
		return err.Error()
	}
	applyAndroidOverrides(raw, tunFd, controller, secret)
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		return err.Error()
	}
	hub.ApplyConfig(cfg)
	return ""
}

// applyAndroidOverrides makes any profile run inside Android's VpnService:
// mihomo reads packets from our TUN instead of creating one, and leaves
// routing and interface binding to Android.
// A negative tunFd runs without a TUN (proxy ports and controller only), for
// host tests.
func applyAndroidOverrides(raw *config.RawConfig, tunFd int, controller, secret string) {
	raw.Tun.Enable = tunFd >= 0
	raw.Tun.FileDescriptor = tunFd
	raw.Tun.AutoRoute = false
	raw.Tun.AutoRedirect = false
	raw.Tun.AutoDetectInterface = false
	raw.Tun.StrictRoute = false
	if len(raw.Tun.DNSHijack) == 0 {
		raw.Tun.DNSHijack = []string{"any:53"}
	}
	// The TUN's DNS server is hijacked into mihomo's resolver, so it must be on.
	if raw.Tun.Enable && !raw.DNS.Enable {
		raw.DNS.Enable = true
		raw.DNS.IPv6 = true
		raw.DNS.EnhancedMode = C2.DNSFakeIP
		if raw.DNS.FakeIPRange == "" {
			raw.DNS.FakeIPRange = "198.18.0.1/16"
		}
		if len(raw.DNS.NameServer) == 0 {
			raw.DNS.NameServer = []string{"https://1.1.1.1/dns-query", "https://dns.google/dns-query"}
		}
		if len(raw.DNS.DefaultNameserver) == 0 {
			raw.DNS.DefaultNameserver = []string{"1.1.1.1", "8.8.8.8"}
		}
	}
	raw.Interface = ""
	raw.RoutingMark = 0
	raw.ExternalController = controller
	raw.ExternalControllerTLS = ""
	raw.ExternalControllerUnix = ""
	raw.ExternalUI = ""
	raw.Secret = secret
}

func stop() {
	mu.Lock()
	defer mu.Unlock()
	executor.Shutdown()
}

// validate parses the profile at configPath without running it.
func validate(homeDir, configPath string) string {
	C2.SetHomeDir(homeDir)
	buf, err := os.ReadFile(configPath)
	if err != nil {
		return err.Error()
	}
	raw, err := config.UnmarshalRawConfig(buf)
	if err != nil {
		return err.Error()
	}
	if _, err := config.ParseRawConfig(raw); err != nil {
		return err.Error()
	}
	return ""
}

//export quartzMihomoStart
func quartzMihomoStart(homeDir, configPath *C.char, tunFd C.int, controller, secret *C.char) *C.char {
	return cString(start(C.GoString(homeDir), C.GoString(configPath), int(tunFd), C.GoString(controller), C.GoString(secret)))
}

//export quartzMihomoStop
func quartzMihomoStop() { stop() }

//export quartzMihomoValidate
func quartzMihomoValidate(homeDir, configPath *C.char) *C.char {
	return cString(validate(C.GoString(homeDir), C.GoString(configPath)))
}

//export quartzMihomoVersion
func quartzMihomoVersion() *C.char { return C.CString(C2.Version) }


// cString returns NULL for "", else a malloc'd copy the caller frees.
func cString(s string) *C.char {
	if s == "" {
		return (*C.char)(unsafe.Pointer(nil))
	}
	return C.CString(s)
}

func main() {}
