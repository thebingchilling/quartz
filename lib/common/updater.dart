import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// Quartz releases are tagged `quartz-flclash-<version>-<build>`, where the build
/// is the CI run that made them and also the app's build number.
int? releaseBuildNumber(String tag) {
  final match = RegExp(r'-(\d+)$').firstMatch(tag);
  return match == null ? null : int.tryParse(match.group(1)!);
}

/// The release file this device installs: the APK for its ABI, or the Windows
/// installer. Null on other platforms, or when the release lacks it.
Map<String, dynamic>? updateAssetFor(
  Map<String, dynamic> release, {
  required String os,
  required String dartPlatform,
}) {
  final suffix = switch (os) {
    'android' =>
      dartPlatform.contains('arm64')
          ? '-android-arm64-v8a.apk'
          : '-android-armeabi-v7a.apk',
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

Future<File> downloadUpdate(
  Dio dio,
  Map<String, dynamic> asset, {
  ProgressCallback? onProgress,
}) async {
  final dir = Directory(
    p.join((await getTemporaryDirectory()).path, 'updates'),
  );
  if (await dir.exists()) await dir.delete(recursive: true);
  await dir.create(recursive: true);
  final file = File(p.join(dir.path, asset['name'] as String));
  await dio.download(
    asset['browser_download_url'] as String,
    file.path,
    onReceiveProgress: onProgress,
  );
  return file;
}
