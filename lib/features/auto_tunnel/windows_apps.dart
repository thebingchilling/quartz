import 'dart:ffi';
import 'dart:io';

import 'package:ffi/ffi.dart';
import 'package:win32/win32.dart';

/// The executable of the window in front, lower-cased (e.g. `bank.exe`).
String? foregroundExecutable() {
  if (!Platform.isWindows) return null;
  final hwnd = GetForegroundWindow();
  final pid = calloc<Uint32>();
  final buffer = calloc<Uint16>(1024);
  final size = calloc<Uint32>()..value = 1024;
  try {
    GetWindowThreadProcessId(hwnd, pid);
    if (pid.value == 0) return null;
    final process = OpenProcess(
      PROCESS_QUERY_LIMITED_INFORMATION,
      false,
      pid.value,
    ).value;
    if (process.address == 0) return null;
    try {
      final ok = QueryFullProcessImageName(
        process,
        PROCESS_NAME_WIN32,
        PWSTR(buffer.cast<Utf16>()),
        size,
      ).value;
      if (!ok) return null;
      final path = buffer.cast<Utf16>().toDartString(length: size.value);
      return path.split(RegExp(r'[\\/]')).last.toLowerCase();
    } finally {
      CloseHandle(process);
    }
  } finally {
    calloc
      ..free(pid)
      ..free(buffer)
      ..free(size);
  }
}

/// Executables now running, lower-cased.
Future<Set<String>> runningExecutables() async {
  if (!Platform.isWindows) return {};
  final result = await Process.run('tasklist', const ['/FO', 'CSV', '/NH']);
  if (result.exitCode != 0) return {};
  return {
    for (final line in (result.stdout as String).split('\n'))
      if (RegExp(r'^"([^"]+)"').firstMatch(line.trim()) case final match?)
        match.group(1)!.toLowerCase(),
  };
}

const _listPaths =
    'Get-Process | Where-Object Path | '
    'Select-Object -ExpandProperty Path -Unique | Sort-Object';

/// Full paths of running programs, for app settings the kill switch can
/// match exactly.
Future<List<String>> runningProgramPaths() async {
  if (!Platform.isWindows) return const [];
  final result = await Process.run('powershell', const [
    '-NoProfile',
    '-Command',
    _listPaths,
  ]);
  if (result.exitCode != 0) return const [];
  return [
    for (final line in (result.stdout as String).split('\n'))
      if (line.trim().toLowerCase().endsWith('.exe')) line.trim(),
  ];
}

const _listShortcuts = r'''
$shell = New-Object -ComObject WScript.Shell
$dirs = @("$env:ProgramData\Microsoft\Windows\Start Menu\Programs",
  "$env:AppData\Microsoft\Windows\Start Menu\Programs")
Get-ChildItem $dirs -Recurse -Filter *.lnk -ErrorAction SilentlyContinue | ForEach-Object {
  $target = $shell.CreateShortcut($_.FullName).TargetPath
  if ($target -like '*.exe') { "$($_.BaseName)`t$target" }
}
''';

/// Start menu programs as (name, full path), the desktop's list of
/// installed apps.
Future<List<(String, String)>> installedPrograms() async {
  if (!Platform.isWindows) return const [];
  final result = await Process.run('powershell', const [
    '-NoProfile',
    '-Command',
    _listShortcuts,
  ]);
  if (result.exitCode != 0) return const [];
  final seen = <String>{};
  return [
    for (final line in (result.stdout as String).split('\n'))
      if (line.trim().split('\t') case [
        final name,
        final path,
      ] when seen.add(path.toLowerCase()))
        (name, path),
  ];
}
