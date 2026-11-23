import 'dart:io';
import 'dart:isolate';

import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:fl_clash/common/exception.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// Quartz releases are tagged `quartz-<version>-<build>`, where the build
/// is the CI run that made them and also the app's build number.
int? releaseBuildNumber(String tag) {
  final match = RegExp(r'-(\d+)$').firstMatch(tag);
  return match == null ? null : int.tryParse(match.group(1)!);
}

/// Set by the standalone EXE's launcher to its own path.
const portableExeEnv = 'QUARTZ_PORTABLE_EXE';

/// Kept in step with AFTER_UPDATE in services/launcher/src/main.rs.
const afterUpdateArgument = '--after-update';

bool get isPortable => Platform.environment.containsKey(portableExeEnv);

/// What autostart and link handlers start: the standalone EXE outlives the
/// versioned folder it unpacks into, which a later version prunes.
String get appLaunchPath =>
    Platform.environment[portableExeEnv] ?? Platform.resolvedExecutable;

/// The release file this device installs: the APK for its ABI, or the Windows
/// installer (the standalone EXE when [portable]). Null on other platforms, or
/// when the release lacks it.
Map<String, dynamic>? updateAssetFor(
  Map<String, dynamic> release, {
  required String os,
  required String dartPlatform,
  bool portable = false,
}) {
  final suffix = switch (os) {
    'android' =>
      dartPlatform.contains('arm64')
          ? '-android-arm64-v8a.apk'
          : '-android-armeabi-v7a.apk',
    'windows' when portable => '-windows-amd64-portable.exe',
    'windows' => '-windows-amd64-setup.exe',
    _ => null,
  };
  if (suffix == null) return null;
  final assets = (release['assets'] as List?) ?? const [];
  for (final asset in assets.whereType<Map<String, dynamic>>()) {
    if ((asset['name'] as String? ?? '').endsWith(suffix)) return asset;
  }
  return null;
}

const checksumsAssetName = 'SHA256SUMS';

String safeAssetFileName(Map<String, dynamic> asset) {
  final name = asset['name'] as String? ?? '';
  final base = p.basename(name.replaceAll('\\', '/'));
  if (base != name || base.isEmpty || base == '.' || base == '..') {
    throw MessageException('Refusing update file name "$name"');
  }
  return base;
}

Map<String, String> parseChecksums(String text) {
  final checksums = <String, String>{};
  for (final line in text.split('\n')) {
    final match = RegExp(
      r'^([0-9a-fA-F]{64})\s+\*?(?:\./)?(.+?)\s*$',
    ).firstMatch(line);
    if (match != null) {
      checksums[match.group(2)!] = match.group(1)!.toLowerCase();
    }
  }
  return checksums;
}

Future<String> expectedChecksum(
  Dio dio,
  Map<String, dynamic> release,
  String fileName,
) async {
  final assets = (release['assets'] as List?) ?? const [];
  final sums = assets.whereType<Map<String, dynamic>>().where(
    (asset) => asset['name'] == checksumsAssetName,
  );
  if (sums.isEmpty) {
    throw const MessageException('The release has no SHA256SUMS');
  }
  final response = await dio.get<String>(
    sums.first['browser_download_url'] as String,
    options: Options(responseType: ResponseType.plain),
  );
  final checksum = parseChecksums(response.data ?? '')[fileName];
  if (checksum == null) {
    throw MessageException('SHA256SUMS has no entry for $fileName');
  }
  return checksum;
}

Future<String> fileSha256(String path) => Isolate.run(
  () async => (await sha256.bind(File(path).openRead()).first).toString(),
);

/// A release without SHA256SUMS fails closed: an unverifiable file is never installed.
Future<File> downloadUpdate(
  Dio dio,
  Map<String, dynamic> release,
  Map<String, dynamic> asset, {
  ProgressCallback? onProgress,
}) async {
  final fileName = safeAssetFileName(asset);
  final expected = await expectedChecksum(dio, release, fileName);
  final dir = Directory(
    p.join((await getTemporaryDirectory()).path, 'updates'),
  );
  if (await dir.exists()) await dir.delete(recursive: true);
  await dir.create(recursive: true);
  final file = File(p.join(dir.path, fileName));
  await dio.download(
    asset['browser_download_url'] as String,
    file.path,
    onReceiveProgress: onProgress,
  );
  if (await fileSha256(file.path) != expected) {
    await file.delete();
    throw MessageException('$fileName does not match its SHA256SUMS entry');
  }
  return file;
}
