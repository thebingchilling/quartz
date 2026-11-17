import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

class TunnelTarget {
  const TunnelTarget({required this.profileId, this.group, this.proxy});

  final int profileId;
  final String? group;
  final String? proxy;

  Map<String, String> get picks => {
    if (group != null && proxy != null) group!: proxy!,
  };

  String get key => '$profileId/${group ?? ''}/${proxy ?? ''}';

  Map<String, dynamic> toJson() => {
    'profileId': profileId,
    'group': group,
    'proxy': proxy,
  };

  static TunnelTarget? fromJson(Object? json) => json is Map
      ? switch (json['profileId']) {
          final int profileId => TunnelTarget(
            profileId: profileId,
            group: json['group'] as String?,
            proxy: json['proxy'] as String?,
          ),
          _ => null,
        }
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

  /// Null connects the default tunnel.
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

/// WG Tunnel's model, plus the SIMs mobile data counts on, apps that bring
/// their own tunnel while open, and kill switches.
class AutoTunnelSettings {
  const AutoTunnelSettings({
    this.enabled = false,
    this.defaultTunnel,
    this.tunnelOnWifi = true,
    this.trustedNetworks = const [],
    this.wifiTunnels = const {},
    this.tunnelOnMobileData = true,
    this.mobileSims = const {},
    this.mobileTunnel,
    this.simTunnels = const {},
    this.stopOnNoNetwork = false,
    this.debounceSeconds = 2,
    this.apps = const [],
    this.killSwitchTunnels = const {},
  });

  final bool enabled;

  /// Null connects whichever profile is current.
  final TunnelTarget? defaultTunnel;

  final bool tunnelOnWifi;

  /// Names, where `*` and `?` match any run of characters and any one.
  final List<String> trustedNetworks;
  final Map<String, TunnelTarget> wifiTunnels;
  final bool tunnelOnMobileData;

  /// Empty means every SIM.
  final Set<int> mobileSims;
  final TunnelTarget? mobileTunnel;
  final Map<int, TunnelTarget> simTunnels;
  final bool stopOnNoNetwork;
  final int debounceSeconds;
  final List<AppSettings> apps;
  final Set<int> killSwitchTunnels;

  static const debounceChoices = [0, 1, 2, 3, 5, 10];

  bool get usesKillSwitch =>
      killSwitchTunnels.isNotEmpty || apps.any((a) => a.killSwitch);

  Iterable<TunnelTarget> get targets => {
    ?defaultTunnel,
    ...wifiTunnels.values,
    ?mobileTunnel,
    ...simTunnels.values,
    for (final app in apps) ?app.target,
  };

  AutoTunnelSettings copyWith({
    bool? enabled,
    TunnelTarget? Function()? defaultTunnel,
    bool? tunnelOnWifi,
    List<String>? trustedNetworks,
    Map<String, TunnelTarget>? wifiTunnels,
    bool? tunnelOnMobileData,
    Set<int>? mobileSims,
    TunnelTarget? Function()? mobileTunnel,
    Map<int, TunnelTarget>? simTunnels,
    bool? stopOnNoNetwork,
    int? debounceSeconds,
    List<AppSettings>? apps,
    Set<int>? killSwitchTunnels,
  }) => AutoTunnelSettings(
    enabled: enabled ?? this.enabled,
    defaultTunnel: defaultTunnel == null ? this.defaultTunnel : defaultTunnel(),
    tunnelOnWifi: tunnelOnWifi ?? this.tunnelOnWifi,
    trustedNetworks: trustedNetworks ?? this.trustedNetworks,
    wifiTunnels: wifiTunnels ?? this.wifiTunnels,
    tunnelOnMobileData: tunnelOnMobileData ?? this.tunnelOnMobileData,
    mobileSims: mobileSims ?? this.mobileSims,
    mobileTunnel: mobileTunnel == null ? this.mobileTunnel : mobileTunnel(),
    simTunnels: simTunnels ?? this.simTunnels,
    stopOnNoNetwork: stopOnNoNetwork ?? this.stopOnNoNetwork,
    debounceSeconds: debounceSeconds ?? this.debounceSeconds,
    apps: apps ?? this.apps,
    killSwitchTunnels: killSwitchTunnels ?? this.killSwitchTunnels,
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
    'defaultTunnel': defaultTunnel?.toJson(),
    'tunnelOnWifi': tunnelOnWifi,
    'trustedNetworks': trustedNetworks,
    'wifiTunnels': [
      for (final MapEntry(:key, :value) in wifiTunnels.entries)
        {'ssid': key, 'target': value.toJson()},
    ],
    'tunnelOnMobileData': tunnelOnMobileData,
    'mobileSims': mobileSims.toList(),
    'mobileTunnel': mobileTunnel?.toJson(),
    'simTunnels': [
      for (final MapEntry(:key, :value) in simTunnels.entries)
        {'simId': key, 'target': value.toJson()},
    ],
    'stopOnNoNetwork': stopOnNoNetwork,
    'debounceSeconds': debounceSeconds,
    'apps': [for (final a in apps) a.toJson()],
    'killSwitchTunnels': killSwitchTunnels.toList(),
  };

  factory AutoTunnelSettings.fromJson(Map<String, dynamic> json) {
    List<Map<String, dynamic>> maps(String key) => [
      for (final item in (json[key] as List?) ?? const [])
        if (item is Map) item.cast<String, dynamic>(),
    ];
    return AutoTunnelSettings(
      enabled: json['enabled'] as bool? ?? false,
      defaultTunnel: TunnelTarget.fromJson(json['defaultTunnel']),
      tunnelOnWifi: json['tunnelOnWifi'] as bool? ?? true,
      trustedNetworks: [
        for (final ssid in (json['trustedNetworks'] as List?) ?? const [])
          if (ssid is String) ssid,
      ],
      wifiTunnels: {
        for (final item in maps('wifiTunnels'))
          if (item['ssid'] case final String ssid)
            ssid: ?TunnelTarget.fromJson(item['target']),
      },
      tunnelOnMobileData: json['tunnelOnMobileData'] as bool? ?? true,
      mobileSims: {
        for (final id in (json['mobileSims'] as List?) ?? const [])
          if (id is int) id,
      },
      mobileTunnel: TunnelTarget.fromJson(json['mobileTunnel']),
      simTunnels: {
        for (final item in maps('simTunnels'))
          if (item['simId'] case final int simId)
            simId: ?TunnelTarget.fromJson(item['target']),
      },
      stopOnNoNetwork: json['stopOnNoNetwork'] as bool? ?? false,
      debounceSeconds: json['debounceSeconds'] as int? ?? 2,
      apps: [for (final a in maps('apps')) AppSettings.fromJson(a)],
      killSwitchTunnels: {
        for (final id in (json['killSwitchTunnels'] as List?) ?? const [])
          if (id is int) id,
      },
    );
  }
}

enum NetworkKind { wifi, mobile, none }

/// [ssid] is null when the system hides it.
typedef NetworkSnapshot = ({NetworkKind kind, String? ssid, int? simId});

/// A null [target] while connecting is whichever profile is current.
typedef AutoTunnelAction = ({String key, bool connect, TunnelTarget? target});

bool ssidMatches(String pattern, String ssid) {
  if (!pattern.contains('*') && !pattern.contains('?')) return pattern == ssid;
  final regex = pattern
      .split('')
      .map(
        (char) => switch (char) {
          '*' => '.*',
          '?' => '.',
          _ => RegExp.escape(char),
        },
      )
      .join();
  return RegExp('^$regex\$').hasMatch(ssid);
}

bool isTrusted(AutoTunnelSettings settings, NetworkSnapshot network) {
  final ssid = network.ssid;
  return network.kind == NetworkKind.wifi &&
      ssid != null &&
      settings.trustedNetworks.any((pattern) => ssidMatches(pattern, ssid));
}

/// Null leaves the tunnel as it is. Mirrored by Android's `AutoTunnelRules`.
AutoTunnelAction? decideAutoTunnel(
  AutoTunnelSettings settings,
  NetworkSnapshot network, {
  List<String> openApps = const [],
}) {
  AutoTunnelAction connect(String key, TunnelTarget? target) =>
      (key: key, connect: true, target: target ?? settings.defaultTunnel);
  AutoTunnelAction disconnect(String key) =>
      (key: key, connect: false, target: null);

  for (final open in openApps) {
    for (final app in settings.apps) {
      if (app.detected == open) return connect(app.id, app.target);
    }
  }
  final ssid = network.ssid;
  final simId = network.simId;
  switch (network.kind) {
    case NetworkKind.wifi:
      if (isTrusted(settings, network)) return disconnect('wifi:trusted');
      if (!settings.tunnelOnWifi) return disconnect('wifi:off');
      final preferred = ssid == null
          ? null
          : settings.wifiTunnels.entries
                .where((entry) => ssidMatches(entry.key, ssid))
                .firstOrNull;
      return preferred == null
          ? connect('wifi', null)
          : connect('wifi:${preferred.key}', preferred.value);
    case NetworkKind.mobile:
      final counted =
          settings.mobileSims.isEmpty ||
          simId == null ||
          settings.mobileSims.contains(simId);
      if (!settings.tunnelOnMobileData || !counted) {
        return disconnect('mobile:off');
      }
      final perSim = simId == null ? null : settings.simTunnels[simId];
      if (perSim != null) return connect('mobile:$simId', perSim);
      return connect('mobile', settings.mobileTunnel);
    case NetworkKind.none:
      return settings.stopOnNoNetwork ? disconnect('none') : null;
  }
}

bool blocksAll(
  AutoTunnelSettings settings,
  NetworkSnapshot network,
  int? tunnelId,
) =>
    settings.killSwitchTunnels.contains(tunnelId) &&
    !isTrusted(settings, network);

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

  String get title => '$name · SIM ${slot + 1}';
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
  static const key = 'quartz.autoTunnel.v4';
  static const _previousKey = 'quartz.autoTunnel.v3';
  static const platform = AutoTunnelPlatform();

  @override
  AutoTunnelSettings build() {
    _load();
    return const AutoTunnelSettings();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final text = prefs.getString(key);
    try {
      if (text != null) {
        state = AutoTunnelSettings.fromJson(
          (jsonDecode(text) as Map).cast<String, dynamic>(),
        );
        return;
      }
      final previous = prefs.getString(_previousKey);
      if (previous == null) return;
      final old = AutoTunnelSettings.fromJson(
        (jsonDecode(previous) as Map).cast<String, dynamic>(),
      );
      state = AutoTunnelSettings(
        apps: old.apps,
        killSwitchTunnels: old.killSwitchTunnels,
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
}
