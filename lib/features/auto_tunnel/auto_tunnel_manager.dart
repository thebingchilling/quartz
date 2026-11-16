import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/desktop/helper_client.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

import 'auto_tunnel.dart';
import 'auto_tunnel_apply.dart';
import 'windows_apps.dart';

/// Android's service watches networks and apps; this answers its switches
/// while the app runs and keeps the configs it switches to when closed.
class AndroidAutoTunnel extends ConsumerStatefulWidget {
  const AndroidAutoTunnel({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<AndroidAutoTunnel> createState() => _AndroidAutoTunnelState();
}

class _AndroidAutoTunnelState extends ConsumerState<AndroidAutoTunnel> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    AutoTunnelPlatform.channel.setMethodCallHandler(_handle);
    ref.listenManual(autoTunnelProvider, (_, _) => _schedule());
    ref.listenManual(profilesProvider, (_, _) => _schedule());
    ref.listenManual(currentProfileIdProvider, (_, _) => _schedule());
    unawaited(_adopt());
  }

  Future<dynamic> _handle(MethodCall call) async {
    if (call.method != 'apply') return null;
    final target = targetFor(ref.read(autoTunnelProvider), call.arguments);
    if (target == null || !mounted) return false;
    return applyTunnelTarget(ref, target);
  }

  Future<void> _adopt() async {
    final key = await takeAppliedKey();
    if (key == null || !mounted) return;
    final target = targetFor(ref.read(autoTunnelProvider), key);
    if (target != null) await applyTunnelTarget(ref, target, connect: false);
  }

  void _schedule() {
    _timer?.cancel();
    _timer = Timer(const Duration(seconds: 3), () => unawaited(_push()));
  }

  Future<void> _push() async {
    if (!mounted) return;
    final settings = ref.read(autoTunnelProvider);
    final rendered = settings.enabled
        ? await renderTargets(ref, settings)
        : const <String, dynamic>{};
    try {
      await AutoTunnelNotifier.platform.update(
        settingsJson(settings, rendered, ref.read(currentProfileIdProvider)),
      );
    } on PlatformException catch (error) {
      commonPrint.log('Auto-tunnel update failed: $error');
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    AutoTunnelPlatform.channel.setMethodCallHandler(null);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// Windows: the same settings as Android's service, watched while the app
/// runs in the tray. The kill switch lives in the Helper's firewall, so it
/// keeps blocking if Quartz closes.
class DesktopAutoTunnel extends ConsumerStatefulWidget {
  const DesktopAutoTunnel({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<DesktopAutoTunnel> createState() => _DesktopAutoTunnelState();
}

class _DesktopAutoTunnelState extends ConsumerState<DesktopAutoTunnel> {
  static const _settle = Duration(seconds: 2);
  static const _appPoll = Duration(seconds: 2);
  static const _linger = Duration(seconds: 4);
  static const _tunOfferedKey = 'quartz.tunOffered';

  StreamSubscription<List<ConnectivityResult>>? _subscription;
  List<ConnectivityResult> _results = const [];
  NetworkSnapshot _network = (kind: NetworkKind.none, ssid: null, simId: null);
  Timer? _timer;
  Timer? _appTimer;
  Timer? _lingerTimer;
  List<String> _openApps = const [];
  List<String> _appsForRules = const [];
  String? _lastKey;
  String? _lastFirewall;
  bool _manualOff = false;
  bool _switching = false;

  @override
  void initState() {
    super.initState();
    _subscription = Connectivity().onConnectivityChanged.listen((results) {
      _results = results;
      _schedule();
    });
    unawaited(
      Connectivity().checkConnectivity().then((results) {
        _results = results;
        _schedule();
      }),
    );
    ref.listenManual(autoTunnelProvider, (_, _) {
      _lastKey = null;
      _schedule();
    });
    ref.listenManual(isStartProvider, (previous, running) {
      if (running) {
        _manualOff = false;
      } else if (previous == true && !_switching) {
        _manualOff = true;
      }
      _lastFirewall = null;
      unawaited(_syncFirewall());
    });
    ref.listenManual(currentProfileIdProvider, (_, _) {
      unawaited(_syncFirewall());
    });
    _appTimer = Timer.periodic(_appPoll, (_) => unawaited(_pollApps()));
    if (system.isWindows) runAfterFrame(() => unawaited(_offerTun()));
  }

  /// Once, on the first run: TUN sends every app through the tunnel, and
  /// costs one admin prompt to install the Helper.
  Future<void> _offerTun() async {
    final prefs = await SharedPreferences.getInstance();
    if (prefs.getBool(_tunOfferedKey) ?? false) return;
    await prefs.setBool(_tunOfferedKey, true);
    if (!mounted || ref.read(patchClashConfigProvider).tun.enable) return;
    final l = currentAppLocalizations;
    final enable = await dialogs.showMessage(
      title: l.tunOfferTitle,
      message: TextSpan(text: l.tunOfferMessage),
      confirmText: l.tunOfferEnable,
      cancelText: l.tunOfferLater,
    );
    if (enable != true || !mounted) return;
    ref
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith.tun(enable: true));
  }

  Future<void> _pollApps() async {
    final settings = ref.read(autoTunnelProvider);
    final watched = {
      for (final app in settings.apps)
        if (app.target != null) app.detected,
    };
    if (!settings.enabled || watched.isEmpty) return;
    final next = stepOpenApps(
      _openApps,
      foreground: foregroundExecutable(),
      open: _openApps.isEmpty ? const {} : await runningExecutables(),
      ruleApps: watched,
    );
    if (listEquals(next, _openApps)) return;
    _openApps = next;
    _lingerTimer?.cancel();
    if (next.firstOrNull == _appsForRules.firstOrNull) {
      _appsForRules = next;
      return;
    }
    _lingerTimer = Timer(next.isEmpty ? _linger : Duration.zero, () {
      _appsForRules = _openApps;
      _schedule();
    });
  }

  void _schedule() {
    _timer?.cancel();
    _timer = Timer(_settle, () => unawaited(_apply()));
  }

  Future<NetworkSnapshot> _snapshot() async {
    if (_results.contains(ConnectivityResult.ethernet)) {
      return (kind: NetworkKind.ethernet, ssid: null, simId: null);
    }
    if (_results.contains(ConnectivityResult.wifi)) {
      String? ssid;
      try {
        ssid = await WifiSsidManager.instance.getSsid();
      } catch (error) {
        commonPrint.log('Auto-tunnel: no Wi-Fi name ($error)');
      }
      return (kind: NetworkKind.wifi, ssid: ssid, simId: null);
    }
    if (_results.contains(ConnectivityResult.mobile)) {
      return (kind: NetworkKind.mobile, ssid: null, simId: null);
    }
    return (kind: NetworkKind.none, ssid: null, simId: null);
  }

  Future<void> _apply() async {
    if (!mounted) return;
    final settings = ref.read(autoTunnelProvider);
    final network = await _snapshot();
    if (network != _network) _manualOff = false;
    _network = network;
    await _syncFirewall();
    if (!settings.enabled || !mounted) return;
    final decision = decideAutoTunnel(
      settings,
      network,
      openApps: _appsForRules,
    );
    final key = '${network.kind}|${network.ssid}|${decision?.$1}';
    if (!mounted || key == _lastKey) return;
    _lastKey = key;
    if (decision == null) return;
    commonPrint.log('Auto-tunnel: $network -> ${decision.$1}');
    _switching = !decision.$2.disconnects;
    try {
      await applyTunnelTarget(ref, decision.$2);
    } finally {
      _switching = false;
    }
  }

  /// The kill switch blocks every app but the Core and the TUN adapter;
  /// a manual stop releases the connection and tunnel kill switches.
  Future<void> _syncFirewall() async {
    if (!mounted || !system.isWindows) return;
    final settings = ref.read(autoTunnelProvider);
    final blockAll =
        !_manualOff &&
        blocksAll(settings, _network, ref.read(currentProfileIdProvider));
    final apps = [
      for (final app in settings.apps)
        if (app.killSwitch) app.app,
    ];
    final tunAlias = ref.read(patchClashConfigProvider).tun.device;
    final key = '$blockAll|${apps.join('|')}|$tunAlias';
    if (key == _lastFirewall) return;
    _lastFirewall = key;
    try {
      await helperClient.applyFirewall(
        blockAll: blockAll,
        apps: apps,
        tunAlias: tunAlias,
      );
    } on HelperException catch (error) {
      _lastFirewall = null;
      commonPrint.log(
        'Kill switch: the Helper refused ($error)',
        logLevel: LogLevel.warning,
      );
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _appTimer?.cancel();
    _lingerTimer?.cancel();
    unawaited(_subscription?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
