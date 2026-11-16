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

TunnelTarget? targetFor(AutoTunnelSettings settings, String key) =>
    settings.apps.firstWhereOrNull((app) => app.id == key)?.target ??
    settings.connections.firstWhereOrNull((c) => c.id == key)?.target;

/// False when [target]'s profile is gone.
Future<bool> applyTunnelTarget(
  WidgetRef ref,
  TunnelTarget target, {
  bool connect = true,
}) async {
  final setup = ref.read(setupActionProvider.notifier);
  if (target.disconnects) {
    if (connect && ref.read(isStartProvider)) await setup.setRunning(false);
    return true;
  }
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
  if (connect && !ref.read(isStartProvider)) {
    await setup.setRunning(true, initialize: !ref.read(initProvider));
  }
  return true;
}

/// Each target's config and service state, so Android's service can switch
/// with the app closed.
Future<Map<String, dynamic>> renderTargets(
  WidgetRef ref,
  AutoTunnelSettings settings,
) async {
  final keyed = <String, TunnelTarget>{
    for (final c in settings.connections)
      if (c.target case final target? when !target.disconnects) c.id: target,
    for (final a in settings.apps)
      if (a.target case final target? when !target.disconnects) a.id: target,
  };
  final profiles = ref.read(profilesProvider);
  final dir = Directory(join(await appPath.homeDirPath, 'autotunnel'));
  await dir.create(recursive: true);
  final yamls = <int, String>{};
  final rendered = <String, dynamic>{};
  for (final MapEntry(:key, value: target) in keyed.entries) {
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
    rendered[key] = {
      'profileId': profile.id,
      'path': path,
      'label': profile.realLabel,
      'selectedMap': {...profile.selectedMap, ...target.picks},
    };
  }
  return rendered;
}

/// The switch Android's service made while the app was closed, if any.
Future<String?> takeAppliedKey() async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.reload();
  final key = prefs.getString(_appliedKey);
  if (key != null) await prefs.remove(_appliedKey);
  return key;
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
