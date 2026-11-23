import 'dart:ffi';
import 'dart:io';
import 'dart:isolate';

import 'package:ffi/ffi.dart';
import 'package:win32/win32.dart';

// Win32 directly, never PowerShell: antivirus heuristics flag apps driving it.

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
