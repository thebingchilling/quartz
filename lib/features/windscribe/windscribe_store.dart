import 'dart:async';
import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'windscribe.dart';

class WindscribeSettings {
  const WindscribeSettings({
    this.username = '',
    this.password = '',
    this.proto = WindscribeProto.udp,
    this.port = 443,
    this.profileId,
    this.updatedAt,
    this.count = 0,
    this.servers = const [],
    this.country,
  });

  /// The OpenVPN credentials from Windscribe's config generator, not the
  /// account login.
  final String username;
  final String password;
  final WindscribeProto proto;
  final int port;
  final int? profileId;
  final DateTime? updatedAt;
  final int count;

  /// The list the profile was last written from, for the location picker.
  final List<WindscribeServer> servers;

  /// The location the profile holds the servers of; null until one is picked.
  final String? country;

  bool get hasCredentials => username.isNotEmpty && password.isNotEmpty;

  WindscribeSettings copyWith({
    String? username,
    String? password,
    WindscribeProto? proto,
    int? port,
    int? profileId,
    DateTime? updatedAt,
    int? count,
    List<WindscribeServer>? servers,
    String? country,
  }) => WindscribeSettings(
    username: username ?? this.username,
    password: password ?? this.password,
    proto: proto ?? this.proto,
    port: port ?? this.port,
    profileId: profileId ?? this.profileId,
    updatedAt: updatedAt ?? this.updatedAt,
    count: count ?? this.count,
    servers: servers ?? this.servers,
    country: country ?? this.country,
  );

  Map<String, dynamic> toJson() => {
    'username': username,
    'password': password,
    'proto': proto.name,
    'port': port,
    'profileId': profileId,
    'updatedAt': updatedAt?.toIso8601String(),
    'count': count,
    'servers': [for (final server in servers) server.toJson()],
    'country': country,
  };

  factory WindscribeSettings.fromJson(Map<String, dynamic> json) =>
      WindscribeSettings(
        username: json['username'] as String? ?? '',
        password: json['password'] as String? ?? '',
        proto:
            WindscribeProto.values.asNameMap()[json['proto']] ??
            WindscribeProto.udp,
        port: json['port'] as int? ?? 443,
        profileId: json['profileId'] as int?,
        updatedAt: DateTime.tryParse(json['updatedAt'] as String? ?? ''),
        count: json['count'] as int? ?? 0,
        servers: [
          for (final item in json['servers'] as List? ?? const [])
            ?WindscribeServer.fromJson(item),
        ],
        country: json['country'] as String?,
      );
}

final windscribeProfileActiveProvider = Provider<bool>((ref) {
  final profileId = ref.watch(windscribeProvider.select((s) => s.profileId));
  return profileId != null && profileId == ref.watch(currentProfileIdProvider);
});

final windscribeProvider =
    NotifierProvider<WindscribeNotifier, WindscribeSettings>(
      WindscribeNotifier.new,
    );

class WindscribeNotifier extends Notifier<WindscribeSettings> {
  static const _key = 'quartz.windscribe';
  static const label = 'windscribe';

  /// Load changes constantly, the set of servers rarely.
  static const staleAfter = Duration(hours: 6);

  final Completer<void> _loaded = Completer();

  @override
  WindscribeSettings build() {
    _load();
    return const WindscribeSettings();
  }

  Future<void> _load() async {
    try {
      final text = (await SharedPreferences.getInstance()).getString(_key);
      if (text != null) {
        state = WindscribeSettings.fromJson(
          (jsonDecode(text) as Map).cast<String, dynamic>(),
        );
      }
    } on Object catch (error) {
      commonPrint.log('Windscribe settings unreadable: $error');
    } finally {
      _loaded.complete();
    }
  }

  Future<void> update(
    WindscribeSettings Function(WindscribeSettings state) change,
  ) async {
    await _loaded.future;
    state = change(state);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, jsonEncode(state.toJson()));
  }

  /// Downloads the server list and writes it into the Windscribe profile,
  /// adding the profile the first time.
  Future<int> refresh() async {
    await _loaded.future;
    if (!state.hasCredentials) {
      throw const FormatException('Enter the OpenVPN credentials first');
    }
    final servers = await WindscribeApi(request.dio).servers();
    await _writeProfile(servers, state.country);
    return servers.length;
  }

  /// Rewrites the profile with [country]'s servers and makes it the one that
  /// runs, so the proxies page lists just that location.
  Future<void> selectCountry(String country) async {
    await _loaded.future;
    if (state.servers.isEmpty || !state.hasCredentials) return;
    await _writeProfile(state.servers, country);
    final profileId = state.profileId;
    if (profileId != null && ref.read(currentProfileIdProvider) != profileId) {
      ref.read(currentProfileIdProvider.notifier).value = profileId;
    }
  }

  Future<void> _writeProfile(
    List<WindscribeServer> servers,
    String? country,
  ) async {
    final settings = state;
    final selected = windscribeCountryOf(servers, country);
    final yaml = buildWindscribeProfile(
      servers: servers,
      country: selected,
      username: settings.username,
      password: settings.password,
      proto: settings.proto,
      port: settings.port,
    );
    final existing = settings.profileId == null
        ? null
        : ref.read(profilesProvider).getProfile(settings.profileId!);
    final profile =
        await (existing?.copyWith(label: label) ?? Profile.normal(label: label))
            .saveFile(
              Uint8List.fromList(utf8.encode(yaml)),
              validate: (path, key) =>
                  ref.read(coreHandlerProvider).validateProfile(path, key),
            );
    if (existing == null) {
      ref.read(profilesActionProvider.notifier).putProfile(profile);
    } else {
      ref.read(profilesActionProvider.notifier).setProfileAndAutoApply(profile);
    }
    await update(
      (state) => state.copyWith(
        profileId: profile.id,
        updatedAt: DateTime.now(),
        count: servers.length,
        servers: servers,
        country: selected,
      ),
    );
  }

  /// Run at startup: keeps an existing Windscribe profile's list current.
  Future<void> refreshIfStale() async {
    await _loaded.future;
    final updated = state.updatedAt;
    final profileId = state.profileId;
    if (profileId == null || !state.hasCredentials) return;
    final profile = ref.read(profilesProvider).getProfile(profileId);
    final outdated = state.country == null || profile?.label != label;
    if (outdated && state.servers.isNotEmpty) {
      final picked = state.country ?? profile?.selectedMap['Windscribe'];
      try {
        await _writeProfile(state.servers, picked);
      } on Object catch (error) {
        commonPrint.log('Windscribe profile rewrite failed: $error');
      }
      return;
    }
    if (updated != null && DateTime.now().difference(updated) < staleAfter) {
      return;
    }
    try {
      await refresh();
    } on Object catch (error) {
      commonPrint.log('Windscribe list update failed: $error');
    }
  }
}
