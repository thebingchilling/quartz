import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

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

  static TunnelTarget? fromJson(Object? json) => json is Map
      ? TunnelTarget(
          profileId: json['profileId'] as int?,
          group: json['group'] as String?,
          proxy: json['proxy'] as String?,
        )
      : null;

  @override
  bool operator ==(Object other) =>
      other is TunnelTarget &&
      other.profileId == profileId &&
      other.group == group &&
      other.proxy == proxy;

  @override
  int get hashCode => Object.hash(profileId, group, proxy);
}

enum ConnectionKind { wifi, mobile, ethernet }

/// One connection's settings. A null [ssid] is every other Wi-Fi, a null
/// [simId] every other SIM; a null [target] leaves the tunnel as it is.
class ConnectionSettings {
  const ConnectionSettings({
    required this.kind,
    this.ssid,
    this.simId,
    this.target,
    this.killSwitch = false,
  });

  final ConnectionKind kind;
  final String? ssid;
  final int? simId;
  final TunnelTarget? target;
  final bool killSwitch;

  String get id => '${kind.name}:${ssid ?? simId ?? '*'}';

  ConnectionSettings copyWith({
    TunnelTarget? Function()? target,
    bool? killSwitch,
  }) => ConnectionSettings(
    kind: kind,
    ssid: ssid,
    simId: simId,
    target: target == null ? this.target : target(),
    killSwitch: killSwitch ?? this.killSwitch,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'kind': kind.name,
    'ssid': ssid,
    'simId': simId,
    'target': target?.toJson(),
    'killSwitch': killSwitch,
  };

  factory ConnectionSettings.fromJson(Map<String, dynamic> json) =>
      ConnectionSettings(
        kind:
            ConnectionKind.values.asNameMap()[json['kind']] ??
            ConnectionKind.wifi,
        ssid: json['ssid'] as String?,
        simId: json['simId'] as int?,
        target: TunnelTarget.fromJson(json['target']),
        killSwitch: json['killSwitch'] as bool? ?? false,
      );
}

/// [app] is an Android package or a Windows program's full path.
class AppSettings {
  const AppSettings({
    required this.app,
    required this.label,
    this.target,
    this.killSwitch = false,
  });

  final String app;
  final String label;
  final TunnelTarget? target;
  final bool killSwitch;

  String get id => 'app:$app';

  /// What app detection reports: the package, or the program's file name.
  String get detected =>
      app.contains(_separator) ? app.split(_separator).last.toLowerCase() : app;

  static final _separator = RegExp(r'[\\/]');

  AppSettings copyWith({TunnelTarget? Function()? target, bool? killSwitch}) =>
      AppSettings(
        app: app,
        label: label,
        target: target == null ? this.target : target(),
        killSwitch: killSwitch ?? this.killSwitch,
      );

  Map<String, dynamic> toJson() => {
    'id': id,
    'app': app,
    'detected': detected,
    'label': label,
    'target': target?.toJson(),
    'killSwitch': killSwitch,
  };

  factory AppSettings.fromJson(Map<String, dynamic> json) => AppSettings(
    app: json['app'] as String,
    label: json['label'] as String? ?? json['app'] as String,
    target: TunnelTarget.fromJson(json['target']),
    killSwitch: json['killSwitch'] as bool? ?? false,
  );
}

class AutoTunnelSettings {
  const AutoTunnelSettings({
    this.enabled = false,
    this.connections = const [],
    this.apps = const [],
    this.killSwitchTunnels = const {},
  });

  final bool enabled;
  final List<ConnectionSettings> connections;
  final List<AppSettings> apps;
  final Set<int> killSwitchTunnels;

  bool get usesKillSwitch =>
      killSwitchTunnels.isNotEmpty ||
      connections.any((c) => c.killSwitch) ||
      apps.any((a) => a.killSwitch);

  AutoTunnelSettings copyWith({
    bool? enabled,
    List<ConnectionSettings>? connections,
    List<AppSettings>? apps,
    Set<int>? killSwitchTunnels,
  }) => AutoTunnelSettings(
    enabled: enabled ?? this.enabled,
    connections: connections ?? this.connections,
    apps: apps ?? this.apps,
    killSwitchTunnels: killSwitchTunnels ?? this.killSwitchTunnels,
  );

  AutoTunnelSettings putConnection(ConnectionSettings connection) => copyWith(
    connections: [
      for (final c in connections)
        if (c.id != connection.id) c,
      connection,
    ],
  );

  AutoTunnelSettings putApp(AppSettings app) => copyWith(
    apps: [
      for (final a in apps)
        if (a.id != app.id) a,
      app,
    ],
  );

  Map<String, dynamic> toJson() => {
    'enabled': enabled,
    'connections': [for (final c in connections) c.toJson()],
    'apps': [for (final a in apps) a.toJson()],
    'killSwitchTunnels': killSwitchTunnels.toList(),
  };

  factory AutoTunnelSettings.fromJson(Map<String, dynamic> json) =>
      AutoTunnelSettings(
        enabled: json['enabled'] as bool? ?? false,
        connections: [
          for (final c in (json['connections'] as List?) ?? const [])
            ConnectionSettings.fromJson((c as Map).cast()),
        ],
        apps: [
          for (final a in (json['apps'] as List?) ?? const [])
            AppSettings.fromJson((a as Map).cast()),
        ],
        killSwitchTunnels: {
          for (final id in (json['killSwitchTunnels'] as List?) ?? const [])
            id as int,
        },
      );
}

enum NetworkKind { wifi, mobile, ethernet, none }

/// [ssid] is null when the system hides it.
typedef NetworkSnapshot = ({NetworkKind kind, String? ssid, int? simId});

ConnectionSettings? connectionFor(
  AutoTunnelSettings settings,
  NetworkSnapshot network,
) {
  final kind = switch (network.kind) {
    NetworkKind.wifi => ConnectionKind.wifi,
    NetworkKind.mobile => ConnectionKind.mobile,
    NetworkKind.ethernet => ConnectionKind.ethernet,
    NetworkKind.none => null,
  };
  if (kind == null) return null;
  final ofKind = settings.connections.where((c) => c.kind == kind);
  return switch (kind) {
        ConnectionKind.wifi when network.ssid != null =>
          ofKind.where((c) => c.ssid == network.ssid).firstOrNull,
        ConnectionKind.mobile when network.simId != null =>
          ofKind.where((c) => c.simId == network.simId).firstOrNull,
        _ => null,
      } ??
      ofKind.where((c) => c.ssid == null && c.simId == null).firstOrNull;
}

/// The key of the item that decides ([AppSettings.id] or
/// [ConnectionSettings.id]) and its target, or null to leave the tunnel as it
/// is. An open app (most recent first) wins over the connection. Mirrored by
/// Android's `AutoTunnelRules`; keep both in step.
(String, TunnelTarget)? decideAutoTunnel(
  AutoTunnelSettings settings,
  NetworkSnapshot network, {
  List<String> openApps = const [],
}) {
  for (final open in openApps) {
    for (final app in settings.apps) {
      if (app.detected == open && app.target != null) {
        return (app.id, app.target!);
      }
    }
  }
  final connection = connectionFor(settings, network);
  final target = connection?.target;
  if (connection == null || target == null) return null;
  return (connection.id, target);
}

/// Whether everything is blocked while no tunnel runs: the connection's or
/// the last tunnel's kill switch is on.
bool blocksAll(
  AutoTunnelSettings settings,
  NetworkSnapshot network,
  int? tunnelId,
) =>
    settings.killSwitchTunnels.contains(tunnelId) ||
    (connectionFor(settings, network)?.killSwitch ?? false);

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
  static const key = 'quartz.autoTunnel.v3';
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
