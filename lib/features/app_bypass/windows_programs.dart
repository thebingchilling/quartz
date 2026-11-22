import 'dart:io';

const _listPaths =
    'Get-Process | Where-Object Path | '
    'Select-Object -ExpandProperty Path -Unique | Sort-Object';

/// Full paths of running programs.
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

/// Start menu programs as (name, full path).
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
