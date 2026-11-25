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

enum EndAction { disconnect, keep, connect }

class AutoTunnelRule {
  const AutoTunnelRule({
    required this.id,
    this.name = '',
    this.enabled = true,
    this.categoryId,
    this.networks = const [],
    this.connect = true,
    this.target,
    this.killSwitch = false,
    this.onEnd = EndAction.disconnect,
    this.endTarget,
  });

  final String id;
  final String name;
  final bool enabled;
  final String? categoryId;
  final List<NetworkMatch> networks;
  final bool connect;

  /// Null only in rules saved before every rule named its tunnel: whichever
  /// profile is current.
  final TunnelTarget? target;

  final bool killSwitch;
  final EndAction onEnd;
  final TunnelTarget? endTarget;

  bool matchesNetwork(NetworkSnapshot network) =>
      networks.isEmpty || networks.any((match) => match.matches(network));

  AutoTunnelRule copyWith({
    String? name,
    bool? enabled,
    String? Function()? categoryId,
    List<NetworkMatch>? networks,
    bool? connect,
    TunnelTarget? Function()? target,
    bool? killSwitch,
    EndAction? onEnd,
    TunnelTarget? Function()? endTarget,
  }) => AutoTunnelRule(
    id: id,
    name: name ?? this.name,
    enabled: enabled ?? this.enabled,
    categoryId: categoryId == null ? this.categoryId : categoryId(),
    networks: networks ?? this.networks,
    connect: connect ?? this.connect,
    target: target == null ? this.target : target(),
    killSwitch: killSwitch ?? this.killSwitch,
    onEnd: onEnd ?? this.onEnd,
    endTarget: endTarget == null ? this.endTarget : endTarget(),
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'enabled': enabled,
    'categoryId': categoryId,
    'networks': [for (final match in networks) match.toJson()],
    'connect': connect,
    'target': target?.toJson(),
    'killSwitch': killSwitch,
    'onEnd': onEnd.name,
    'endTarget': endTarget?.toJson(),
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
      connect: json['connect'] as bool? ?? true,
      target: TunnelTarget.fromJson(json['target']),
      killSwitch: json['killSwitch'] as bool? ?? false,
      onEnd:
          EndAction.values.asNameMap()[json['onEnd']] ?? EndAction.disconnect,
      endTarget: TunnelTarget.fromJson(json['endTarget']),
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
  Iterable<TunnelTarget> get targets => {
    for (final rule in rules) ...[?rule.target, ?rule.endTarget],
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
      rule.networks.length == 1 &&
      rule.networks.single == const NetworkMatch.wifi();
}

enum NetworkKind { wifi, mobile, none }

/// [ssid] is null when the system hides it.
typedef NetworkSnapshot = ({NetworkKind kind, String? ssid, int? simId});

/// A null [connect] leaves the tunnel as it is.
typedef AutoTunnelAction = ({
  String key,
  bool? connect,
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
  for (final rule in settings.activeRules) {
    if (rule.matchesNetwork(network)) return act(rule);
  }
  if (active == null) return null;
  final ended = settings.rules.firstWhereOrNull((rule) => rule.id == active);
  return switch (ended?.onEnd) {
    EndAction.keep => (key: 'ended', connect: null, target: null, ruleId: null),
    EndAction.connect when ended?.endTarget != null => (
      key: 'ended',
      connect: true,
      target: ended!.endTarget,
      ruleId: null,
    ),
    _ => (key: 'ended', connect: false, target: null, ruleId: null),
  };
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

typedef KillSwitchState = ({bool blockAll, bool blockOnCoreExit});

/// While up, only the Core exiting on its own blocks, so the bypassing LAN works.
KillSwitchState killSwitchState(
  AutoTunnelSettings settings, {
  required String? active,
  required bool enabled,
  required bool wanted,
  required bool up,
  required bool userStopped,
}) {
  final running = wanted && up;
  final ruleBlocks =
      !userStopped && blocksAll(settings, active: active, running: running);
  return (
    blockAll: ruleBlocks || (enabled && wanted && !up),
    blockOnCoreExit: enabled && running,
  );
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
    );
  }

  Future<bool> request(String kind) async =>
      await channel.invokeMethod<bool>('request', kind) ?? false;

  Future<List<String>> wifiNetworks() async =>
      await channel.invokeListMethod<String>('wifiNetworks') ?? const [];

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

/// Drops rules that waited for apps rather than widen them to every network.
Map<String, dynamic> withoutAppRules(Map<String, dynamic> json) => {
  ...json,
  'rules': [
    for (final rule in (json['rules'] as List?) ?? const [])
      if (rule is! Map || ((rule['apps'] as List?) ?? const []).isEmpty) rule,
  ],
};

final autoTunnelProvider =
    NotifierProvider<AutoTunnelNotifier, AutoTunnelSettings>(
      AutoTunnelNotifier.new,
    );

class AutoTunnelNotifier extends Notifier<AutoTunnelSettings> {
  static const key = 'quartz.autoTunnel.v7';
  static const _withAppsKey = 'quartz.autoTunnel.v6';
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
      final withApps = prefs.getString(_withAppsKey);
      if (withApps != null) {
        state = AutoTunnelSettings.fromJson(
          withoutAppRules((jsonDecode(withApps) as Map).cast()),
        );
        return;
      }
      final previous = prefs.getString(_previousKey);
      if (previous == null) return;
      state = AutoTunnelSettings.fromPrevious(
        withoutAppRules((jsonDecode(previous) as Map).cast()),
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
