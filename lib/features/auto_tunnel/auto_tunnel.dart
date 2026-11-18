import 'dart:convert';

import 'package:collection/collection.dart';
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

/// A condition left empty is not asked; a rule with neither matches every
/// network.
class AutoTunnelRule {
  const AutoTunnelRule({
    required this.id,
    this.name = '',
    this.enabled = true,
    this.categoryId,
    this.networks = const [],
    this.apps = const [],
    this.matchAll = true,
    this.connect = true,
    this.target,
    this.killSwitch = false,
  });

  final String id;
  final String name;
  final bool enabled;
  final String? categoryId;
  final List<NetworkMatch> networks;
  final List<RuleApp> apps;

  final bool matchAll;
  final bool connect;

  /// Null only in rules saved before every rule named its tunnel: whichever
  /// profile is current.
  final TunnelTarget? target;

  final bool killSwitch;

  bool matchesNetwork(NetworkSnapshot network) =>
      networks.isNotEmpty && networks.any((match) => match.matches(network));

  /// A null [app] is no rule app open.
  bool matches(NetworkSnapshot network, String? app) {
    final appOpen = app != null && apps.any((a) => a.detected == app);
    if (apps.isEmpty) return networks.isEmpty || matchesNetwork(network);
    if (networks.isEmpty) return appOpen;
    return matchAll
        ? appOpen && matchesNetwork(network)
        : appOpen || matchesNetwork(network);
  }

  AutoTunnelRule copyWith({
    String? name,
    bool? enabled,
    String? Function()? categoryId,
    List<NetworkMatch>? networks,
    List<RuleApp>? apps,
    bool? matchAll,
    bool? connect,
    TunnelTarget? Function()? target,
    bool? killSwitch,
  }) => AutoTunnelRule(
    id: id,
    name: name ?? this.name,
    enabled: enabled ?? this.enabled,
    categoryId: categoryId == null ? this.categoryId : categoryId(),
    networks: networks ?? this.networks,
    apps: apps ?? this.apps,
    matchAll: matchAll ?? this.matchAll,
    connect: connect ?? this.connect,
    target: target == null ? this.target : target(),
    killSwitch: killSwitch ?? this.killSwitch,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'enabled': enabled,
    'categoryId': categoryId,
    'networks': [for (final match in networks) match.toJson()],
    'apps': [for (final app in apps) app.toJson()],
    'matchAll': matchAll,
    'connect': connect,
    'target': target?.toJson(),
    'killSwitch': killSwitch,
  };

  static AutoTunnelRule? fromJson(Object? json) {
    if (json is! Map || json['id'] is! String) return null;
    return AutoTunnelRule(
      id: json['id'] as String,
      name: json['name'] as String? ?? '',
      enabled: json['enabled'] as bool? ?? true,
      categoryId: json['categoryId'] as String?,
      networks: [
        for (final match in (json['networks'] as List?) ?? const [])
          ?NetworkMatch.fromJson(match),
      ],
      apps: [
        for (final app in (json['apps'] as List?) ?? const [])
          ?RuleApp.fromJson(app),
      ],
      matchAll: json['matchAll'] as bool? ?? true,
      connect: json['connect'] as bool? ?? true,
      target: TunnelTarget.fromJson(json['target']),
      killSwitch: json['killSwitch'] as bool? ?? false,
    );
  }

  static String newId() =>
      DateTime.now().microsecondsSinceEpoch.toRadixString(36);
}

class RuleCategory {
  const RuleCategory({
    required this.id,
    required this.name,
    this.enabled = true,
    this.killSwitch = false,
  });

  final String id;
  final String name;
  final bool enabled;

  final bool killSwitch;

  RuleCategory copyWith({String? name, bool? enabled, bool? killSwitch}) =>
      RuleCategory(
        id: id,
        name: name ?? this.name,
        enabled: enabled ?? this.enabled,
        killSwitch: killSwitch ?? this.killSwitch,
      );

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'enabled': enabled,
    'killSwitch': killSwitch,
  };

  static RuleCategory? fromJson(Object? json) =>
      json is Map && json['id'] is String
      ? RuleCategory(
          id: json['id'] as String,
          name: json['name'] as String? ?? '',
          enabled: json['enabled'] as bool? ?? true,
          killSwitch: json['killSwitch'] as bool? ?? false,
        )
      : null;
}

class AutoTunnelSettings {
  const AutoTunnelSettings({
    this.enabled = false,
    this.rules = const [],
    this.categories = const [],
  });

  final bool enabled;

  final List<AutoTunnelRule> rules;
  final List<RuleCategory> categories;

  RuleCategory? categoryOf(AutoTunnelRule rule) =>
      categories.firstWhereOrNull((c) => c.id == rule.categoryId);

  bool isActive(AutoTunnelRule rule) =>
      rule.enabled && (categoryOf(rule)?.enabled ?? true);

  bool killSwitchOf(AutoTunnelRule rule) =>
      rule.connect &&
      (rule.killSwitch || (categoryOf(rule)?.killSwitch ?? false));

  Iterable<AutoTunnelRule> get activeRules => rules.where(isActive);

  bool get anyKillSwitch => rules.any(killSwitchOf);

  /// Every app a rule waits for, by what detection reports.
  Set<String> get watchedApps => {
    for (final rule in activeRules) ...rule.apps.map((app) => app.detected),
  };

  Iterable<TunnelTarget> get targets => {
    for (final rule in rules) ?rule.target,
  };

  AutoTunnelSettings copyWith({
    bool? enabled,
    List<AutoTunnelRule>? rules,
    List<RuleCategory>? categories,
  }) => AutoTunnelSettings(
    enabled: enabled ?? this.enabled,
    rules: rules ?? this.rules,
    categories: categories ?? this.categories,
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

  AutoTunnelSettings putCategory(RuleCategory category) => copyWith(
    categories: categories.any((c) => c.id == category.id)
        ? [for (final c in categories) c.id == category.id ? category : c]
        : [...categories, category],
  );

  AutoTunnelSettings removeCategory(String id) => copyWith(
    categories: [
      for (final c in categories)
        if (c.id != id) c,
    ],
    rules: [
      for (final r in rules)
        r.categoryId == id ? r.copyWith(categoryId: () => null) : r,
    ],
  );

  Map<String, dynamic> toJson() => {
    'enabled': enabled,
    'rules': [for (final rule in rules) rule.toJson()],
    'categories': [for (final category in categories) category.toJson()],
  };

  factory AutoTunnelSettings.fromJson(Map<String, dynamic> json) =>
      AutoTunnelSettings(
        enabled: json['enabled'] as bool? ?? false,
        rules: [
          for (final rule in (json['rules'] as List?) ?? const [])
            ?AutoTunnelRule.fromJson(rule),
        ],
        categories: [
          for (final category in (json['categories'] as List?) ?? const [])
            ?RuleCategory.fromJson(category),
        ],
      );

  /// Drops the "any Wi-Fi: disconnect" rule the previous version seeded.
  factory AutoTunnelSettings.fromPrevious(Map<String, dynamic> json) {
    final defaultTunnel = TunnelTarget.fromJson(json['defaultTunnel']);
    final killSwitch = json['killSwitch'] as bool? ?? false;
    final previous = AutoTunnelSettings.fromJson(json);
    return previous.copyWith(
      rules: [
        for (final rule in previous.rules)
          if (!_isSeededCatchAll(rule))
            rule.copyWith(
              target: () => rule.target ?? defaultTunnel,
              killSwitch: rule.connect && killSwitch,
            ),
      ],
    );
  }

  static bool _isSeededCatchAll(AutoTunnelRule rule) =>
      !rule.connect &&
      rule.apps.isEmpty &&
      rule.networks.length == 1 &&
      rule.networks.single == const NetworkMatch.wifi();
}

enum NetworkKind { wifi, mobile, none }

/// [ssid] is null when the system hides it.
typedef NetworkSnapshot = ({NetworkKind kind, String? ssid, int? simId});

typedef AutoTunnelAction = ({
  String key,
  bool connect,
  TunnelTarget? target,
  String? ruleId,
});

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

AutoTunnelRule? _activeRule(AutoTunnelSettings settings, String? id) =>
    id == null
    ? null
    : settings.activeRules.firstWhereOrNull((rule) => rule.id == id);

/// Null leaves the tunnel as it is. Mirrored by Android's `AutoTunnelRules`.
AutoTunnelAction? decideAutoTunnel(
  AutoTunnelSettings settings,
  NetworkSnapshot network, {
  List<String> openApps = const [],
  String? active,
}) {
  if (network.kind == NetworkKind.none) {
    final rule = _activeRule(settings, active);
    if (rule != null && settings.killSwitchOf(rule)) return null;
    return (key: 'none', connect: false, target: null, ruleId: null);
  }
  AutoTunnelAction act(AutoTunnelRule rule) => (
    key: 'rule:${rule.id}',
    connect: rule.connect,
    target: rule.connect ? rule.target : null,
    ruleId: rule.id,
  );
  final rules = settings.activeRules.toList();
  for (final open in openApps) {
    for (final rule in rules) {
      if (rule.apps.any((app) => app.detected == open) &&
          rule.matches(network, open)) {
        return act(rule);
      }
    }
  }
  for (final rule in rules) {
    if (rule.matches(network, null)) return act(rule);
  }
  return active == null
      ? null
      : (key: 'ended', connect: false, target: null, ruleId: null);
}

bool blocksAll(
  AutoTunnelSettings settings, {
  required String? active,
  required bool running,
}) {
  if (running || !settings.enabled) return false;
  final rule = _activeRule(settings, active);
  return rule != null && settings.killSwitchOf(rule);
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
  static const key = 'quartz.autoTunnel.v6';
  static const _previousKey = 'quartz.autoTunnel.v5';
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
