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
        {'name': 'Quartz-0.9.0-android-arm64-v8a.apk'},
        {'name': 'Quartz-0.9.0-android-armeabi-v7a.apk'},
        {'name': 'Quartz-0.9.0-windows-amd64-setup.exe'},
        {'name': 'Quartz-0.9.0-windows-amd64.zip'},
      ],
    };

    String? pick(String os, String platform) =>
        updateAssetFor(release, os: os, dartPlatform: platform)?['name']
            as String?;

    test('picks the APK for the device ABI', () {
      expect(
        pick('android', '3.13.2 on "android_arm64"'),
        'Quartz-0.9.0-android-arm64-v8a.apk',
      );
      expect(
        pick('android', '3.13.2 on "android_arm"'),
        'Quartz-0.9.0-android-armeabi-v7a.apk',
      );
    });

    test('picks the Windows installer, nothing elsewhere', () {
      expect(pick('windows', ''), 'Quartz-0.9.0-windows-amd64-setup.exe');
      expect(pick('linux', ''), isNull);
    });
  });
}
