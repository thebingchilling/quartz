import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'warp.dart';

/// A generated WARP profile: the Clash profile it lives in, and what's needed
/// to show its expiry and build it again.
class WarpEntry {
  const WarpEntry({
    required this.profileId,
    required this.name,
    required this.options,
    required this.registration,
    this.awg,
  });

  final int profileId;
  final String name;
  final WarpOptions options;
  final WarpRegistration registration;
  final WarpAwg? awg;

  DateTime get expiresAt => registration.expiresAt();

  Map<String, dynamic> toJson() => {
    'profileId': profileId,
    'name': name,
    'options': options.toJson(),
    'registration': registration.toJson(),
    'awg': awg?.i,
  };

  factory WarpEntry.fromJson(Map<String, dynamic> json) => WarpEntry(
    profileId: json['profileId'] as int,
    name: json['name'] as String,
    options: WarpOptions.fromJson(
      (json['options'] as Map).cast<String, dynamic>(),
    ),
    registration: WarpRegistration.fromJson(
      (json['registration'] as Map).cast<String, dynamic>(),
    ),
    awg: (json['awg'] as List?) == null
        ? null
        : WarpAwg((json['awg'] as List).cast<String>()),
  );
}

final warpEntriesProvider = NotifierProvider<WarpEntries, List<WarpEntry>>(
  WarpEntries.new,
);

class WarpEntries extends Notifier<List<WarpEntry>> {
  static const _key = 'quartz.warp.entries';

  @override
  List<WarpEntry> build() {
    _load();
    return const [];
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final text = prefs.getString(_key);
    if (text == null) return;
    try {
      state = [
        for (final item in jsonDecode(text) as List)
          WarpEntry.fromJson((item as Map).cast<String, dynamic>()),
      ];
    } on Object catch (error) {
      commonPrint.log('WARP entries unreadable: $error');
    }
  }

  Future<void> _save(List<WarpEntry> entries) async {
    state = entries;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _key,
      jsonEncode([for (final entry in entries) entry.toJson()]),
    );
  }

  WarpApi get _api => WarpApi(request.dio);

  Future<List<String>> _captured() async => WarpAwg.parseCaptured(
    await rootBundle.loadString('assets/data/warp-awg-payloads.txt'),
  );

  Future<Profile> _write(Profile profile, String yaml) => profile.saveFile(
    Uint8List.fromList(utf8.encode(yaml)),
    validate: (path, key) =>
        ref.read(coreHandlerProvider).validateProfile(path, key),
  );

  String _nameFor(WarpVariant variant) {
    final base = variant == WarpVariant.awg ? 'WARP AWG' : 'WARP';
    final taken = {for (final entry in state) entry.name};
    if (!taken.contains(base)) return base;
    var n = 2;
    while (taken.contains('$base $n')) {
      n++;
    }
    return '$base $n';
  }

  /// Registers with Cloudflare and adds a Clash profile for it.
  Future<WarpEntry> generate(WarpOptions options) async {
    final registration = await _api.register();
    final awg = options.variant == WarpVariant.awg
        ? WarpAwg.pick(await _captured())
        : null;
    final name = _nameFor(options.variant);
    final profile = await _write(
      Profile.normal(label: name),
      buildWarpProfile(
        name: name,
        registration: registration,
        options: options,
        awg: awg,
      ),
    );
    ref.read(profilesActionProvider.notifier).putProfile(profile);
    final entry = WarpEntry(
      profileId: profile.id,
      name: name,
      options: options,
      registration: registration,
      awg: awg,
    );
    await _save([...state, entry]);
    return entry;
  }

  /// A new registration (a fresh 90 days) written into the same profile; the
  /// old one is revoked.
  Future<void> regenerate(WarpEntry entry) async {
    final profile = ref.read(profilesProvider).getProfile(entry.profileId);
    if (profile == null) {
      await _save([
        for (final e in state)
          if (e != entry) e,
      ]);
      throw const WarpException('Its profile was deleted');
    }
    final registration = await _api.register();
    final awg = entry.options.variant == WarpVariant.awg
        ? WarpAwg.pick(await _captured())
        : null;
    final updated = await _write(
      profile,
      buildWarpProfile(
        name: entry.name,
        registration: registration,
        options: entry.options,
        awg: awg,
      ),
    );
    ref.read(profilesProvider.notifier).put(updated);
    try {
      await _api.delete(entry.registration);
    } on WarpException catch (error) {
      commonPrint.log('WARP revoke failed: $error');
    }
    await _save([
      for (final e in state)
        if (e == entry)
          WarpEntry(
            profileId: e.profileId,
            name: e.name,
            options: e.options,
            registration: registration,
            awg: awg,
          )
        else
          e,
    ]);
  }

  /// Revokes the registration and removes its profile.
  Future<void> delete(WarpEntry entry) async {
    try {
      await _api.delete(entry.registration);
    } on WarpException catch (error) {
      commonPrint.log('WARP revoke failed: $error');
    }
    if (ref.read(profilesProvider).getProfile(entry.profileId) != null) {
      await ref
          .read(profilesActionProvider.notifier)
          .deleteProfile(entry.profileId);
    }
    await _save([
      for (final e in state)
        if (e != entry) e,
    ]);
  }
}
