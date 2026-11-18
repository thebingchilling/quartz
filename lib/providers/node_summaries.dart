import 'dart:io';

import 'package:fl_clash/common/common.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'config.dart';
import 'core.dart';

/// Each proxy's condensed summary by name, read by the app from the config it
/// applied and the provider files, so the Core stays stock. Read again when
/// the groups change, which follows every apply and provider update.
final nodeSummariesProvider =
    NotifierProvider<NodeSummaries, Map<String, String>>(NodeSummaries.new);

class NodeSummaries extends Notifier<Map<String, String>> {
  String? _scanned;
  bool _scanning = false;
  bool _again = false;

  @override
  Map<String, String> build() {
    ref.listen(groupsProvider, (_, _) => _refresh());
    ref.listen(initProvider, (_, _) => _refresh());
    _refresh();
    return const {};
  }

  Future<void> _refresh() async {
    if (!ref.read(initProvider)) return;
    if (_scanning) {
      _again = true;
      return;
    }
    _scanning = true;
    try {
      do {
        _again = false;
        await _scan();
      } while (_again && ref.mounted);
    } on Exception catch (error) {
      commonPrint.log('Node summaries: $error');
    } finally {
      _scanning = false;
    }
  }

  /// The config's and the provider files' modification times: groups change
  /// on every pick and delay test, the files only when there is more to read.
  Future<String?> _stamp(String configPath) async {
    final config = File(configPath);
    if (!await config.exists()) return null;
    final stamps = ['${(await config.stat()).modified}'];
    final profileId = ref.read(currentProfileIdProvider);
    if (profileId != null) {
      final dir = Directory(
        await appPath.getProviderDirPath(
          profileId,
          proxiesProviderDirectoryName,
        ),
      );
      if (await dir.exists()) {
        await for (final entry in dir.list()) {
          stamps.add('${entry.path}@${(await entry.stat()).modified}');
        }
      }
    }
    return stamps.join('|');
  }

  Future<void> _scan() async {
    final configPath = await appPath.configFilePath;
    final stamp = await _stamp(configPath);
    if (stamp == null || stamp == _scanned) return;
    final scan = await compute(scanNodeSummaries, configPath);
    final summaries = {...scan.summaries};
    final core = ref.read(coreHandlerProvider);
    for (final file in scan.pending) {
      try {
        final proxies = await core.convertProxies(file.path);
        addNodeSummaries(summaries, proxies, override: file.override);
      } on Exception {
        continue;
      }
    }
    if (!ref.mounted) return;
    _scanned = stamp;
    if (!mapEquals(summaries, state)) state = summaries;
  }
}
