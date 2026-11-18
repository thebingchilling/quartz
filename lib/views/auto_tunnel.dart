import 'dart:async';
import 'dart:io';

import 'package:collection/collection.dart';
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
) => !rule.connect
    ? l.autoTunnelDisconnect
    : '${l.autoTunnelConnect}: ${rule.target == null ? l.autoTunnelChooseTunnel : _targetText(profiles, rule.target!)}';

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
    const anyWifi = '\u0000wifi';
    const anyMobile = '\u0000mobile';
    const namedWifi = '\u0000named';
    final picked = await dialogs.showCommonDialog<(String, String)>(
      child: OptionsDialog<(String, String)>(
        title: l.autoTunnelAddNetwork,
        options: [
          (namedWifi, l.autoTunnelWifi),
          (anyWifi, l.autoTunnelAnyWifi),
          (anyMobile, l.autoTunnelAnyMobile),
          for (final sim in widget.sims) ('${sim.id}', sim.title),
        ],
        textBuilder: (option) => option.$2,
        value: (namedWifi, l.autoTunnelWifi),
      ),
    );
    if (picked == null || !mounted) return;
    final NetworkMatch match;
    switch (picked.$1) {
      case anyWifi:
        match = const NetworkMatch.wifi();
      case anyMobile:
        match = const NetworkMatch.mobile();
      case namedWifi:
        final nearby = await _nearbyNetworks();
        if (!mounted) return;
        final ssid = await _choose(context, l.autoTunnelAddNetwork, [
          for (final ssid in nearby) (ssid, ssid),
        ], l.autoTunnelEnterName);
        if (ssid == null) return;
        match = NetworkMatch.wifi(ssid);
      default:
        match = NetworkMatch.mobile(int.parse(picked.$1));
    }
    if (_rule.networks.contains(match)) return;
    _edit((r) => r.copyWith(networks: [...r.networks, match]));
  }

  Future<void> _chooseApps() async {
    final picked = await BaseNavigator.push<List<RuleApp>>(
      context,
      _AppPicker(selected: _rule.apps),
    );
    if (picked == null) return;
    _edit((r) => r.copyWith(apps: picked));
  }

  Future<void> _chooseTunnel() async {
    final picked = await BaseNavigator.push<TunnelTarget>(
      context,
      _TunnelPicker(initial: _rule.target),
    );
    if (picked == null) return;
    _edit((r) => r.copyWith(target: () => picked));
  }

  void _save() {
    if (_rule.connect && _rule.target == null) {
      unawaited(_chooseTunnel());
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
          onPressed: _save,
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
                ListItem<String>.options(
                  leading: const GlyphIcon(AppGlyphs.layers),
                  title: Text(l.autoTunnelCategory),
                  subtitle: Text(category?.name ?? l.autoTunnelNoCategory),
                  dialogTitle: l.autoTunnelCategory,
                  options: ['', ...categories.map((c) => c.id)],
                  value: category?.id ?? '',
                  textBuilder: (id) => id.isEmpty
                      ? l.autoTunnelNoCategory
                      : categories.firstWhere((c) => c.id == id).name,
                  onChanged: (id) => _edit(
                    (r) => r.copyWith(
                      categoryId: () => id == null || id.isEmpty ? null : id,
                    ),
                  ),
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
            footer: l.autoTunnelWhenOnDesc,
          ),
          if (rule.networks.isNotEmpty && rule.apps.isNotEmpty)
            RadioGroup<bool>(
              groupValue: rule.matchAll,
              onChanged: (value) {
                if (value != null) _edit((r) => r.copyWith(matchAll: value));
              },
              child: generateSectionV3(
                items: [
                  ListItem<bool>.radio(
                    title: Text(l.autoTunnelMatchAll),
                    value: true,
                  ),
                  ListItem<bool>.radio(
                    title: Text(l.autoTunnelMatchAny),
                    value: false,
                  ),
                ],
              ),
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
            footer: l.autoTunnelWhileOpenDesc,
          ),
          RadioGroup<bool>(
            groupValue: rule.connect,
            onChanged: (value) {
              if (value != null) _edit((r) => r.copyWith(connect: value));
            },
            child: generateSectionV3(
              title: l.autoTunnelThen,
              items: [
                ListItem<bool>.radio(
                  title: Text(l.autoTunnelConnect),
                  value: true,
                ),
                if (rule.connect)
                  ListItem(
                    leading: const GlyphIcon(AppGlyphs.target),
                    title: Text(l.autoTunnelTarget),
                    subtitle: Text(
                      rule.target == null
                          ? l.autoTunnelChooseTunnel
                          : _targetText(profiles, rule.target!),
                    ),
                    trailing: const DisclosureIndicator(),
                    onTap: _chooseTunnel,
                  ),
                ListItem<bool>.radio(
                  title: Text(l.autoTunnelDisconnect),
                  value: false,
                ),
              ],
              footer: l.autoTunnelThenDesc,
            ),
          ),
          if (rule.connect)
            generateSectionV3(
              items: [
                ListItem.toggle(
                  leading: const GlyphIcon(AppGlyphs.lock),
                  title: Text(l.autoTunnelKillSwitch),
                  subtitle: category?.killSwitch ?? false
                      ? Text(l.autoTunnelKillSwitchFromCategory(category!.name))
                      : null,
                  value: rule.killSwitch || (category?.killSwitch ?? false),
                  onChanged: category?.killSwitch ?? false
                      ? null
                      : (value) => _edit((r) => r.copyWith(killSwitch: value)),
                ),
              ],
              footer: system.isWindows
                  ? '${l.autoTunnelKillSwitchDesc} ${l.autoTunnelWindowsKillDesc}'
                  : l.autoTunnelKillSwitchDesc,
            ),
        ],
      ),
    );
  }
}

enum _AppTab { all, user, system, otherProfiles, selected }

/// The same list and tabs as access control, plus the apps of Secure Folder
/// and the work profile that app detection has seen. Pops the chosen apps.
class _AppPicker extends ConsumerStatefulWidget {
  const _AppPicker({required this.selected});

  final List<RuleApp> selected;

  @override
  ConsumerState<_AppPicker> createState() => _AppPickerState();
}

class _AppPickerState extends ConsumerState<_AppPicker>
    with SingleTickerProviderStateMixin {
  late final _tabs = TabController(length: _AppTab.values.length, vsync: this)
    ..addListener(() => setState(() {}));
  late final Map<String, RuleApp> _chosen = {
    for (final app in widget.selected) app.app: app,
  };
  List<Package> _others = const [];
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

  Future<void> _load() async {
    if (Platform.isWindows) {
      final running = await runningProgramPaths();
      if (!mounted) return;
      setState(
        () => _others = [
          for (final path in running)
            Package(
              packageName: path,
              label: path.split(RegExp(r'[\\/]')).last,
              system: false,
              internet: true,
              lastUpdateTime: 0,
            ),
        ],
      );
      return;
    }
    final installed = await ref
        .read(systemActionProvider.notifier)
        .getPackages()
        .catchError((_) => const <Package>[]);
    final seen = await _platform.seenApps().catchError((_) => const <String>[]);
    final known = {for (final package in installed) package.packageName};
    if (!mounted) return;
    setState(
      () => _others = [
        for (final entry in seen)
          if (!known.contains(entry.split('\t').first))
            Package(
              packageName: entry.split('\t').first,
              label: entry.split('\t').last,
              system: false,
              internet: true,
              lastUpdateTime: 0,
            ),
      ],
    );
  }

  Future<void> _typeApp(AppLocalizations l) async {
    final label = Platform.isWindows
        ? l.autoTunnelExeName
        : l.autoTunnelPackageName;
    final app = await _typed(context, l.autoTunnelChooseApps, label);
    if (app == null) return;
    setState(
      () => _chosen[app] = RuleApp(
        app: app,
        label: app.split(RegExp(r'[\\/]')).last,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final installed = Platform.isWindows
        ? const <Package>[]
        : ref.watch(packagesProvider);
    final tab = _AppTab.values[_tabs.index];
    final query = SearchQuery(_query);
    final packages =
        [
              ...switch (tab) {
                _AppTab.all => [...installed, ..._others],
                _AppTab.user => installed.where((p) => !p.system),
                _AppTab.system => installed.where((p) => p.system),
                _AppTab.otherProfiles => _others,
                _AppTab.selected => [
                  ...installed,
                  ..._others,
                ].where((p) => _chosen.containsKey(p.packageName)),
              },
            ]
            .whereMatches(query, (p) => [p.label, p.packageName])
            .sortedBy((p) => p.label.toLowerCase());
    return CommonScaffold(
      title: l.autoTunnelChooseApps,
      searchState: AppBarSearchState(
        onSearch: (value) => setState(() => _query = value),
      ),
      actions: [
        IconButton(
          tooltip: l.autoTunnelEnterName,
          onPressed: () => _typeApp(l),
          icon: const GlyphIcon(AppGlyphs.edit),
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
            if (!Platform.isWindows)
              TabBar(
                controller: _tabs,
                isScrollable: true,
                tabAlignment: TabAlignment.start,
                dividerColor: Colors.transparent,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                tabs: [
                  for (final tab in _AppTab.values)
                    Tab(
                      text: switch (tab) {
                        _AppTab.all => l.accessTabAll,
                        _AppTab.user => l.accessTabUser,
                        _AppTab.system => l.systemApp,
                        _AppTab.otherProfiles => l.autoTunnelOtherProfiles,
                        _AppTab.selected => l.selected,
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

Future<String?> _choose(
  BuildContext context,
  String title,
  List<(String, String)> choices,
  String manualLabel,
) async {
  var picked = _manual;
  if (choices.isNotEmpty) {
    final selected = await dialogs.showCommonDialog<(String, String)>(
      child: OptionsDialog<(String, String)>(
        title: title,
        options: [...choices, (_manual, manualLabel)],
        textBuilder: (option) => option.$2,
        value: (_manual, manualLabel),
      ),
    );
    if (selected == null) return null;
    picked = selected.$1;
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

/// Pops the chosen tunnel.
class _TunnelPicker extends ConsumerStatefulWidget {
  const _TunnelPicker({required this.initial});

  final TunnelTarget? initial;

  @override
  ConsumerState<_TunnelPicker> createState() => _TunnelPickerState();
}

class _TunnelPickerState extends ConsumerState<_TunnelPicker> {
  late TunnelTarget? _target = widget.initial;
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

  void _pick(int profileId) {
    setState(() => _target = TunnelTarget(profileId: profileId));
    unawaited(_loadGroups());
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final profiles = ref.watch(profilesProvider);
    final target = _target;
    final group = target?.group;
    final groupProxies = group == null
        ? const <String>[]
        : _groups[group] ?? [];
    return CommonScaffold(
      title: l.autoTunnelTarget,
      actions: [
        IconButton(
          tooltip: l.save,
          onPressed: target == null
              ? null
              : () => Navigator.of(context).pop(target),
          icon: const GlyphIcon(AppGlyphs.check),
        ),
      ],
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          RadioGroup<int>(
            groupValue: target?.profileId,
            onChanged: (choice) {
              if (choice != null) _pick(choice);
            },
            child: generateSectionV3(
              items: [
                for (final profile in profiles)
                  ListItem<int>.radio(
                    title: Text(profile.realLabel),
                    value: profile.id,
                  ),
              ],
            ),
          ),
          if (target != null && _groups.isNotEmpty)
            generateSectionV3(
              title: l.autoTunnelServer,
              items: [
                ListItem<String>.options(
                  title: Text(l.autoTunnelGroup),
                  subtitle: Text(group ?? l.autoTunnelKeepServer),
                  dialogTitle: l.autoTunnelGroup,
                  options: ['', ..._groups.keys],
                  value: group ?? '',
                  textBuilder: (g) => g.isEmpty ? l.autoTunnelKeepServer : g,
                  onChanged: (g) => setState(
                    () => _target = TunnelTarget(
                      profileId: target.profileId,
                      group: g == null || g.isEmpty ? null : g,
                    ),
                  ),
                ),
                if (group != null && groupProxies.isNotEmpty)
                  ListItem<String>.options(
                    title: Text(l.autoTunnelServer),
                    subtitle: Text(target.proxy ?? l.autoTunnelKeepServer),
                    dialogTitle: l.autoTunnelServer,
                    options: groupProxies,
                    value: target.proxy ?? groupProxies.first,
                    textBuilder: (p) => p,
                    onChanged: (p) => setState(
                      () => _target = TunnelTarget(
                        profileId: target.profileId,
                        group: group,
                        proxy: p,
                      ),
                    ),
                  ),
              ],
              footer: l.autoTunnelServerDesc,
            ),
        ],
      ),
    );
  }
}
