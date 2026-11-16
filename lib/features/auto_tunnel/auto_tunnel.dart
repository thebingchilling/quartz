import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum TriggerKind { wifi, mobile, ethernet, app }

/// No [profileId] means disconnect.
class TunnelTarget {
  const TunnelTarget({this.profileId, this.group, this.proxy});

  const TunnelTarget.disconnect() : this();

  final int? profileId;
  final String? group;
  final String? proxy;

  bool get disconnects => profileId == null;

  Map<String, String> get picks => {
    if (group != null && proxy != null) group!: proxy!,
  };

  Map<String, dynamic> toJson() => {
    'profileId': profileId,
    'group': group,
    'proxy': proxy,
  };

  factory TunnelTarget.fromJson(Map<String, dynamic>? json) => TunnelTarget(
    profileId: json?['profileId'] as int?,
    group: json?['group'] as String?,
    proxy: json?['proxy'] as String?,
  );

  @override
  bool operator ==(Object other) =>
      other is TunnelTarget &&
      other.profileId == profileId &&
      other.group == group &&
      other.proxy == proxy;

  @override
  int get hashCode => Object.hash(profileId, group, proxy);
}

/// Empty [ssids] or [simIds] match any Wi-Fi or SIM.
class AutoTunnelRule {
  const AutoTunnelRule({
    required this.id,
    required this.kind,
    this.enabled = true,
    this.ssids = const [],
    this.simIds = const [],
    this.apps = const [],
    this.target = const TunnelTarget.disconnect(),
  });

  final String id;
  final TriggerKind kind;
  final bool enabled;
  final List<String> ssids;
  final List<int> simIds;
  final List<String> apps;
  final TunnelTarget target;

  AutoTunnelRule copyWith({
    TriggerKind? kind,
    bool? enabled,
    List<String>? ssids,
    List<int>? simIds,
    List<String>? apps,
    TunnelTarget? target,
  }) => AutoTunnelRule(
    id: id,
    kind: kind ?? this.kind,
    enabled: enabled ?? this.enabled,
    ssids: ssids ?? this.ssids,
    simIds: simIds ?? this.simIds,
    apps: apps ?? this.apps,
    target: target ?? this.target,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'kind': kind.name,
    'enabled': enabled,
    'ssids': ssids,
    'simIds': simIds,
    'apps': apps,
    'target': target.toJson(),
  };

  factory AutoTunnelRule.fromJson(Map<String, dynamic> json) => AutoTunnelRule(
    id: json['id'] as String,
    kind: TriggerKind.values.asNameMap()[json['kind']] ?? TriggerKind.wifi,
    enabled: json['enabled'] as bool? ?? true,
    ssids: (json['ssids'] as List?)?.cast<String>() ?? const [],
    simIds: (json['simIds'] as List?)?.cast<int>() ?? const [],
    apps: (json['apps'] as List?)?.cast<String>() ?? const [],
    target: TunnelTarget.fromJson((json['target'] as Map?)?.cast()),
  );
}

enum FallbackMode { keep, disconnect, target }

class AutoTunnelSettings {
  const AutoTunnelSettings({
    this.enabled = false,
    this.rules = const [],
    this.fallback = FallbackMode.keep,
    this.fallbackTarget = const TunnelTarget.disconnect(),
  });

  final bool enabled;
  final List<AutoTunnelRule> rules;
  final FallbackMode fallback;
  final TunnelTarget fallbackTarget;

  AutoTunnelSettings copyWith({
    bool? enabled,
    List<AutoTunnelRule>? rules,
    FallbackMode? fallback,
    TunnelTarget? fallbackTarget,
  }) => AutoTunnelSettings(
    enabled: enabled ?? this.enabled,
    rules: rules ?? this.rules,
    fallback: fallback ?? this.fallback,
    fallbackTarget: fallbackTarget ?? this.fallbackTarget,
  );

  Map<String, dynamic> toJson() => {
    'enabled': enabled,
    'rules': [for (final rule in rules) rule.toJson()],
    'fallback': fallback.name,
    'fallbackTarget': fallbackTarget.toJson(),
  };

  factory AutoTunnelSettings.fromJson(Map<String, dynamic> json) =>
      AutoTunnelSettings(
        enabled: json['enabled'] as bool? ?? false,
        rules: [
          for (final rule in (json['rules'] as List?) ?? const [])
            AutoTunnelRule.fromJson((rule as Map).cast()),
        ],
        fallback:
            FallbackMode.values.asNameMap()[json['fallback']] ??
            FallbackMode.keep,
        fallbackTarget: TunnelTarget.fromJson(
          (json['fallbackTarget'] as Map?)?.cast(),
        ),
      );
}

enum NetworkKind { wifi, mobile, ethernet, none }

typedef NetworkSnapshot = ({NetworkKind kind, String? ssid, int? simId});

/// Null leaves the tunnel as it is. Mirrored by Android's `AutoTunnelRules`;
/// keep both in step.
(String, TunnelTarget)? decideAutoTunnel(
  AutoTunnelSettings settings,
  NetworkSnapshot network, {
  List<String> openApps = const [],
}) {
  final rules = settings.rules.where((rule) => rule.enabled);
  for (final app in openApps) {
    for (final rule in rules) {
      if (rule.kind == TriggerKind.app && rule.apps.contains(app)) {
        return (rule.id, rule.target);
      }
    }
  }
  if (network.kind == NetworkKind.none) return null;
  for (final rule in rules) {
    final holds = switch (rule.kind) {
      TriggerKind.app => false,
      TriggerKind.ethernet => network.kind == NetworkKind.ethernet,
      TriggerKind.mobile =>
        network.kind == NetworkKind.mobile &&
            (rule.simIds.isEmpty || rule.simIds.contains(network.simId)),
      TriggerKind.wifi =>
        network.kind == NetworkKind.wifi &&
            (rule.ssids.isEmpty || rule.ssids.contains(network.ssid)),
    };
    if (holds) return (rule.id, rule.target);
  }
  return switch (settings.fallback) {
    FallbackMode.keep => null,
    FallbackMode.disconnect => ('fallback', const TunnelTarget.disconnect()),
    FallbackMode.target => ('fallback', settings.fallbackTarget),
  };
}

/// A null [open] means only the foreground app is known to be open.
List<String> stepOpenApps(
  List<String> recent, {
  required String? foreground,
  required Set<String>? open,
  required Set<String> ruleApps,
}) {
  var next = recent;
  if (foreground != null && ruleApps.contains(foreground)) {
    next = [foreground, ...recent.where((app) => app != foreground)];
  }
  return [
    for (final app in next)
      if (ruleApps.contains(app) &&
          (app == foreground || (open != null && open.contains(app))))
        app,
  ];
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
  bool appWatcher,
  bool usageAccess,
});

class AutoTunnelPlatform {
  const AutoTunnelPlatform();

  static const channel = MethodChannel('$packageName/autotunnel');

  /// Carries pre-rendered configs so the service can switch with the app closed.
  Future<void> update(String json) => channel.invokeMethod('update', json);

  Future<AutoTunnelPermissions> permissions() async {
    final map =
        await channel.invokeMapMethod<String, bool>('permissions') ?? const {};
    return (
      location: map['location'] ?? false,
      backgroundLocation: map['backgroundLocation'] ?? false,
      phone: map['phone'] ?? false,
      appWatcher: map['appWatcher'] ?? false,
      usageAccess: map['usageAccess'] ?? false,
    );
  }

  Future<bool> request(String kind) async =>
      await channel.invokeMethod<bool>('request', kind) ?? false;

  Future<List<String>> wifiNetworks() async =>
      await channel.invokeListMethod<String>('wifiNetworks') ?? const [];

  /// Includes work-profile and Secure Folder apps the app list can't show.
  Future<List<String>> seenApps() async =>
      await channel.invokeListMethod<String>('seenApps') ?? const [];

  Future<List<SimCard>> simCards() async {
    final list = await channel.invokeListMethod<Map>('simCards') ?? const [];
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
  static const key = 'quartz.autoTunnel.v2';
  static const platform = AutoTunnelPlatform();

  @override
  AutoTunnelSettings build() {
    _load();
    return const AutoTunnelSettings();
  }

  Future<void> _load() async {
    final text = (await SharedPreferences.getInstance()).getString(key);
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
    await prefs.setString(key, jsonEncode(state.toJson()));
  }

  static String newId() =>
      DateTime.now().microsecondsSinceEpoch.toRadixString(36);
}
