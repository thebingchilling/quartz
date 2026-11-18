import 'dart:async';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/desktop/helper_client.dart';
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
const _fallback = -1;

Future<void> _update(
  WidgetRef ref,
  AutoTunnelSettings Function(AutoTunnelSettings) change,
) async {
  final before = ref.read(autoTunnelProvider).killSwitch;
  await ref.read(autoTunnelProvider.notifier).update(change);
  final after = ref.read(autoTunnelProvider).killSwitch;
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

/// [fallback] names what a null target connects.
String _tunnelText(
  List<Profile> profiles,
  TunnelTarget? target,
  String fallback,
) => target == null ? fallback : _targetText(profiles, target);

Future<(TunnelTarget?,)?> _pickTunnel(
  BuildContext context, {
  required TunnelTarget? initial,
  required String fallback,
}) => BaseNavigator.push<(TunnelTarget?,)>(
  context,
  _TunnelPicker(initial: initial, fallback: fallback),
);

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

String _ruleTitle(AppLocalizations l, AutoTunnelRule rule, List<SimCard> sims) {
  final networks = rule.networks.isEmpty
      ? l.autoTunnelAnyNetwork
      : rule.networks.map((m) => _networkText(l, m, sims)).join(', ');
  final apps = rule.apps.map((app) => app.label).join(', ');
  return apps.isEmpty ? networks : '$apps · $networks';
}

String _ruleAction(
  AppLocalizations l,
  AutoTunnelRule rule,
  List<Profile> profiles,
) => rule.connect
    ? '${l.autoTunnelConnect}: ${_tunnelText(profiles, rule.target, l.autoTunnelDefaultTunnel)}'
    : l.autoTunnelDisconnect;

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
    final rules = s.rules.where((rule) => rule.enabled);
    final namesWifi = rules.any(
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

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final settings = ref.watch(autoTunnelProvider);
    final profiles = ref.watch(profilesProvider);
    final permissionItems = _permissionItems(l, settings);
    return CommonScaffold(
      title: l.autoTunnel,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
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
          if (permissionItems.isNotEmpty)
            generateSectionV3(
              title: l.autoTunnelPermissions,
              items: permissionItems,
            ),
          generateSectionV3(
            title: l.autoTunnelRules,
            items: [
              for (final rule in settings.rules)
                ListItem(
                  leading: GlyphIcon(
                    rule.apps.isNotEmpty
                        ? AppGlyphs.appsList
                        : rule.networks.any(
                            (m) => m.kind == NetworkMatchKind.mobile,
                          )
                        ? AppGlyphs.dataUsage
                        : AppGlyphs.wifi,
                  ),
                  title: Text(_ruleTitle(l, rule, _sims)),
                  subtitle: Text(_ruleAction(l, rule, profiles)),
                  trailing: Switch(
                    value: rule.enabled,
                    onChanged: (value) => _update(
                      ref,
                      (s) => s.putRule(rule.copyWith(enabled: value)),
                    ),
                  ),
                  onTap: () => _openRule(rule),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelAddRule),
                onTap: () =>
                    _openRule(AutoTunnelRule(id: AutoTunnelRule.newId())),
              ),
            ],
            footer: l.autoTunnelRulesDesc,
          ),
          generateSectionV3(
            title: l.autoTunnelAdvanced,
            items: [
              ListItem(
                leading: const GlyphIcon(AppGlyphs.target),
                title: Text(l.autoTunnelDefaultTunnel),
                subtitle: Text(
                  _tunnelText(
                    profiles,
                    settings.defaultTunnel,
                    l.autoTunnelCurrentProfile,
                  ),
                ),
                trailing: const GlyphIcon(AppGlyphs.chevronForward),
                onTap: () async {
                  final picked = await _pickTunnel(
                    context,
                    initial: settings.defaultTunnel,
                    fallback: l.autoTunnelCurrentProfile,
                  );
                  if (picked == null) return;
                  await _update(
                    ref,
                    (s) => s.copyWith(defaultTunnel: () => picked.$1),
                  );
                },
              ),
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.close),
                title: Text(l.autoTunnelOtherwise),
                subtitle: Text(l.autoTunnelOtherwiseDesc),
                value: settings.disconnectOtherwise,
                onChanged: (value) =>
                    _update(ref, (s) => s.copyWith(disconnectOtherwise: value)),
              ),
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.networkCheck),
                title: Text(l.autoTunnelStopNoNetwork),
                value: settings.stopOnNoNetwork,
                onChanged: (value) =>
                    _update(ref, (s) => s.copyWith(stopOnNoNetwork: value)),
              ),
              ListItem<int>.options(
                leading: const GlyphIcon(AppGlyphs.history),
                title: Text(l.autoTunnelDebounce),
                subtitle: Text(l.autoTunnelSeconds(settings.debounceSeconds)),
                dialogTitle: l.autoTunnelDebounce,
                options: AutoTunnelSettings.debounceChoices,
                value: settings.debounceSeconds,
                textBuilder: l.autoTunnelSeconds,
                onChanged: (value) {
                  if (value == null) return;
                  _update(ref, (s) => s.copyWith(debounceSeconds: value));
                },
              ),
            ],
            footer: l.autoTunnelDebounceDesc,
          ),
          generateSectionV3(
            items: [
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.lock),
                title: Text(l.autoTunnelKillSwitch),
                value: settings.killSwitch,
                onChanged: (value) =>
                    _update(ref, (s) => s.copyWith(killSwitch: value)),
              ),
            ],
            footer: system.isWindows
                ? '${l.autoTunnelKillSwitchDesc} ${l.autoTunnelWindowsKillDesc}'
                : l.autoTunnelKillSwitchDesc,
          ),
          if (system.isAndroid)
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
        ],
      ),
    );
  }
}

Future<List<String>> _nearbyNetworks() async => system.isAndroid
    ? await _platform.wifiNetworks().catchError((_) => const <String>[])
    : [?await WifiSsidManager.instance.getSsid().catchError((_) => null)];

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
          (anyWifi, l.autoTunnelAnyWifi),
          (namedWifi, l.autoTunnelWifi),
          (anyMobile, l.autoTunnelAnyMobile),
          for (final sim in widget.sims) ('${sim.id}', sim.title),
        ],
        textBuilder: (option) => option.$2,
        value: (anyWifi, l.autoTunnelAnyWifi),
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

  void _save() {
    unawaited(_update(ref, (s) => s.putRule(_rule)));
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final profiles = ref.watch(profilesProvider);
    final rule = _rule;
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
            title: l.autoTunnelWhenOn,
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
          generateSectionV3(
            title: l.autoTunnelWhileOpen,
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
                      _tunnelText(
                        profiles,
                        rule.target,
                        l.autoTunnelDefaultTunnel,
                      ),
                    ),
                    trailing: const GlyphIcon(AppGlyphs.chevronForward),
                    onTap: () async {
                      final picked = await _pickTunnel(
                        context,
                        initial: rule.target,
                        fallback: l.autoTunnelDefaultTunnel,
                      );
                      if (picked == null) return;
                      _edit((r) => r.copyWith(target: () => picked.$1));
                    },
                  ),
                ListItem<bool>.radio(
                  title: Text(l.autoTunnelDisconnect),
                  value: false,
                ),
              ],
            ),
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

/// Pops `(target,)`, where a null target is the [fallback] choice.
class _TunnelPicker extends ConsumerStatefulWidget {
  const _TunnelPicker({required this.initial, required this.fallback});

  final TunnelTarget? initial;
  final String fallback;

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

  void _pick(int choice) {
    setState(
      () => _target = choice == _fallback
          ? null
          : TunnelTarget(profileId: choice),
    );
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
          onPressed: () => Navigator.of(context).pop((_target,)),
          icon: const GlyphIcon(AppGlyphs.check),
        ),
      ],
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          RadioGroup<int>(
            groupValue: target?.profileId ?? _fallback,
            onChanged: (choice) {
              if (choice != null) _pick(choice);
            },
            child: generateSectionV3(
              items: [
                ListItem<int>.radio(
                  title: Text(widget.fallback),
                  value: _fallback,
                ),
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
