import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum WifiRuleMode { blacklist, whitelist }

/// When the VPN connects by itself (Android): per Wi-Fi name, per SIM on
/// mobile data, and on Ethernet. The rules act when the network changes.
class AutoTunnelSettings {
  const AutoTunnelSettings({
    this.enabled = false,
    this.wifiEnabled = true,
    this.wifiMode = WifiRuleMode.blacklist,
    this.ssids = const [],
    this.mobileEnabled = true,
    this.simIds = const [],
    this.ethernetEnabled = true,
  });

  final bool enabled;
  final bool wifiEnabled;
  final WifiRuleMode wifiMode;
  final List<String> ssids;
  final bool mobileEnabled;

  /// Empty means every SIM.
  final List<int> simIds;
  final bool ethernetEnabled;

  AutoTunnelSettings copyWith({
    bool? enabled,
    bool? wifiEnabled,
    WifiRuleMode? wifiMode,
    List<String>? ssids,
    bool? mobileEnabled,
    List<int>? simIds,
    bool? ethernetEnabled,
  }) => AutoTunnelSettings(
    enabled: enabled ?? this.enabled,
    wifiEnabled: wifiEnabled ?? this.wifiEnabled,
    wifiMode: wifiMode ?? this.wifiMode,
    ssids: ssids ?? this.ssids,
    mobileEnabled: mobileEnabled ?? this.mobileEnabled,
    simIds: simIds ?? this.simIds,
    ethernetEnabled: ethernetEnabled ?? this.ethernetEnabled,
  );

  Map<String, dynamic> toJson() => {
    'enabled': enabled,
    'wifiEnabled': wifiEnabled,
    'wifiMode': wifiMode.name,
    'ssids': ssids,
    'mobileEnabled': mobileEnabled,
    'simIds': simIds,
    'ethernetEnabled': ethernetEnabled,
  };

  factory AutoTunnelSettings.fromJson(Map<String, dynamic> json) =>
      AutoTunnelSettings(
        enabled: json['enabled'] as bool? ?? false,
        wifiEnabled: json['wifiEnabled'] as bool? ?? true,
        wifiMode:
            WifiRuleMode.values.asNameMap()[json['wifiMode']] ??
            WifiRuleMode.blacklist,
        ssids: (json['ssids'] as List?)?.cast<String>() ?? const [],
        mobileEnabled: json['mobileEnabled'] as bool? ?? true,
        simIds: (json['simIds'] as List?)?.cast<int>() ?? const [],
        ethernetEnabled: json['ethernetEnabled'] as bool? ?? true,
      );
}

class SimCard {
  const SimCard({required this.id, required this.name, required this.slot});

  final int id;
  final String name;
  final int slot;
}

typedef AutoTunnelPermissions = ({
  bool location,
  bool backgroundLocation,
  bool phone,
});

/// The Android side: the network-watching service, nearby Wi-Fi and SIMs.
class AutoTunnelPlatform {
  const AutoTunnelPlatform();

  static const _channel = MethodChannel('$packageName/autotunnel');

  Future<void> update(AutoTunnelSettings settings) =>
      _channel.invokeMethod('update', jsonEncode(settings.toJson()));

  Future<AutoTunnelPermissions> permissions() async {
    final map =
        await _channel.invokeMapMethod<String, bool>('permissions') ?? const {};
    return (
      location: map['location'] ?? false,
      backgroundLocation: map['backgroundLocation'] ?? false,
      phone: map['phone'] ?? false,
    );
  }

  /// `location`, `backgroundLocation` or `phone`.
  Future<bool> request(String kind) async =>
      await _channel.invokeMethod<bool>('request', kind) ?? false;

  Future<List<String>> wifiNetworks() async =>
      await _channel.invokeListMethod<String>('wifiNetworks') ?? const [];

  Future<List<SimCard>> simCards() async {
    final list = await _channel.invokeListMethod<Map>('simCards') ?? const [];
    return [
      for (final item in list)
        SimCard(
          id: item['id'] as int,
          name: item['name'] as String? ?? 'SIM ${(item['slot'] as int) + 1}',
          slot: item['slot'] as int,
        ),
    ];
  }
}

final autoTunnelProvider =
    NotifierProvider<AutoTunnelNotifier, AutoTunnelSettings>(
      AutoTunnelNotifier.new,
    );

class AutoTunnelNotifier extends Notifier<AutoTunnelSettings> {
  static const _key = 'quartz.autoTunnel';
  static const platform = AutoTunnelPlatform();

  @override
  AutoTunnelSettings build() {
    _load();
    return const AutoTunnelSettings();
  }

  Future<void> _load() async {
    final text = (await SharedPreferences.getInstance()).getString(_key);
    if (text == null) return;
    try {
      state = AutoTunnelSettings.fromJson(
        (jsonDecode(text) as Map).cast<String, dynamic>(),
      );
    } on Object catch (error) {
      commonPrint.log('Auto-tunnel settings unreadable: $error');
    }
  }

  Future<void> update(
    AutoTunnelSettings Function(AutoTunnelSettings state) change,
  ) async {
    state = change(state);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, jsonEncode(state.toJson()));
    try {
      await platform.update(state);
    } on PlatformException catch (error) {
      commonPrint.log('Auto-tunnel update failed: $error');
    }
  }
}
