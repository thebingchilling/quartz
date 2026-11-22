import 'dart:io';

/// Saved Wi-Fi profiles, then those in range (hidden until location is on).
Future<List<String>> windowsWifiNetworks() async {
  if (!Platform.isWindows) return const [];
  Future<List<String>> names(List<String> args, RegExp line) async {
    final result = await Process.run('netsh', ['wlan', 'show', ...args]);
    if (result.exitCode != 0) return const [];
    return [
      for (final row in (result.stdout as String).split('\n'))
        if (line.firstMatch(row) case final match?)
          if (match.group(1)!.trim() case final name when name.isNotEmpty) name,
    ];
  }

  final (saved, nearby) = await (
    names(const ['profiles'], RegExp(r'^\s*All User Profile\s*:\s*(.*)$')),
    names(const ['networks'], RegExp(r'^\s*SSID \d+\s*:\s*(.*)$')),
  ).wait;
  return {...saved, ...nearby}.toList();
}
