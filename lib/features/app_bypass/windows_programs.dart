import 'dart:ffi';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';
import 'package:path/path.dart' as p;
import 'package:win32/win32.dart';

// Everything here reads Win32 or files directly: antivirus heuristics flag
// apps that drive PowerShell or netsh.

/// Full paths of running programs.
Future<List<String>> runningProgramPaths() async =>
    Platform.isWindows ? Isolate.run(_runningProgramPaths) : const [];

List<String> _runningProgramPaths() {
  const maxProcesses = 4096;
  const maxPath = 1024;
  final pids = calloc<Uint32>(maxProcesses);
  final needed = calloc<Uint32>();
  final buffer = calloc<Uint16>(maxPath);
  final size = calloc<Uint32>();
  final paths = <String>{};
  try {
    if (!EnumProcesses(pids, maxProcesses * sizeOf<Uint32>(), needed).value) {
      return const [];
    }
    for (var i = 0; i < needed.value ~/ sizeOf<Uint32>(); i++) {
      final process = OpenProcess(
        PROCESS_QUERY_LIMITED_INFORMATION,
        false,
        pids[i],
      ).value;
      if (process.address == 0) continue;
      try {
        size.value = maxPath;
        final ok = QueryFullProcessImageName(
          process,
          PROCESS_NAME_WIN32,
          PWSTR(buffer.cast<Utf16>()),
          size,
        ).value;
        if (!ok) continue;
        final path = buffer.cast<Utf16>().toDartString(length: size.value);
        if (path.toLowerCase().endsWith('.exe')) paths.add(path);
      } finally {
        CloseHandle(process);
      }
    }
  } finally {
    calloc
      ..free(pids)
      ..free(needed)
      ..free(buffer)
      ..free(size);
  }
  return paths.toList()..sort();
}

/// Start menu programs as (name, full path).
Future<List<(String, String)>> installedPrograms() async {
  if (!Platform.isWindows) return const [];
  final env = Platform.environment;
  final dirs = [
    if (env['ProgramData'] case final data?)
      p.join(data, r'Microsoft\Windows\Start Menu\Programs'),
    if (env['APPDATA'] case final appData?)
      p.join(appData, r'Microsoft\Windows\Start Menu\Programs'),
  ];
  return Isolate.run(() => _shortcutTargets(dirs));
}

List<(String, String)> _shortcutTargets(List<String> dirs) {
  final seen = <String>{};
  final programs = <(String, String)>[];
  for (final dir in dirs) {
    final entries = Directory(dir).existsSync()
        ? Directory(dir).listSync(recursive: true, followLinks: false)
        : const <FileSystemEntity>[];
    for (final entry in entries) {
      if (entry is! File || !entry.path.toLowerCase().endsWith('.lnk')) {
        continue;
      }
      final target = shortcutTarget(entry.readAsBytesSync());
      if (target == null ||
          !target.toLowerCase().endsWith('.exe') ||
          !seen.add(target.toLowerCase())) {
        continue;
      }
      programs.add((p.basenameWithoutExtension(entry.path), target));
    }
  }
  return programs;
}

/// The local path a Windows shortcut points to, from its LinkInfo ([MS-SHLLINK]
/// 2.3); null for shortcuts with none, such as Store and advertised ones.
String? shortcutTarget(Uint8List bytes) {
  try {
    final data = ByteData.sublistView(bytes);
    int u16(int at) => data.getUint16(at, Endian.little);
    int u32(int at) => data.getUint32(at, Endian.little);
    if (u32(0) != 0x4C) return null;
    final flags = u32(0x14);
    var info = 0x4C;
    if (flags & 0x1 != 0) info += 2 + u16(info);
    if (flags & 0x2 == 0 || u32(info + 8) & 0x1 == 0) return null;
    String ansi(int at) {
      final end = bytes.indexOf(0, at);
      return String.fromCharCodes(bytes.sublist(at, end));
    }

    String utf16(int at) {
      final units = <int>[];
      for (var i = at; u16(i) != 0; i += 2) {
        units.add(u16(i));
      }
      return String.fromCharCodes(units);
    }

    final unicode = u32(info + 4) >= 0x24;
    final base = unicode
        ? utf16(info + u32(info + 0x1C))
        : ansi(info + u32(info + 0x10));
    final suffix = unicode
        ? utf16(info + u32(info + 0x20))
        : ansi(info + u32(info + 0x18));
    return base.isEmpty ? null : base + suffix;
  } on RangeError {
    return null;
  }
}
