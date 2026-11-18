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
class RuleApp {
  const RuleApp({required this.app, required this.label});

  final String app;
  final String label;

  /// What app detection reports: the package, or the program's file name.
  String get detected =>
      app.contains(_separator) ? app.split(_separator).last.toLowerCase() : app;

  static final _separator = RegExp(r'[\\/]');

  Map<String, dynamic> toJson() => {
    'app': app,
    'label': label,
    'detected': detected,
  };

  static RuleApp? fromJson(Object? json) => json is Map && json['app'] is String
      ? RuleApp(
          app: json['app'] as String,
          label: json['label'] as String? ?? json['app'] as String,
        )
      : null;

  @override
  bool operator ==(Object other) => other is RuleApp && other.app == app;

  @override
  int get hashCode => app.hashCode;
}

enum NetworkMatchKind { wifi, mobile }

/// A null [ssid] is any Wi-Fi and a null [simId] any SIM. An [ssid] may use
/// `*` and `?` wildcards.
class NetworkMatch {
  const NetworkMatch.wifi([this.ssid])
    : kind = NetworkMatchKind.wifi,
      simId = null;

  const NetworkMatch.mobile([this.simId])
    : kind = NetworkMatchKind.mobile,
      ssid = null;

  final NetworkMatchKind kind;
  final String? ssid;
  final int? simId;

  bool matches(NetworkSnapshot network) => switch (kind) {
    NetworkMatchKind.wifi =>
      network.kind == NetworkKind.wifi &&
          (ssid == null ||
              (network.ssid != null && ssidMatches(ssid!, network.ssid!))),
    NetworkMatchKind.mobile =>
      network.kind == NetworkKind.mobile &&
          (simId == null || simId == network.simId),
  };

  Map<String, dynamic> toJson() => {
    'kind': kind.name,
    'ssid': ssid,
    'simId': simId,
  };

  static NetworkMatch? fromJson(Object? json) {
    if (json is! Map) return null;
    return switch (json['kind']) {
      'wifi' => NetworkMatch.wifi(json['ssid'] as String?),
      'mobile' => NetworkMatch.mobile(json['simId'] as int?),
      _ => null,
    };
  }

  @override
  bool operator ==(Object other) =>
      other is NetworkMatch &&
      other.kind == kind &&
      other.ssid == ssid &&
      other.simId == simId;

  @override
  int get hashCode => Object.hash(kind, ssid, simId);
}

/// When on any of [networks] (empty: any network) and, if [apps] is not
/// empty, while one of them is open: connect [target] (null: the default
/// tunnel), or disconnect.
class AutoTunnelRule {
  const AutoTunnelRule({
    required this.id,
    this.enabled = true,
    this.networks = const [],
    this.apps = const [],
    this.connect = true,
    this.target,
  });

  final String id;
  final bool enabled;
  final List<NetworkMatch> networks;
  final List<RuleApp> apps;
  final bool connect;
  final TunnelTarget? target;

  bool matchesNetwork(NetworkSnapshot network) =>
      networks.isEmpty || networks.any((match) => match.matches(network));

  AutoTunnelRule copyWith({
    bool? enabled,
    List<NetworkMatch>? networks,
    List<RuleApp>? apps,
    bool? connect,
    TunnelTarget? Function()? target,
  }) => AutoTunnelRule(
    id: id,
    enabled: enabled ?? this.enabled,
    networks: networks ?? this.networks,
    apps: apps ?? this.apps,
    connect: connect ?? this.connect,
    target: target == null ? this.target : target(),
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'enabled': enabled,
    'networks': [for (final match in networks) match.toJson()],
    'apps': [for (final app in apps) app.toJson()],
    'connect': connect,
    'target': target?.toJson(),
  };

  static AutoTunnelRule? fromJson(Object? json) {
    if (json is! Map || json['id'] is! String) return null;
    return AutoTunnelRule(
      id: json['id'] as String,
      enabled: json['enabled'] as bool? ?? true,
      networks: [
        for (final match in (json['networks'] as List?) ?? const [])
          ?NetworkMatch.fromJson(match),
      ],
      apps: [
        for (final app in (json['apps'] as List?) ?? const [])
          ?RuleApp.fromJson(app),
      ],
      connect: json['connect'] as bool? ?? true,
      target: TunnelTarget.fromJson(json['target']),
    );
  }

  static String newId() =>
      DateTime.now().microsecondsSinceEpoch.toRadixString(36);
}

class AutoTunnelSettings {
  const AutoTunnelSettings({
    this.enabled = false,
    this.rules = const [],
    this.defaultTunnel,
    this.disconnectOtherwise = true,
    this.stopOnNoNetwork = true,
    this.killSwitch = false,
    this.debounceSeconds = 2,
  });

  final bool enabled;
  final List<AutoTunnelRule> rules;

  /// Null connects whichever profile is current.
  final TunnelTarget? defaultTunnel;

  /// When no rule matches; off leaves the tunnel as it is.
  final bool disconnectOtherwise;
  final bool stopOnNoNetwork;
  final bool killSwitch;
  final int debounceSeconds;

  static const debounceChoices = [0, 1, 2, 3, 5, 10];

  /// Every app a rule waits for, by what detection reports.
  Set<String> get watchedApps => {
    for (final rule in rules)
      if (rule.enabled) ...rule.apps.map((app) => app.detected),
  };

  Iterable<TunnelTarget> get targets => {
    ?defaultTunnel,
    for (final rule in rules) ?rule.target,
  };

  AutoTunnelSettings copyWith({
    bool? enabled,
    List<AutoTunnelRule>? rules,
    TunnelTarget? Function()? defaultTunnel,
    bool? disconnectOtherwise,
    bool? stopOnNoNetwork,
    bool? killSwitch,
    int? debounceSeconds,
  }) => AutoTunnelSettings(
    enabled: enabled ?? this.enabled,
    rules: rules ?? this.rules,
    defaultTunnel: defaultTunnel == null ? this.defaultTunnel : defaultTunnel(),
    disconnectOtherwise: disconnectOtherwise ?? this.disconnectOtherwise,
    stopOnNoNetwork: stopOnNoNetwork ?? this.stopOnNoNetwork,
    killSwitch: killSwitch ?? this.killSwitch,
    debounceSeconds: debounceSeconds ?? this.debounceSeconds,
  );

  AutoTunnelSettings putRule(AutoTunnelRule rule) => copyWith(
    rules: rules.any((r) => r.id == rule.id)
        ? [for (final r in rules) r.id == rule.id ? rule : r]
        : [...rules, rule],
  );

  AutoTunnelSettings removeRule(String id) => copyWith(
    rules: [
      for (final r in rules)
        if (r.id != id) r,
    ],
  );

  Map<String, dynamic> toJson() => {
    'enabled': enabled,
    'rules': [for (final rule in rules) rule.toJson()],
    'defaultTunnel': defaultTunnel?.toJson(),
    'disconnectOtherwise': disconnectOtherwise,
    'stopOnNoNetwork': stopOnNoNetwork,
    'killSwitch': killSwitch,
    'debounceSeconds': debounceSeconds,
  };

  factory AutoTunnelSettings.fromJson(Map<String, dynamic> json) =>
      AutoTunnelSettings(
        enabled: json['enabled'] as bool? ?? false,
        rules: [
          for (final rule in (json['rules'] as List?) ?? const [])
            ?AutoTunnelRule.fromJson(rule),
        ],
        defaultTunnel: TunnelTarget.fromJson(json['defaultTunnel']),
        disconnectOtherwise: json['disconnectOtherwise'] as bool? ?? true,
        stopOnNoNetwork: json['stopOnNoNetwork'] as bool? ?? true,
        killSwitch: json['killSwitch'] as bool? ?? false,
        debounceSeconds: json['debounceSeconds'] as int? ?? 2,
      );

  /// The settings of the version before rules, as rules in the same order of
  /// precedence they had.
  factory AutoTunnelSettings.fromPrevious(Map<String, dynamic> json) {
    var n = 0;
    String id() => 'r${n++}';
    TunnelTarget? target(Object? value) => TunnelTarget.fromJson(value);
    final rules = <AutoTunnelRule>[
      for (final ssid in (json['trustedNetworks'] as List?) ?? const [])
        if (ssid is String)
          AutoTunnelRule(
            id: id(),
            networks: [NetworkMatch.wifi(ssid)],
            connect: false,
          ),
      for (final item in (json['wifiTunnels'] as List?) ?? const [])
        if (item is Map && item['ssid'] is String)
          AutoTunnelRule(
            id: id(),
            networks: [NetworkMatch.wifi(item['ssid'] as String)],
            target: target(item['target']),
          ),
      AutoTunnelRule(
        id: id(),
        networks: const [NetworkMatch.wifi()],
        connect: json['tunnelOnWifi'] as bool? ?? true,
      ),
      for (final item in (json['simTunnels'] as List?) ?? const [])
        if (item is Map && item['simId'] is int)
          AutoTunnelRule(
            id: id(),
            networks: [NetworkMatch.mobile(item['simId'] as int)],
            target: target(item['target']),
          ),
      if (json['tunnelOnMobileData'] as bool? ?? true)
        AutoTunnelRule(
          id: id(),
          networks: [
            for (final sim in (json['mobileSims'] as List?) ?? const [])
              if (sim is int) NetworkMatch.mobile(sim),
          ].ifEmpty(const [NetworkMatch.mobile()]),
          target: target(json['mobileTunnel']),
        ),
      for (final app in (json['apps'] as List?) ?? const [])
        if (RuleApp.fromJson(app) case final ruleApp?)
          AutoTunnelRule(
            id: id(),
            apps: [ruleApp],
            target: target((app as Map)['target']),
          ),
    ];
    final apps = (json['apps'] as List?) ?? const [];
    return AutoTunnelSettings(
      enabled: json['enabled'] as bool? ?? false,
      rules: rules,
      defaultTunnel: target(json['defaultTunnel']),
      stopOnNoNetwork: json['stopOnNoNetwork'] as bool? ?? true,
      debounceSeconds: json['debounceSeconds'] as int? ?? 2,
      killSwitch:
          ((json['killSwitchTunnels'] as List?) ?? const []).isNotEmpty ||
          apps.any((app) => app is Map && app['killSwitch'] == true),
    );
  }
}

extension<T> on List<T> {
  List<T> ifEmpty(List<T> fallback) => isEmpty ? fallback : this;
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

/// Null leaves the tunnel as it is. Rules with apps go first, for the most
/// recently opened app, then the network rules, in order. Mirrored by
/// Android's `AutoTunnelRules`.
AutoTunnelAction? decideAutoTunnel(
  AutoTunnelSettings settings,
  NetworkSnapshot network, {
  List<String> openApps = const [],
}) {
  if (network.kind == NetworkKind.none) {
    return settings.stopOnNoNetwork
        ? (key: 'none', connect: false, target: null)
        : null;
  }
  AutoTunnelAction act(AutoTunnelRule rule) => (
    key: 'rule:${rule.id}',
    connect: rule.connect,
    target: rule.connect ? rule.target ?? settings.defaultTunnel : null,
  );
  final active = settings.rules.where((rule) => rule.enabled);
  for (final open in openApps) {
    for (final rule in active) {
      if (rule.apps.any((app) => app.detected == open) &&
          rule.matchesNetwork(network)) {
        return act(rule);
      }
    }
  }
  for (final rule in active) {
    if (rule.apps.isEmpty && rule.matchesNetwork(network)) return act(rule);
  }
  return settings.disconnectOtherwise
      ? (key: 'otherwise', connect: false, target: null)
      : null;
}

/// The kill switch blocks every app only while a tunnel is meant to run and
/// doesn't: never after auto-tunnel disconnects on purpose, nor after the
/// user turns the tunnel off.
bool blocksAll(AutoTunnelSettings settings, {required bool tunnelWanted}) =>
    settings.killSwitch && tunnelWanted;

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
  static const key = 'quartz.autoTunnel.v5';
  static const _previousKey = 'quartz.autoTunnel.v4';
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
      state = AutoTunnelSettings.fromPrevious(
        (jsonDecode(previous) as Map).cast<String, dynamic>(),
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
