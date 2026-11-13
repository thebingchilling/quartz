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
    this.includePro = true,
    this.profileId,
    this.updatedAt,
    this.count = 0,
  });

  /// The OpenVPN credentials from Windscribe's config generator, not the
  /// account login.
  final String username;
  final String password;
  final WindscribeProto proto;
  final int port;
  final bool includePro;
  final int? profileId;
  final DateTime? updatedAt;
  final int count;

  bool get hasCredentials => username.isNotEmpty && password.isNotEmpty;

  WindscribeSettings copyWith({
    String? username,
    String? password,
    WindscribeProto? proto,
    int? port,
    bool? includePro,
    int? profileId,
    DateTime? updatedAt,
    int? count,
  }) => WindscribeSettings(
    username: username ?? this.username,
    password: password ?? this.password,
    proto: proto ?? this.proto,
    port: port ?? this.port,
    includePro: includePro ?? this.includePro,
    profileId: profileId ?? this.profileId,
    updatedAt: updatedAt ?? this.updatedAt,
    count: count ?? this.count,
  );

  Map<String, dynamic> toJson() => {
    'username': username,
    'password': password,
    'proto': proto.name,
    'port': port,
    'includePro': includePro,
    'profileId': profileId,
    'updatedAt': updatedAt?.toIso8601String(),
    'count': count,
  };

  factory WindscribeSettings.fromJson(Map<String, dynamic> json) =>
      WindscribeSettings(
        username: json['username'] as String? ?? '',
        password: json['password'] as String? ?? '',
        proto:
            WindscribeProto.values.asNameMap()[json['proto']] ??
            WindscribeProto.udp,
        port: json['port'] as int? ?? 443,
        includePro: json['includePro'] as bool? ?? true,
        profileId: json['profileId'] as int?,
        updatedAt: DateTime.tryParse(json['updatedAt'] as String? ?? ''),
        count: json['count'] as int? ?? 0,
      );
}

final windscribeProvider =
    NotifierProvider<WindscribeNotifier, WindscribeSettings>(
      WindscribeNotifier.new,
    );

class WindscribeNotifier extends Notifier<WindscribeSettings> {
  static const _key = 'quartz.windscribe';
  static const label = 'Windscribe';

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
    final settings = state;
    if (!settings.hasCredentials) {
      throw const FormatException('Enter the OpenVPN credentials first');
    }
    final servers = [
      for (final server in await WindscribeApi(request.dio).servers())
        if (settings.includePro || !server.premium) server,
    ];
    final yaml = buildWindscribeProfile(
      servers: servers,
      username: settings.username,
      password: settings.password,
      proto: settings.proto,
      port: settings.port,
    );
    final existing = settings.profileId == null
        ? null
        : ref.read(profilesProvider).getProfile(settings.profileId!);
    final profile = await (existing ?? Profile.normal(label: label)).saveFile(
      Uint8List.fromList(utf8.encode(yaml)),
      validate: (path, key) =>
          ref.read(coreHandlerProvider).validateProfile(path, key),
    );
    if (existing == null) {
      ref.read(profilesActionProvider.notifier).putProfile(profile);
    } else {
      ref.read(profilesProvider.notifier).put(profile);
    }
    await update(
      (state) => state.copyWith(
        profileId: profile.id,
        updatedAt: DateTime.now(),
        count: servers.length,
      ),
    );
    return servers.length;
  }

  /// Run at startup: keeps an existing Windscribe profile's list current.
  Future<void> refreshIfStale() async {
    await _loaded.future;
    final updated = state.updatedAt;
    if (state.profileId == null || !state.hasCredentials) return;
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
