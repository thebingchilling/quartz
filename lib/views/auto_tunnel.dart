import 'dart:async';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:file_picker/file_picker.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/desktop/helper_client.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:fl_clash/features/auto_tunnel/windows_apps.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/views/access.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

const _platform = AutoTunnelNotifier.platform;
const _manual = '\u0000manual';

Future<void> _update(
  WidgetRef ref,
  AutoTunnelSettings Function(AutoTunnelSettings) change,
) async {
  final before = ref.read(autoTunnelProvider).anyKillSwitch;
  await ref.read(autoTunnelProvider.notifier).update(change);
  final after = ref.read(autoTunnelProvider).anyKillSwitch;
  if (system.isWindows && after && !before) await _ensureHelper();
}

/// Windows' kill switch is a firewall the Helper service sets up.
Future<void> _ensureHelper() async {
  final ready = await helperClient.readiness(logFailure: false);
  if (ready == HelperReadiness.ready) return;
  await system.authorizeCore();
}

String _targetText(List<Profile> profiles, TunnelTarget target) {
  final profile = profiles.firstWhereOrNull((p) => p.id == target.profileId);
  final name = profile?.realLabel ?? '?';
  return target.proxy == null ? name : '$name · ${target.proxy}';
}

String _networkText(
  AppLocalizations l,
  NetworkMatch match,
  List<SimCard> sims,
) => switch (match.kind) {
  NetworkMatchKind.wifi => match.ssid ?? l.autoTunnelAnyWifi,
  NetworkMatchKind.mobile =>
    match.simId == null
        ? l.autoTunnelAnyMobile
        : sims.firstWhereOrNull((sim) => sim.id == match.simId)?.title ??
              'SIM ${match.simId}',
};

String _conditionText(
  AppLocalizations l,
  AutoTunnelRule rule,
  List<SimCard> sims,
) {
  final networks = rule.networks
      .map((m) => _networkText(l, m, sims))
      .join(', ');
  final apps = rule.apps.map((app) => app.label).join(', ');
  if (networks.isEmpty && apps.isEmpty) return l.autoTunnelAnyNetwork;
  if (networks.isEmpty) return apps;
  if (apps.isEmpty) return networks;
  final joiner = rule.matchAll ? l.autoTunnelAndWord : l.autoTunnelOrWord;
  return '$networks $joiner $apps';
}

String _actionText(
  AppLocalizations l,
  AutoTunnelRule rule,
  List<Profile> profiles,
) => _outcomeText(l, (connect: rule.connect, target: rule.target), profiles);

class AutoTunnelView extends ConsumerStatefulWidget {
  const AutoTunnelView({super.key});

  @override
  ConsumerState<AutoTunnelView> createState() => _AutoTunnelViewState();
}

class _AutoTunnelViewState extends ConsumerState<AutoTunnelView>
    with WidgetsBindingObserver {
  AutoTunnelPermissions? _permissions;
  List<SimCard> _sims = const [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_refresh());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_refresh());
  }

  Future<void> _refresh() async {
    if (!system.isAndroid) return;
    try {
      final permissions = await _platform.permissions();
      final sims = permissions.phone ? await _platform.simCards() : <SimCard>[];
      if (!mounted) return;
      setState(() {
        _permissions = permissions;
        _sims = sims;
      });
    } on PlatformException catch (error) {
      commonPrint.log('Auto-tunnel permissions: $error');
    }
  }

  Future<void> _request(String kind) async {
    await _platform.request(kind);
    await _refresh();
  }

  List<Widget> _permissionItems(AppLocalizations l, AutoTunnelSettings s) {
    final permissions = _permissions;
    if (permissions == null) return const [];
    final namesWifi = s.activeRules.any(
      (rule) => rule.networks.any((m) => m.ssid != null),
    );
    final hasApps = s.watchedApps.isNotEmpty;
    Widget item(String title, Glyph glyph, String kind, {String? subtitle}) =>
        ListItem(
          leading: GlyphIcon(glyph),
          title: Text(title),
          subtitle: subtitle == null ? null : Text(subtitle),
          trailing: FilledButton.tonal(
            onPressed: () => _request(kind),
            child: Text(l.autoTunnelGrant),
          ),
        );
    return [
      if (namesWifi && !permissions.location)
        item(l.autoTunnelLocation, AppGlyphs.locate, 'location')
      else if (namesWifi && !permissions.backgroundLocation)
        item(
          l.autoTunnelBackgroundLocation,
          AppGlyphs.locate,
          'backgroundLocation',
        ),
      if (!permissions.phone)
        item(l.autoTunnelPhone, AppGlyphs.devices, 'phone'),
      if (hasApps && !permissions.appWatcher)
        item(
          l.autoTunnelAppWatcher,
          AppGlyphs.eye,
          'appWatcher',
          subtitle: l.autoTunnelAppWatcherDesc,
        ),
      if (hasApps && !permissions.usageAccess)
        item(
          l.autoTunnelUsageAccess,
          AppGlyphs.history,
          'usageAccess',
          subtitle: l.autoTunnelUsageAccessDesc,
        ),
    ];
  }

  void _openRule(AutoTunnelRule rule) => unawaited(
    BaseNavigator.push<void>(context, _RuleEditor(rule: rule, sims: _sims)),
  );

  void _openCategory(RuleCategory category) => unawaited(
    BaseNavigator.push<void>(context, _CategoryEditor(category: category)),
  );

  Widget _ruleRow(
    AppLocalizations l,
    AutoTunnelSettings settings,
    List<Profile> profiles,
    int index,
  ) {
    final rule = settings.rules[index];
    final category = settings.categoryOf(rule);
    final condition = _conditionText(l, rule, _sims);
    final details = [
      ?category?.name,
      if (rule.name.isNotEmpty) condition,
      _actionText(l, rule, profiles),
      if (settings.killSwitchOf(rule)) l.autoTunnelKillSwitch,
    ].join(' · ');
    return ReorderableDelayedDragStartListener(
      key: ValueKey(rule.id),
      index: index,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: ItemPositionProvider(
          position: ItemPosition.get(index, settings.rules.length),
          child: ListItem(
            leading: GlyphIcon(
              rule.apps.isNotEmpty
                  ? AppGlyphs.appsList
                  : rule.networks.any((m) => m.kind == NetworkMatchKind.mobile)
                  ? AppGlyphs.dataUsage
                  : AppGlyphs.wifi,
            ),
            title: Text(rule.name.isEmpty ? condition : rule.name),
            subtitle: Text(details),
            trailing: Switch(
              value: rule.enabled,
              onChanged: (value) =>
                  _update(ref, (s) => s.putRule(rule.copyWith(enabled: value))),
            ),
            onTap: () => _openRule(rule),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final settings = ref.watch(autoTunnelProvider);
    final profiles = ref.watch(profilesProvider);
    final permissionItems = _permissionItems(l, settings);
    Widget box(Widget child) => SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      sliver: SliverToBoxAdapter(child: child),
    );
    return CommonScaffold(
      title: l.autoTunnel,
      body: CustomScrollView(
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.symmetric(
              horizontal: 16,
            ).copyWith(top: context.contentTopPadding),
            sliver: SliverToBoxAdapter(
              child: generateSectionV3(
                items: [
                  ListItem.toggle(
                    leading: const GlyphIcon(AppGlyphs.vpn),
                    title: Text(l.autoTunnel),
                    value: settings.enabled,
                    onChanged: (value) =>
                        _update(ref, (state) => state.copyWith(enabled: value)),
                  ),
                ],
                footer: l.autoTunnelDesc,
              ),
            ),
          ),
          if (permissionItems.isNotEmpty)
            box(
              generateSectionV3(
                title: l.autoTunnelPermissions,
                items: permissionItems,
              ),
            ),
          box(
            generateSectionV3(
              title: l.autoTunnelCategories,
              items: [
                for (final category in settings.categories)
                  ListItem(
                    leading: const GlyphIcon(AppGlyphs.layers),
                    title: Text(category.name),
                    subtitle: category.killSwitch
                        ? Text(l.autoTunnelKillSwitch)
                        : null,
                    trailing: Switch(
                      value: category.enabled,
                      onChanged: (value) => _update(
                        ref,
                        (s) => s.putCategory(category.copyWith(enabled: value)),
                      ),
                    ),
                    onTap: () => _openCategory(category),
                  ),
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.add),
                  title: Text(l.autoTunnelAddCategory),
                  onTap: () => _openCategory(
                    RuleCategory(id: AutoTunnelRule.newId(), name: ''),
                  ),
                ),
              ],
              footer: l.autoTunnelCategoriesDesc,
            ),
          ),
          box(ListHeader(title: l.autoTunnelRules)),
          SliverReorderableList(
            itemCount: settings.rules.length,
            itemBuilder: (_, index) => _ruleRow(l, settings, profiles, index),
            proxyDecorator: (_, index, animation) => commonProxyDecorator(
              _ruleRow(l, settings, profiles, index),
              index,
              animation,
            ),
            onReorderItem: (oldIndex, newIndex) => _update(
              ref,
              (s) =>
                  s.copyWith(rules: s.rules.copyAndReorder(oldIndex, newIndex)),
            ),
          ),
          box(
            generateSectionV3(
              items: [
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.add),
                  title: Text(l.autoTunnelAddRule),
                  onTap: () =>
                      _openRule(AutoTunnelRule(id: AutoTunnelRule.newId())),
                ),
              ],
              footer: l.autoTunnelRulesDesc,
            ),
          ),
          if (system.isAndroid)
            box(
              generateSectionV3(
                items: [
                  ListItem(
                    leading: const GlyphIcon(AppGlyphs.openExternal),
                    title: Text(l.autoTunnelSystemKillSwitch),
                    onTap: () => _request('systemKillSwitch'),
                  ),
                ],
                footer: l.autoTunnelSystemKillSwitchDesc,
              ),
            ),
          const SliverToBoxAdapter(child: SizedBox(height: 32)),
        ],
      ),
    );
  }
}

Future<List<String>> _nearbyNetworks() async => system.isAndroid
    ? await _platform.wifiNetworks().catchError((_) => const <String>[])
    : [?await WifiSsidManager.instance.getSsid().catchError((_) => null)];

/// One category, edited as a draft and saved with the check.
class _CategoryEditor extends ConsumerStatefulWidget {
  const _CategoryEditor({required this.category});

  final RuleCategory category;

  @override
  ConsumerState<_CategoryEditor> createState() => _CategoryEditorState();
}

class _CategoryEditorState extends ConsumerState<_CategoryEditor> {
  late RuleCategory _category = widget.category;

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final category = _category;
    return CommonScaffold(
      title: l.autoTunnelCategory,
      actions: [
        IconButton(
          tooltip: l.delete,
          onPressed: () {
            unawaited(_update(ref, (s) => s.removeCategory(category.id)));
            Navigator.of(context).pop();
          },
          icon: const GlyphIcon(AppGlyphs.delete),
        ),
        IconButton(
          tooltip: l.save,
          onPressed: category.name.trim().isEmpty
              ? null
              : () {
                  unawaited(_update(ref, (s) => s.putCategory(category)));
                  Navigator.of(context).pop();
                },
          icon: const GlyphIcon(AppGlyphs.check),
        ),
      ],
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              ListItem.input(
                leading: const GlyphIcon(AppGlyphs.edit),
                title: Text(l.autoTunnelName),
                subtitle: category.name.isEmpty ? null : Text(category.name),
                dialogTitle: l.autoTunnelName,
                value: category.name,
                onChanged: (value) => setState(
                  () => _category = category.copyWith(name: value?.trim()),
                ),
              ),
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.lock),
                title: Text(l.autoTunnelKillSwitch),
                value: category.killSwitch,
                onChanged: (value) => setState(
                  () => _category = category.copyWith(killSwitch: value),
                ),
              ),
            ],
            footer: system.isWindows
                ? '${l.autoTunnelCategoryKillSwitchDesc} ${l.autoTunnelWindowsKillDesc}'
                : l.autoTunnelCategoryKillSwitchDesc,
          ),
        ],
      ),
    );
  }
}

/// What a rule does, as the tunnel picker returns it: a null [connect]
/// leaves the tunnel as it is.
typedef _Outcome = ({bool? connect, TunnelTarget? target});

String _outcomeText(
  AppLocalizations l,
  _Outcome outcome,
  List<Profile> profiles,
) => switch (outcome) {
  (connect: null, target: _) => l.autoTunnelKeep,
  (connect: false, target: _) => l.autoTunnelDisconnect,
  (connect: true, target: null) => l.autoTunnelChooseTunnel,
  (connect: true, target: final target?) => l.autoTunnelConnectTo(
    _targetText(profiles, target),
  ),
};

_Outcome _endOutcome(AutoTunnelRule rule) => switch (rule.onEnd) {
  EndAction.keep => (connect: null, target: null),
  EndAction.connect => (connect: true, target: rule.endTarget),
  EndAction.disconnect => (connect: false, target: null),
};

/// A row that shows a check when picked; tapping picks it.
class _ChoiceRow extends StatelessWidget {
  const _ChoiceRow({
    required this.title,
    required this.selected,
    required this.onTap,
    this.subtitle,
    this.leading,
  });

  final String title;
  final String? subtitle;
  final Widget? leading;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => ListItem(
    leading: leading,
    title: Text(title),
    subtitle: subtitle == null ? null : Text(subtitle!),
    trailing: selected
        ? GlyphIcon(AppGlyphs.check, color: context.colorScheme.primary)
        : null,
    onTap: onTap,
  );
}

/// Pops the tapped option.
class _ChoicePage<T> extends StatelessWidget {
  const _ChoicePage({
    required this.title,
    required this.options,
    required this.label,
    this.selected,
    this.footer,
  });

  final String title;
  final List<T> options;
  final String Function(T) label;
  final T? selected;
  final String? footer;

  @override
  Widget build(BuildContext context) => CommonScaffold(
    title: title,
    body: ListView(
      padding: const EdgeInsets.symmetric(
        horizontal: 16,
      ).copyWith(top: context.contentTopPadding, bottom: 32),
      children: [
        generateSectionV3(
          items: [
            for (final option in options)
              _ChoiceRow(
                title: label(option),
                selected: option == selected,
                onTap: () => Navigator.of(context).pop(option),
              ),
          ],
          footer: footer,
        ),
      ],
    ),
  );
}

Future<T?> _pick<T>(
  BuildContext context, {
  required String title,
  required List<T> options,
  required String Function(T) label,
  T? selected,
  String? footer,
}) => BaseNavigator.push<T>(
  context,
  _ChoicePage<T>(
    title: title,
    options: options,
    label: label,
    selected: selected,
    footer: footer,
  ),
);

/// One rule, edited as a draft and saved with the check.
class _RuleEditor extends ConsumerStatefulWidget {
  const _RuleEditor({required this.rule, required this.sims});

  final AutoTunnelRule rule;
  final List<SimCard> sims;

  @override
  ConsumerState<_RuleEditor> createState() => _RuleEditorState();
}

class _RuleEditorState extends ConsumerState<_RuleEditor> {
  late AutoTunnelRule _rule = widget.rule;

  void _edit(AutoTunnelRule Function(AutoTunnelRule) change) =>
      setState(() => _rule = change(_rule));

  Future<void> _addNetwork(AppLocalizations l) async {
    const named = '\u0000named';
    const anyWifi = '\u0000wifi';
    const anyMobile = '\u0000mobile';
    final sims = {for (final sim in widget.sims) '${sim.id}': sim.title};
    final picked = await _pick<String>(
      context,
      title: l.autoTunnelAddNetwork,
      options: [
        named,
        anyWifi,
        if (system.isAndroid) ...[anyMobile, ...sims.keys],
      ],
      label: (option) => switch (option) {
        named => l.autoTunnelWifi,
        anyWifi => l.autoTunnelAnyWifi,
        anyMobile => l.autoTunnelAnyMobile,
        _ => sims[option]!,
      },
    );
    if (picked == null || !mounted) return;
    final NetworkMatch match;
    switch (picked) {
      case anyWifi:
        match = const NetworkMatch.wifi();
      case anyMobile:
        match = const NetworkMatch.mobile();
      case named:
        final nearby = await _nearbyNetworks();
        if (!mounted) return;
        final ssid = await _choose(
          context,
          l.autoTunnelWifi,
          nearby,
          l.autoTunnelEnterName,
        );
        if (ssid == null) return;
        match = NetworkMatch.wifi(ssid);
      default:
        match = NetworkMatch.mobile(int.parse(picked));
    }
    if (_rule.networks.contains(match)) return;
    _edit((r) => r.copyWith(networks: [...r.networks, match]));
  }

  Future<void> _chooseApps() async {
    final picked = await pickApps(context, _rule.apps);
    if (picked == null) return;
    _edit((r) => r.copyWith(apps: picked));
  }

  Future<void> _chooseThen(AppLocalizations l) async {
    final picked = await BaseNavigator.push<_Outcome>(
      context,
      _TunnelPicker(
        title: l.autoTunnelThen,
        initial: (connect: _rule.connect, target: _rule.target),
        allowKeep: false,
      ),
    );
    if (picked == null) return;
    _edit(
      (r) => r.copyWith(
        connect: picked.connect ?? false,
        target: () => picked.target,
      ),
    );
  }

  Future<void> _chooseEnd(AppLocalizations l) async {
    final picked = await BaseNavigator.push<_Outcome>(
      context,
      _TunnelPicker(
        title: l.autoTunnelOnEnd,
        initial: _endOutcome(_rule),
        allowKeep: true,
      ),
    );
    if (picked == null) return;
    _edit(
      (r) => r.copyWith(
        onEnd: switch (picked.connect) {
          null => EndAction.keep,
          false => EndAction.disconnect,
          true => EndAction.connect,
        },
        endTarget: () => picked.target,
      ),
    );
  }

  Future<void> _chooseCategory(
    AppLocalizations l,
    List<RuleCategory> categories,
  ) async {
    final picked = await _pick<String>(
      context,
      title: l.autoTunnelCategory,
      options: ['', ...categories.map((c) => c.id)],
      label: (id) => id.isEmpty
          ? l.autoTunnelNoCategory
          : categories.firstWhere((c) => c.id == id).name,
      selected: _rule.categoryId ?? '',
    );
    if (picked == null) return;
    _edit((r) => r.copyWith(categoryId: () => picked.isEmpty ? null : picked));
  }

  void _save(AppLocalizations l) {
    if (_rule.connect && _rule.target == null) {
      unawaited(_chooseThen(l));
      return;
    }
    unawaited(_update(ref, (s) => s.putRule(_rule)));
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final profiles = ref.watch(profilesProvider);
    final categories = ref.watch(
      autoTunnelProvider.select((s) => s.categories),
    );
    final rule = _rule;
    final category = categories.firstWhereOrNull(
      (c) => c.id == rule.categoryId,
    );
    final fromCategory = category?.killSwitch ?? false;
    return CommonScaffold(
      title: l.autoTunnelRule,
      actions: [
        IconButton(
          tooltip: l.delete,
          onPressed: () {
            unawaited(_update(ref, (s) => s.removeRule(rule.id)));
            Navigator.of(context).pop();
          },
          icon: const GlyphIcon(AppGlyphs.delete),
        ),
        IconButton(
          tooltip: l.save,
          onPressed: () => _save(l),
          icon: const GlyphIcon(AppGlyphs.check),
        ),
      ],
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              ListItem.input(
                leading: const GlyphIcon(AppGlyphs.edit),
                title: Text(l.autoTunnelName),
                subtitle: Text(
                  rule.name.isEmpty
                      ? _conditionText(l, rule, widget.sims)
                      : rule.name,
                ),
                dialogTitle: l.autoTunnelName,
                value: rule.name,
                onChanged: (value) =>
                    _edit((r) => r.copyWith(name: value?.trim() ?? '')),
              ),
              if (categories.isNotEmpty)
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.layers),
                  title: Text(l.autoTunnelCategory),
                  subtitle: Text(category?.name ?? l.autoTunnelNoCategory),
                  trailing: const DisclosureIndicator(),
                  onTap: () => _chooseCategory(l, categories),
                ),
            ],
          ),
          generateSectionV3(
            title: l.autoTunnelIfNetwork,
            items: [
              for (final match in rule.networks)
                ListItem(
                  leading: GlyphIcon(
                    match.kind == NetworkMatchKind.wifi
                        ? AppGlyphs.wifi
                        : AppGlyphs.dataUsage,
                  ),
                  title: Text(_networkText(l, match, widget.sims)),
                  trailing: IconButton(
                    tooltip: l.delete,
                    icon: const GlyphIcon(AppGlyphs.close),
                    onPressed: () => _edit(
                      (r) => r.copyWith(
                        networks: [
                          for (final m in r.networks)
                            if (m != match) m,
                        ],
                      ),
                    ),
                  ),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelAddNetwork),
                onTap: () => _addNetwork(l),
              ),
            ],
          ),
          if (rule.networks.isNotEmpty && rule.apps.isNotEmpty)
            generateSectionV3(
              items: [
                _ChoiceRow(
                  title: l.autoTunnelMatchAll,
                  selected: rule.matchAll,
                  onTap: () => _edit((r) => r.copyWith(matchAll: true)),
                ),
                _ChoiceRow(
                  title: l.autoTunnelMatchAny,
                  selected: !rule.matchAll,
                  onTap: () => _edit((r) => r.copyWith(matchAll: false)),
                ),
              ],
            ),
          generateSectionV3(
            title: l.autoTunnelIfApps,
            items: [
              for (final app in rule.apps)
                ListItem(
                  leading: system.isAndroid
                      ? PackageIcon(packageName: app.app, size: 40)
                      : const GlyphIcon(AppGlyphs.appsList),
                  title: Text(app.label),
                  trailing: IconButton(
                    tooltip: l.delete,
                    icon: const GlyphIcon(AppGlyphs.close),
                    onPressed: () => _edit(
                      (r) => r.copyWith(
                        apps: [
                          for (final a in r.apps)
                            if (a != app) a,
                        ],
                      ),
                    ),
                  ),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelChooseApps),
                onTap: _chooseApps,
              ),
            ],
            footer: l.autoTunnelIfDesc,
          ),
          generateSectionV3(
            items: [
              ListItem(
                leading: const GlyphIcon(AppGlyphs.target),
                title: Text(l.autoTunnelThen),
                subtitle: Text(
                  _outcomeText(l, (
                    connect: rule.connect,
                    target: rule.target,
                  ), profiles),
                ),
                trailing: const DisclosureIndicator(),
                onTap: () => _chooseThen(l),
              ),
              if (rule.connect)
                ListItem.toggle(
                  leading: const GlyphIcon(AppGlyphs.lock),
                  title: Text(l.autoTunnelKillSwitch),
                  subtitle: Text(
                    fromCategory
                        ? l.autoTunnelKillSwitchFromCategory(category!.name)
                        : l.autoTunnelKillSwitchDesc,
                  ),
                  value: rule.killSwitch || fromCategory,
                  onChanged: fromCategory
                      ? null
                      : (value) => _edit((r) => r.copyWith(killSwitch: value)),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.undo),
                title: Text(l.autoTunnelOnEnd),
                subtitle: Text(_outcomeText(l, _endOutcome(rule), profiles)),
                trailing: const DisclosureIndicator(),
                onTap: () => _chooseEnd(l),
              ),
            ],
            footer: l.autoTunnelOnEndDesc,
          ),
        ],
      ),
    );
  }
}

/// The app or program picker auto-tunnel rules use; pops the chosen apps.
Future<List<RuleApp>?> pickApps(BuildContext context, List<RuleApp> selected) =>
    BaseNavigator.push<List<RuleApp>>(context, _AppPicker(selected: selected));

enum _AppTab { user, system, otherProfiles, programs, running }

final _appTabs = Platform.isWindows
    ? const [_AppTab.programs, _AppTab.running]
    : const [_AppTab.user, _AppTab.system, _AppTab.otherProfiles];

/// Access control's list and tabs, chosen apps first, plus the apps of the
/// work profile and Secure Folder; on Windows, the Start menu's programs and
/// the running ones. Pops the chosen apps.
class _AppPicker extends ConsumerStatefulWidget {
  const _AppPicker({required this.selected});

  final List<RuleApp> selected;

  @override
  ConsumerState<_AppPicker> createState() => _AppPickerState();
}

class _AppPickerState extends ConsumerState<_AppPicker>
    with SingleTickerProviderStateMixin {
  late final _tabs = TabController(length: _appTabs.length, vsync: this)
    ..addListener(() => setState(() {}));
  late final Map<String, RuleApp> _chosen = {
    for (final app in widget.selected) app.app: app,
  };
  late final Set<String> _pinned = _chosen.keys.toSet();
  List<Package> _others = const [];
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

  static Package _package(String app, String label) => Package(
    packageName: app,
    label: label,
    system: false,
    internet: true,
    lastUpdateTime: 0,
  );

  /// Samsung gives Secure Folder users 150 and up and Dual Messenger 95.
  static String _profileName(AppLocalizations l, int? user) => switch (user) {
    95 => l.autoTunnelDualMessenger,
    final id? when id >= 150 && id <= 160 => l.autoTunnelSecureFolder,
    _ => l.autoTunnelWorkProfile,
  };

  Future<void> _load() async {
    final l = currentAppLocalizations;
    if (Platform.isWindows) {
      final (programs, running) = await (
        installedPrograms(),
        runningProgramPaths(),
      ).wait;
      if (!mounted) return;
      final listed = {for (final (_, path) in programs) path.toLowerCase()};
      setState(() {
        _others = [
          for (final (name, path) in programs) _package(path, name),
          for (final app in _chosen.values)
            if (!listed.contains(app.app.toLowerCase()))
              _package(app.app, app.label),
        ];
        _running = [
          for (final path in running) _package(path, _fileName(path)),
        ];
      });
      return;
    }
    final profileApps = await _platform.profileApps().catchError(
      (_) => const <String>[],
    );
    final seen = await _platform.seenApps().catchError((_) => const <String>[]);
    if (!mounted) return;
    final labels = <String, String>{};
    final profiles = <String, Set<String>>{};
    for (final entry in [...profileApps, ...seen]) {
      final parts = entry.split('\t');
      final app = parts.first;
      labels.putIfAbsent(app, () => parts.length > 1 ? parts[1] : app);
      if (parts.length > 2) {
        (profiles[app] ??= {}).add(_profileName(l, int.tryParse(parts[2])));
      }
    }
    setState(
      () => _others = [
        for (final MapEntry(key: app, value: label) in labels.entries)
          _package(app, [label, ...?profiles[app]?.sorted()].join(' · ')),
      ],
    );
  }

  static String _fileName(String path) => path.split(RegExp(r'[\\/]')).last;

  /// Windows browses for a program; Android takes a typed package name.
  Future<void> _addApp(AppLocalizations l) async {
    final app = Platform.isWindows
        ? (await FilePicker.pickFile(
            type: FileType.custom,
            allowedExtensions: const ['exe'],
          ))?.path
        : await _typed(
            context,
            l.autoTunnelChooseApps,
            l.autoTunnelPackageName,
          );
    if (app == null || !mounted) return;
    setState(() {
      _chosen[app] = RuleApp(app: app, label: _fileName(app));
      _pinned.add(app);
      if (!_others.any((p) => p.packageName == app)) {
        _others = [..._others, _package(app, _chosen[app]!.label)];
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final installed = Platform.isWindows
        ? const <Package>[]
        : ref.watch(packagesProvider);
    final tab = _appTabs[_tabs.index];
    final query = SearchQuery(_query);
    final packages =
        [
              ...switch (tab) {
                _AppTab.user => installed.where((p) => !p.system),
                _AppTab.system => installed.where((p) => p.system),
                _AppTab.otherProfiles || _AppTab.programs => _others,
                _AppTab.running => _running,
              },
            ]
            .whereMatches(query, (p) => [p.label, p.packageName])
            .sortedBy((p) => p.label.toLowerCase())
            .sortedBy<num>((p) => _pinned.contains(p.packageName) ? 0 : 1);
    return CommonScaffold(
      title: l.autoTunnelChooseApps,
      searchState: AppBarSearchState(
        onSearch: (value) => setState(() => _query = value),
      ),
      actions: [
        IconButton(
          tooltip: Platform.isWindows
              ? l.autoTunnelBrowse
              : l.autoTunnelEnterName,
          onPressed: () => _addApp(l),
          icon: GlyphIcon(
            Platform.isWindows ? AppGlyphs.importFile : AppGlyphs.edit,
          ),
        ),
        IconButton(
          tooltip: l.save,
          onPressed: () => Navigator.of(context).pop(_chosen.values.toList()),
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
                for (final tab in _appTabs)
                  Tab(
                    text: switch (tab) {
                      _AppTab.user => l.accessTabUser,
                      _AppTab.system => l.systemApp,
                      _AppTab.otherProfiles => l.autoTunnelOtherProfiles,
                      _AppTab.programs => l.autoTunnelPrograms,
                      _AppTab.running => l.autoTunnelRunning,
                    },
                  ),
              ],
            ),
            if (tab == _AppTab.otherProfiles)
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 0),
                child: Text(
                  l.autoTunnelOtherProfilesDesc,
                  style: context.textTheme.bodySmall?.copyWith(
                    color: context.colorScheme.onSurfaceVariant,
                  ),
                ),
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
                      value: _chosen.containsKey(package.packageName),
                      onChanged: (value) => setState(() {
                        if (value == true) {
                          _chosen[package.packageName] = RuleApp(
                            app: package.packageName,
                            label: package.label,
                          );
                        } else {
                          _chosen.remove(package.packageName);
                        }
                      }),
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

Future<String?> _typed(BuildContext context, String title, String label) async {
  final l = context.appLocalizations;
  final typed = await dialogs.showCommonDialog<String>(
    child: InputDialog(
      title: title,
      value: '',
      labelText: label,
      validator: (value) =>
          value == null || value.trim().isEmpty ? l.emptyTip(label) : null,
    ),
  );
  final value = typed?.trim();
  return value == null || value.isEmpty ? null : value;
}

/// One of [choices], or a typed name.
Future<String?> _choose(
  BuildContext context,
  String title,
  List<String> choices,
  String manualLabel,
) async {
  var picked = _manual;
  if (choices.isNotEmpty) {
    final selected = await _pick<String>(
      context,
      title: title,
      options: [...choices, _manual],
      label: (option) => option == _manual ? manualLabel : option,
    );
    if (selected == null) return null;
    picked = selected;
  }
  if (picked != _manual) return picked;
  if (!context.mounted) return null;
  return _typed(context, title, manualLabel);
}

typedef _Groups = Map<String, List<String>>;

Future<_Groups> _groupsOf(int profileId) async {
  try {
    final path = await appPath.getProfilePath(profileId.toString());
    final config = loadPlainYaml(await File(path).readAsString());
    if (config is! Map) return const {};
    return {
      for (final group in (config['proxy-groups'] as List?) ?? const [])
        if (group is Map && group['name'] is String)
          group['name'] as String: [
            for (final proxy in (group['proxies'] as List?) ?? const [])
              if (proxy is String) proxy,
          ],
    };
  } on Object catch (error) {
    commonPrint.log('Auto-tunnel: unreadable profile $profileId ($error)');
    return const {};
  }
}

/// Disconnect, leave the tunnel as it is when [allowKeep], or a profile with
/// an optional server. Pops the [_Outcome].
class _TunnelPicker extends ConsumerStatefulWidget {
  const _TunnelPicker({
    required this.title,
    required this.initial,
    required this.allowKeep,
  });

  final String title;
  final _Outcome initial;
  final bool allowKeep;

  @override
  ConsumerState<_TunnelPicker> createState() => _TunnelPickerState();
}

class _TunnelPickerState extends ConsumerState<_TunnelPicker> {
  late TunnelTarget? _target = widget.initial.connect == true
      ? widget.initial.target
      : null;
  _Groups _groups = const {};

  @override
  void initState() {
    super.initState();
    unawaited(_loadGroups());
  }

  Future<void> _loadGroups() async {
    final id = _target?.profileId;
    final groups = id == null
        ? const <String, List<String>>{}
        : await _groupsOf(id);
    if (mounted) setState(() => _groups = groups);
  }

  void _pickProfile(int profileId) {
    if (_target?.profileId == profileId) return;
    setState(() => _target = TunnelTarget(profileId: profileId));
    unawaited(_loadGroups());
  }

  Future<void> _pickServer(AppLocalizations l, String group) async {
    final target = _target;
    final servers = _groups[group] ?? const [];
    if (target == null || servers.isEmpty) return;
    final proxy = await _pick<String>(
      context,
      title: group,
      options: servers,
      label: (p) => p,
      selected: target.group == group ? target.proxy : null,
    );
    if (proxy == null || !mounted) return;
    setState(
      () => _target = TunnelTarget(
        profileId: target.profileId,
        group: group,
        proxy: proxy,
      ),
    );
  }

  void _done(_Outcome outcome) => Navigator.of(context).pop(outcome);

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final profiles = ref.watch(profilesProvider);
    final target = _target;
    final initial = widget.initial.connect;
    return CommonScaffold(
      title: widget.title,
      actions: [
        IconButton(
          tooltip: l.save,
          onPressed: target == null
              ? null
              : () => _done((connect: true, target: target)),
          icon: const GlyphIcon(AppGlyphs.check),
        ),
      ],
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              _ChoiceRow(
                leading: const GlyphIcon(AppGlyphs.close),
                title: l.autoTunnelDisconnect,
                selected: target == null && initial == false,
                onTap: () => _done((connect: false, target: null)),
              ),
              if (widget.allowKeep)
                _ChoiceRow(
                  leading: const GlyphIcon(AppGlyphs.pin),
                  title: l.autoTunnelKeep,
                  selected: target == null && initial == null,
                  onTap: () => _done((connect: null, target: null)),
                ),
            ],
          ),
          generateSectionV3(
            title: l.autoTunnelConnectToTitle,
            items: [
              for (final profile in profiles)
                _ChoiceRow(
                  leading: const GlyphIcon(AppGlyphs.vpn),
                  title: profile.realLabel,
                  selected: target?.profileId == profile.id,
                  onTap: () => _pickProfile(profile.id),
                ),
            ],
          ),
          if (target != null && _groups.isNotEmpty)
            generateSectionV3(
              title: l.autoTunnelServer,
              items: [
                _ChoiceRow(
                  title: l.autoTunnelKeepServer,
                  selected: target.group == null,
                  onTap: () => setState(
                    () => _target = TunnelTarget(profileId: target.profileId),
                  ),
                ),
                for (final group in _groups.keys)
                  if (_groups[group]!.isNotEmpty)
                    _ChoiceRow(
                      title: group,
                      subtitle: target.group == group ? target.proxy : null,
                      selected: target.group == group,
                      onTap: () => _pickServer(l, group),
                    ),
              ],
              footer: l.autoTunnelServerDesc,
            ),
        ],
      ),
    );
  }
}
