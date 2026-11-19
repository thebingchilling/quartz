import 'dart:convert';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path/path.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'auto_tunnel.dart';

/// Written by Android's service when it switched while the app was closed.
const _appliedKey = 'quartz.autoTunnel.applied';

/// Reads what Android's service sends through `apply`, or leaves behind.
AutoTunnelAction? actionFromJson(Object? json) {
  final map = json is String ? jsonDecode(json) : json;
  if (map is! Map) return null;
  return (
    key: map['key'] as String? ?? '',
    connect: map['connect'] as bool?,
    target: TunnelTarget.fromJson(map['target']),
    ruleId: map['ruleId'] as String?,
  );
}

/// A tunnel whose profile was deleted connects whichever profile is current.
Future<void> applyAutoTunnelAction(
  WidgetRef ref,
  AutoTunnelAction action, {
  bool connect = true,
}) async {
  final setup = ref.read(setupActionProvider.notifier);
  if (action.connect == null) return;
  if (action.connect == false) {
    if (connect && ref.read(isStartProvider)) await setup.setRunning(false);
    return;
  }
  final target = action.target;
  if (target != null && !await applyTunnelTarget(ref, target)) {
    commonPrint.log('Auto-tunnel: profile ${target.profileId} is gone');
  }
  if (connect && !ref.read(isStartProvider)) {
    await setup.setRunning(true, initialize: !ref.read(initProvider));
  }
}

/// Makes [target] the current profile with its server picked; false when
/// its profile is gone.
Future<bool> applyTunnelTarget(WidgetRef ref, TunnelTarget target) async {
  final profile = ref
      .read(profilesProvider)
      .firstWhereOrNull((profile) => profile.id == target.profileId);
  if (profile == null) return false;
  final picks = target.picks;
  final switching = ref.read(currentProfileIdProvider) != profile.id;
  final selectedMap = {...profile.selectedMap, ...picks};
  if (!mapEquals(selectedMap, profile.selectedMap)) {
    if (switching) {
      ref
          .read(profilesProvider.notifier)
          .put(profile.copyWith(selectedMap: selectedMap));
    } else {
      final proxies = ref.read(proxiesActionProvider.notifier);
      for (final MapEntry(:key, :value) in picks.entries) {
        await proxies.changeProxy(groupName: key, proxyName: value);
      }
    }
  }
  if (switching) ref.read(currentProfileIdProvider.notifier).value = profile.id;
  return true;
}

/// Each tunnel's config and service state by [TunnelTarget.key], so
/// Android's service can switch with the app closed. The current profile is
/// rendered too, for decisions that keep whichever profile is current.
Future<Map<String, dynamic>> renderTargets(
  WidgetRef ref,
  AutoTunnelSettings settings,
) async {
  final current = ref.read(currentProfileIdProvider);
  final targets = {
    ...settings.targets,
    if (current != null) TunnelTarget(profileId: current),
  };
  final profiles = ref.read(profilesProvider);
  final dir = Directory(join(await appPath.homeDirPath, 'autotunnel'));
  await dir.create(recursive: true);
  final yamls = <int, String>{};
  final rendered = <String, dynamic>{};
  for (final target in targets) {
    final profile = profiles.firstWhereOrNull((p) => p.id == target.profileId);
    if (profile == null) continue;
    final path = join(dir.path, '${profile.id}.yaml');
    if (!yamls.containsKey(profile.id)) {
      final yaml = await ref
          .read(setupActionProvider.notifier)
          .getProfileWithId(profile.id);
      if (yaml.isEmpty) continue;
      yamls[profile.id] = yaml;
      await File(path).writeAsStringAtomically(yaml);
    }
    rendered[target.key] = {
      'profileId': profile.id,
      'path': path,
      'label': profile.realLabel,
      'selectedMap': {...profile.selectedMap, ...target.picks},
    };
  }
  return rendered;
}

/// The switch Android's service made while the app was closed, if any.
Future<AutoTunnelAction?> takeAppliedAction() async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.reload();
  final json = prefs.getString(_appliedKey);
  if (json == null) return null;
  await prefs.remove(_appliedKey);
  try {
    return actionFromJson(json);
  } on FormatException {
    return null;
  }
}

String settingsJson(
  AutoTunnelSettings settings,
  Map<String, dynamic> rendered,
  int? currentProfileId,
) => jsonEncode({
  ...settings.toJson(),
  'currentProfileId': currentProfileId,
  'rendered': rendered,
});
