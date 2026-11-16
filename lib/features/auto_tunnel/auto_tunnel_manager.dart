import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
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
        settingsJson(settings, rendered),
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

/// Windows: the same rules as Android's service, watched while the app runs
/// (it lives in the tray).
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

  StreamSubscription<List<ConnectivityResult>>? _subscription;
  List<ConnectivityResult> _results = const [];
  Timer? _timer;
  Timer? _appTimer;
  Timer? _lingerTimer;
  List<String> _openApps = const [];
  List<String> _appsForRules = const [];
  String? _lastKey;

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
    _appTimer = Timer.periodic(_appPoll, (_) => unawaited(_pollApps()));
  }

  Future<void> _pollApps() async {
    final settings = ref.read(autoTunnelProvider);
    final ruleApps = {
      for (final rule in settings.rules)
        if (rule.enabled && rule.kind == TriggerKind.app)
          ...rule.apps.map((app) => app.toLowerCase()),
    };
    if (!settings.enabled || ruleApps.isEmpty) return;
    final next = stepOpenApps(
      _openApps,
      foreground: foregroundExecutable(),
      open: _openApps.isEmpty ? const {} : await runningExecutables(),
      ruleApps: ruleApps,
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
    final settings = ref.read(autoTunnelProvider);
    if (!settings.enabled || !mounted) return;
    final network = await _snapshot();
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
    await applyTunnelTarget(ref, decision.$2);
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
