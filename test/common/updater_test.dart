import 'package:fl_clash/common/exception.dart';
import 'package:fl_clash/common/updater.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('reads the build number from a Quartz release tag', () {
    expect(releaseBuildNumber('quartz-flclash-0.9.0-12'), 12);
    expect(releaseBuildNumber('v0.9.0'), isNull);
  });

  group('updateAssetFor', () {
    final release = {
      'assets': [
        {'name': 'quartz-android-arm64-v8a.apk'},
        {'name': 'quartz-android-armeabi-v7a.apk'},
        {'name': 'quartz-windows-amd64-setup.exe'},
        {'name': 'quartz-windows-amd64-portable.exe'},
      ],
    };

    String? pick(String os, String platform) =>
        updateAssetFor(release, os: os, dartPlatform: platform)?['name']
            as String?;

    test('picks the APK for the device ABI', () {
      expect(
        pick('android', '3.13.2 on "android_arm64"'),
        'quartz-android-arm64-v8a.apk',
      );
      expect(
        pick('android', '3.13.2 on "android_arm"'),
        'quartz-android-armeabi-v7a.apk',
      );
    });

    test('still finds the files of releases named the old way', () {
      expect(
        updateAssetFor(
          {
            'assets': [
              {'name': 'Quartz-0.9.0-android-arm64-v8a.apk'},
            ],
          },
          os: 'android',
          dartPlatform: 'android_arm64',
        )?['name'],
        'Quartz-0.9.0-android-arm64-v8a.apk',
      );
    });

    test('picks the Windows installer, nothing elsewhere', () {
      expect(pick('windows', ''), 'quartz-windows-amd64-setup.exe');
      expect(pick('linux', ''), isNull);
    });

    test('the standalone EXE updates to the standalone EXE', () {
      expect(
        updateAssetFor(
          release,
          os: 'windows',
          dartPlatform: '',
          portable: true,
        )?['name'],
        'quartz-windows-amd64-portable.exe',
      );
    });
  });

  test('reads sha256sum output, with or without the ./ prefix', () {
    final a = 'A' * 64;
    final b = 'b' * 64;
    expect(
      parseChecksums(
        '$a  ./quartz-android-arm64-v8a.apk\n'
        '$b *quartz-windows-amd64-setup.exe\n'
        'not a checksum line\n',
      ),
      {
        'quartz-android-arm64-v8a.apk': 'a' * 64,
        'quartz-windows-amd64-setup.exe': b,
      },
    );
  });

  test('refuses an asset name that would leave the updates folder', () {
    expect(
      safeAssetFileName({'name': 'quartz-android-arm64-v8a.apk'}),
      'quartz-android-arm64-v8a.apk',
    );
    for (final name in [
      '../evil.apk',
      r'..\evil.exe',
      '/tmp/x.apk',
      '..',
      '',
    ]) {
      expect(
        () => safeAssetFileName({'name': name}),
        throwsA(isA<MessageException>()),
        reason: name,
      );
    }
  });
}
