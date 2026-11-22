import 'dart:async';

import 'package:collection/collection.dart';
import 'package:file_picker/file_picker.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/features/app_bypass/windows_programs.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/views/access.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Windows' program picker for access control; pops the chosen full paths.
Future<List<String>?> pickPrograms(
  BuildContext context,
  List<String> selected,
) => BaseNavigator.push<List<String>>(
  context,
  _ProgramPicker(selected: selected),
);

String _fileName(String path) => path.split(RegExp(r'[\\/]')).last;

enum _Tab { programs, running }

/// The Start menu's programs and the running ones, chosen ones first, plus
/// Browse for any `.exe`.
class _ProgramPicker extends ConsumerStatefulWidget {
  const _ProgramPicker({required this.selected});

  final List<String> selected;

  @override
  ConsumerState<_ProgramPicker> createState() => _ProgramPickerState();
}

class _ProgramPickerState extends ConsumerState<_ProgramPicker>
    with SingleTickerProviderStateMixin {
  late final _tabs = TabController(length: _Tab.values.length, vsync: this)
    ..addListener(() => setState(() {}));
  late final Set<String> _chosen = {...widget.selected};
  late final Set<String> _pinned = {...widget.selected};
  List<Package> _programs = const [];
  List<Package> _running = const [];
  String _query = '';

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  static Package _package(String path, String label) => Package(
    packageName: path,
    label: label,
    system: false,
    internet: true,
    lastUpdateTime: 0,
  );

  Future<void> _load() async {
    final (programs, running) = await (
      installedPrograms(),
      runningProgramPaths(),
    ).wait;
    if (!mounted) return;
    final listed = {for (final (_, path) in programs) path.toLowerCase()};
    setState(() {
      _programs = [
        for (final (name, path) in programs) _package(path, name),
        for (final path in _chosen)
          if (!listed.contains(path.toLowerCase()))
            _package(path, _fileName(path)),
      ];
      _running = [for (final path in running) _package(path, _fileName(path))];
    });
  }

  Future<void> _browse() async {
    final path = (await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: const ['exe'],
    ))?.path;
    if (path == null || !mounted) return;
    setState(() {
      _chosen.add(path);
      _pinned.add(path);
      if (!_programs.any((p) => p.packageName == path)) {
        _programs = [..._programs, _package(path, _fileName(path))];
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final tab = _Tab.values[_tabs.index];
    final packages = (tab == _Tab.programs ? _programs : _running)
        .whereMatches(SearchQuery(_query), (p) => [p.label, p.packageName])
        .sortedBy((p) => p.label.toLowerCase())
        .sortedBy<num>((p) => _pinned.contains(p.packageName) ? 0 : 1);
    return CommonScaffold(
      title: l.autoTunnelChooseApps,
      searchState: AppBarSearchState(
        onSearch: (value) => setState(() => _query = value),
      ),
      actions: [
        IconButton(
          tooltip: l.autoTunnelBrowse,
          onPressed: _browse,
          icon: const GlyphIcon(AppGlyphs.importFile),
        ),
        IconButton(
          tooltip: l.save,
          onPressed: () => Navigator.of(context).pop(_chosen.toList()),
          icon: const GlyphIcon(AppGlyphs.check),
        ),
      ],
      body: AppBarClearance(
        child: Column(
          children: [
            TabBar(
              controller: _tabs,
              isScrollable: true,
              tabAlignment: TabAlignment.start,
              dividerColor: Colors.transparent,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              tabs: [
                for (final tab in _Tab.values)
                  Tab(
                    text: switch (tab) {
                      _Tab.programs => l.autoTunnelPrograms,
                      _Tab.running => l.autoTunnelRunning,
                    },
                  ),
              ],
            ),
            Expanded(
              child: Builder(
                builder: (context) => ListView.builder(
                  padding: EdgeInsets.only(
                    top: 8,
                    bottom: 16 + BottomInsetScope.of(context),
                  ),
                  itemCount: packages.length,
                  itemExtent: 72,
                  itemBuilder: (_, index) {
                    final package = packages[index];
                    return PackageListItem(
                      key: Key(package.packageName),
                      package: package,
                      value: _chosen.contains(package.packageName),
                      onChanged: (value) => setState(
                        () => value == true
                            ? _chosen.add(package.packageName)
                            : _chosen.remove(package.packageName),
                      ),
                    );
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
