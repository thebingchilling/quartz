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
    final action = actionFromJson(call.arguments);
    if (action == null || !mounted) return false;
    await applyAutoTunnelAction(ref, action);
    return true;
  }

  Future<void> _adopt() async {
    final action = await takeAppliedAction();
    if (action == null || !mounted) return;
    await applyAutoTunnelAction(ref, action, connect: false);
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
  static const _appPoll = Duration(seconds: 2);
  static const _linger = Duration(seconds: 4);
  static const _settle = Duration(seconds: 2);
  static const _rejudge = Duration(milliseconds: 500);

  StreamSubscription<List<ConnectivityResult>>? _subscription;
  List<ConnectivityResult> _results = const [];
  Timer? _timer;
  Timer? _appTimer;
  Timer? _lingerTimer;
  List<String> _openApps = const [];
  List<String> _appsForRules = const [];
  String? _lastKey;
  String? _lastFirewall;
  String? _active;
  bool _manual = false;
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
      if (!_switching && previous != null && previous != running) {
        if (running) {
          _manual = true;
          _active = null;
        } else {
          _manual = false;
          _lastKey = null;
          _schedule(_rejudge);
        }
      }
      _lastFirewall = null;
      unawaited(_syncFirewall());
    });
    ref.listenManual(currentProfileIdProvider, (_, _) {
      unawaited(_syncFirewall());
    });
    ref.listenManual(
      vpnSettingProvider.select((state) => state.accessControlProps),
      (previous, next) {
        if (previous == next) return;
        ref
            .read(setupActionProvider.notifier)
            .applyProfileDebounce(silence: true, force: true);
      },
    );
    _appTimer = Timer.periodic(_appPoll, (_) => unawaited(_pollApps()));
    if (system.isWindows) runAfterFrame(_forceTun);
  }

  /// Quartz is only ever a full VPN: TUN goes back on at every launch, even
  /// after a declined admin prompt turned it off for that session.
  void _forceTun() {
    if (!mounted || ref.read(patchClashConfigProvider).tun.enable) return;
    ref
        .read(patchClashConfigProvider.notifier)
        .update((state) => state.copyWith.tun(enable: true));
  }

  Future<void> _pollApps() async {
    final settings = ref.read(autoTunnelProvider);
    final watched = settings.watchedApps;
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

  void _schedule([Duration delay = _settle]) {
    _timer?.cancel();
    _timer = Timer(delay, () => unawaited(_apply()));
  }

  Future<NetworkSnapshot> _snapshot() async {
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
    if (!settings.enabled) _active = null;
    await _syncFirewall();
    if (!settings.enabled || _manual || !mounted) return;
    final action = decideAutoTunnel(
      settings,
      network,
      openApps: _appsForRules,
      active: _active,
    );
    final key = '${network.kind}|${network.ssid}|${action?.key}';
    if (!mounted || key == _lastKey) return;
    _lastKey = key;
    if (action == null) return;
    commonPrint.log('Auto-tunnel: $network -> ${action.key}');
    _active = action.ruleId;
    await _syncFirewall();
    _switching = true;
    try {
      await applyAutoTunnelAction(ref, action);
    } finally {
      _switching = false;
    }
  }

  /// The kill switch blocks every app but the Core and the TUN adapter.
  Future<void> _syncFirewall() async {
    if (!mounted || !system.isWindows) return;
    final blockAll = blocksAll(
      ref.read(autoTunnelProvider),
      active: _active,
      running: ref.read(isStartProvider),
    );
    final tunAlias = ref.read(patchClashConfigProvider).tun.device;
    final key = '$blockAll|$tunAlias';
    if (key == _lastFirewall) return;
    _lastFirewall = key;
    try {
      await helperClient.applyFirewall(
        blockAll: blockAll,
        apps: const [],
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
