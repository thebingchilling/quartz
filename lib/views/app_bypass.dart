import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/views/program_picker.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Windows' access control: programs that go around the tunnel, or the only
/// ones that use it.
class AppBypassView extends ConsumerWidget {
  const AppBypassView({super.key});

  void _update(
    WidgetRef ref,
    AccessControlProps Function(AccessControlProps) change,
  ) {
    ref
        .read(vpnSettingProvider.notifier)
        .update(
          (state) => state.copyWith(
            accessControlProps: change(state.accessControlProps),
          ),
        );
  }

  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final props = ref.read(vpnSettingProvider).accessControlProps;
    final whitelist = props.mode == AccessControlMode.acceptSelected;
    final list = whitelist ? props.acceptList : props.rejectList;
    final apps = await pickPrograms(context, list);
    if (apps == null) return;
    _update(
      ref,
      (props) => whitelist
          ? props.copyWith(acceptList: apps)
          : props.copyWith(rejectList: apps),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.appLocalizations;
    final props = ref.watch(
      vpnSettingProvider.select((state) => state.accessControlProps),
    );
    final whitelist = props.mode == AccessControlMode.acceptSelected;
    final list = whitelist ? props.acceptList : props.rejectList;
    return CommonScaffold(
      title: l.accessControl,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.appsList),
                title: Text(l.accessControl),
                value: props.enable,
                onChanged: (value) =>
                    _update(ref, (props) => props.copyWith(enable: value)),
              ),
              ListItem<AccessControlMode>.options(
                leading: const GlyphIcon(AppGlyphs.filter),
                title: Text(l.appBypassMode),
                subtitle: Text(_modeText(l, props.mode)),
                dialogTitle: l.appBypassMode,
                options: AccessControlMode.values,
                value: props.mode,
                textBuilder: (mode) => _modeText(l, mode),
                onChanged: (mode) {
                  if (mode != null) {
                    _update(ref, (props) => props.copyWith(mode: mode));
                  }
                },
              ),
            ],
            footer: l.appBypassDesc,
          ),
          generateSectionV3(
            spaced: true,
            items: [
              for (final app in list)
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.appsList),
                  title: Text(app.split(RegExp(r'[\\/]')).last),
                  subtitle: app.contains(RegExp(r'[\\/]')) ? Text(app) : null,
                  trailing: IconButton(
                    tooltip: l.delete,
                    onPressed: () => _update(
                      ref,
                      (props) => whitelist
                          ? props.copyWith(
                              acceptList: [
                                ...props.acceptList.where((a) => a != app),
                              ],
                            )
                          : props.copyWith(
                              rejectList: [
                                ...props.rejectList.where((a) => a != app),
                              ],
                            ),
                    ),
                    icon: const GlyphIcon(AppGlyphs.delete),
                  ),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelAddApp),
                onTap: () => unawaited(_add(context, ref)),
              ),
            ],
          ),
        ],
      ),
    );
  }

  String _modeText(AppLocalizations l, AccessControlMode mode) =>
      switch (mode) {
        AccessControlMode.rejectSelected => l.appBypassBlacklist,
        AccessControlMode.acceptSelected => l.appBypassWhitelist,
      };
}
