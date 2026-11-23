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

/// The running programs and the chosen ones, chosen first, plus Browse for
/// any `.exe`.
class _ProgramPicker extends ConsumerStatefulWidget {
  const _ProgramPicker({required this.selected});

  final List<String> selected;

  @override
  ConsumerState<_ProgramPicker> createState() => _ProgramPickerState();
}

class _ProgramPickerState extends ConsumerState<_ProgramPicker> {
  late final Set<String> _chosen = {...widget.selected};
  late final Set<String> _pinned = {...widget.selected};
  List<String> _running = const [];
  String _query = '';

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    final running = await runningProgramPaths();
    if (mounted) setState(() => _running = running);
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
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final packages =
        [
              for (final path in {..._pinned, ..._running})
                Package(
                  packageName: path,
                  label: _fileName(path),
                  system: false,
                  internet: true,
                  lastUpdateTime: 0,
                ),
            ]
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
      body: Builder(
        builder: (context) => ListView.builder(
          padding: EdgeInsets.only(
            top: context.contentTopPadding,
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
    );
  }
}
