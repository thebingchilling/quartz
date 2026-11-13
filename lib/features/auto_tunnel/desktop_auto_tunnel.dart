import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

import 'auto_tunnel.dart';

/// Auto-tunnel on Windows: the same rules as Android's service, watched while
/// the app runs (it lives in the tray). It acts when the network or the rules
/// change, so a manual toggle holds until then.
class DesktopAutoTunnel extends ConsumerStatefulWidget {
  const DesktopAutoTunnel({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<DesktopAutoTunnel> createState() => _DesktopAutoTunnelState();
}

class _DesktopAutoTunnelState extends ConsumerState<DesktopAutoTunnel> {
  /// Adapters come and go in bursts while switching networks.
  static const _settle = Duration(seconds: 2);

  StreamSubscription<List<ConnectivityResult>>? _subscription;
  List<ConnectivityResult> _results = const [];
  Timer? _timer;
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
    ref.listenManual(autoTunnelProvider, (previous, next) {
      _lastKey = null;
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
    final key = '${network.kind}|${network.ssid}';
    if (!mounted || key == _lastKey) return;
    _lastKey = key;
    final connect = decideAutoTunnel(settings, network);
    if (connect == null || connect == ref.read(isStartProvider)) return;
    commonPrint.log('Auto-tunnel: $network -> $connect');
    await ref.read(setupActionProvider.notifier).setRunning(connect);
  }

  @override
  void dispose() {
    _timer?.cancel();
    unawaited(_subscription?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
