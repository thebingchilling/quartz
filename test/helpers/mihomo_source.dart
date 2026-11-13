import 'dart:io';

/// The stock mihomo module core/go.mod requires, as Go keeps it in its module
/// cache (`go mod download` fills it). The tests read its config structs.
final String mihomoSource = () {
  final goMod = File('core/go.mod').readAsStringSync();
  final version = RegExp(
    r'github\.com/metacubex/mihomo (v\S+)',
  ).firstMatch(goMod)!.group(1)!;
  final cache = (Process.runSync('go', ['env', 'GOMODCACHE']).stdout as String)
      .trim();
  return '$cache/github.com/metacubex/mihomo@$version';
}();
