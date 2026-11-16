import 'dart:async';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:fl_clash/features/auto_tunnel/windows_apps.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

const _platform = AutoTunnelNotifier.platform;
const _manual = '\u0000manual';

void _update(
  WidgetRef ref,
  AutoTunnelSettings Function(AutoTunnelSettings) change,
) => unawaited(ref.read(autoTunnelProvider.notifier).update(change));

String _kindText(AppLocalizations l, TriggerKind kind) => switch (kind) {
  TriggerKind.wifi => l.autoTunnelWifi,
  TriggerKind.mobile => l.autoTunnelMobile,
  TriggerKind.ethernet => l.autoTunnelEthernet,
  TriggerKind.app => l.autoTunnelApp,
};

Glyph _kindGlyph(TriggerKind kind) => switch (kind) {
  TriggerKind.wifi => AppGlyphs.wifi,
  TriggerKind.mobile => AppGlyphs.dataUsage,
  TriggerKind.ethernet => AppGlyphs.devices,
  TriggerKind.app => AppGlyphs.appsList,
};

String _targetText(AppLocalizations l, List<Profile> profiles, TunnelTarget t) {
  if (t.disconnects) return l.autoTunnelDisconnect;
  final profile = profiles.firstWhereOrNull((p) => p.id == t.profileId);
  final name = profile?.realLabel ?? '?';
  return t.proxy == null ? name : '$name · ${t.proxy}';
}

String _triggerText(
  AppLocalizations l,
  AutoTunnelRule rule,
  List<SimCard> sims,
) {
  final kind = _kindText(l, rule.kind);
  final names = switch (rule.kind) {
    TriggerKind.wifi => rule.ssids,
    TriggerKind.mobile => [
      for (final id in rule.simIds)
        sims.firstWhereOrNull((sim) => sim.id == id)?.name ?? 'SIM',
    ],
    TriggerKind.ethernet => const <String>[],
    TriggerKind.app => rule.apps,
  };
  if (rule.kind == TriggerKind.ethernet) return kind;
  return names.isEmpty
      ? '$kind · ${l.autoTunnelAny}'
      : '$kind · ${names.join(', ')}';
}

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
    final hasApps = s.rules.any((rule) => rule.kind == TriggerKind.app);
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
      if (!permissions.location)
        item(l.autoTunnelLocation, AppGlyphs.locate, 'location')
      else if (!permissions.backgroundLocation)
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

  Future<void> _edit(AutoTunnelRule? rule) async {
    final saved = await BaseNavigator.push<AutoTunnelRule?>(
      context,
      _RuleEditor(
        rule:
            rule ??
            AutoTunnelRule(
              id: AutoTunnelNotifier.newId(),
              kind: TriggerKind.wifi,
            ),
        sims: _sims,
        isNew: rule == null,
      ),
    );
    if (saved == null) return;
    _update(ref, (state) {
      final rules = [...state.rules];
      final index = rules.indexWhere((r) => r.id == saved.id);
      if (index == -1) {
        rules.add(saved);
      } else {
        rules[index] = saved;
      }
      return state.copyWith(rules: rules);
    });
  }

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
              if (settings.rules.isEmpty)
                ListItem(title: Text(l.autoTunnelNoRules)),
              for (final rule in settings.rules)
                ListItem(
                  leading: GlyphIcon(_kindGlyph(rule.kind)),
                  title: Text(_triggerText(l, rule, _sims)),
                  subtitle: Text(_targetText(l, profiles, rule.target)),
                  onTap: () => _edit(rule),
                  trailing: Switch(
                    value: rule.enabled,
                    onChanged: (value) => _update(
                      ref,
                      (state) => state.copyWith(
                        rules: [
                          for (final r in state.rules)
                            r.id == rule.id ? r.copyWith(enabled: value) : r,
                        ],
                      ),
                    ),
                  ),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelAddRule),
                onTap: () => _edit(null),
              ),
            ],
            footer: l.autoTunnelRulesDesc,
          ),
          generateSectionV3(
            title: l.autoTunnelFallback,
            items: [
              ListItem<FallbackMode>.options(
                leading: const GlyphIcon(AppGlyphs.shuffle),
                title: Text(l.autoTunnelFallback),
                subtitle: Text(
                  settings.fallback == FallbackMode.target
                      ? _targetText(l, profiles, settings.fallbackTarget)
                      : _fallbackText(l, settings.fallback),
                ),
                dialogTitle: l.autoTunnelFallback,
                options: FallbackMode.values,
                value: settings.fallback,
                textBuilder: (mode) => _fallbackText(l, mode),
                onChanged: (mode) async {
                  if (mode == null) return;
                  if (mode != FallbackMode.target) {
                    _update(ref, (state) => state.copyWith(fallback: mode));
                    return;
                  }
                  final target = await BaseNavigator.push<TunnelTarget>(
                    context,
                    _TargetPicker(initial: settings.fallbackTarget),
                  );
                  if (target == null) return;
                  _update(
                    ref,
                    (state) =>
                        state.copyWith(fallback: mode, fallbackTarget: target),
                  );
                },
              ),
            ],
          ),
        ],
      ),
    );
  }

  String _fallbackText(AppLocalizations l, FallbackMode mode) => switch (mode) {
    FallbackMode.keep => l.autoTunnelFallbackKeep,
    FallbackMode.disconnect => l.autoTunnelDisconnect,
    FallbackMode.target => l.autoTunnelFallbackTarget,
  };
}

class _RuleEditor extends ConsumerStatefulWidget {
  const _RuleEditor({
    required this.rule,
    required this.sims,
    required this.isNew,
  });

  final AutoTunnelRule rule;
  final List<SimCard> sims;
  final bool isNew;

  @override
  ConsumerState<_RuleEditor> createState() => _RuleEditorState();
}

class _RuleEditorState extends ConsumerState<_RuleEditor> {
  late AutoTunnelRule _rule = widget.rule;

  void _set(AutoTunnelRule rule) => setState(() => _rule = rule);

  Future<String?> _typed(String title, String label) async {
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
    return picked == _manual ? _typed(title, manualLabel) : picked;
  }

  Future<void> _addNetwork() async {
    final l = context.appLocalizations;
    final nearby = system.isAndroid
        ? await _platform.wifiNetworks().catchError((_) => const <String>[])
        : [?await WifiSsidManager.instance.getSsid().catchError((_) => null)];
    final ssid = await _choose(l.autoTunnelAddNetwork, [
      for (final ssid in nearby)
        if (!_rule.ssids.contains(ssid)) (ssid, ssid),
    ], l.autoTunnelEnterName);
    if (ssid == null || _rule.ssids.contains(ssid)) return;
    _set(_rule.copyWith(ssids: [..._rule.ssids, ssid]));
  }

  Future<List<(String, String)>> _appChoices() async {
    if (Platform.isWindows) {
      final running = await runningExecutables();
      return [
        for (final exe in running.sorted())
          if (exe.endsWith('.exe')) (exe, exe),
      ];
    }
    final packages = await ref
        .read(systemActionProvider.notifier)
        .getPackages()
        .then((_) => ref.read(packagesProvider))
        .catchError((_) => const <Package>[]);
    final seen = await _platform.seenApps().catchError((_) => const <String>[]);
    return [
      for (final entry in seen)
        (entry.split('\t').first, entry.split('\t').last),
      for (final package in packages.sortedBy((p) => p.label.toLowerCase()))
        if (!package.system) (package.packageName, package.label),
    ];
  }

  Future<void> _addApp() async {
    final l = context.appLocalizations;
    final choices = await _appChoices();
    if (!mounted) return;
    final app = await _choose(l.autoTunnelAddApp, [
      for (final choice in choices)
        if (!_rule.apps.contains(choice.$1)) choice,
    ], Platform.isWindows ? l.autoTunnelExeName : l.autoTunnelPackageName);
    if (app == null || _rule.apps.contains(app)) return;
    _set(_rule.copyWith(apps: [..._rule.apps, app]));
  }

  Widget _removable(String text, Glyph glyph, VoidCallback onRemove) =>
      ListItem(
        leading: GlyphIcon(glyph),
        title: Text(text),
        trailing: IconButton(
          tooltip: context.appLocalizations.delete,
          onPressed: onRemove,
          icon: const GlyphIcon(AppGlyphs.delete),
        ),
      );

  List<Widget> _triggerItems(AppLocalizations l) => switch (_rule.kind) {
    TriggerKind.ethernet => [ListItem(title: Text(l.autoTunnelEthernetDesc))],
    TriggerKind.wifi => [
      if (_rule.ssids.isEmpty) ListItem(title: Text(l.autoTunnelAnyWifi)),
      for (final ssid in _rule.ssids)
        _removable(
          ssid,
          AppGlyphs.wifi,
          () => _set(
            _rule.copyWith(ssids: [..._rule.ssids.where((s) => s != ssid)]),
          ),
        ),
      ListItem(
        leading: const GlyphIcon(AppGlyphs.add),
        title: Text(l.autoTunnelAddNetwork),
        onTap: _addNetwork,
      ),
    ],
    TriggerKind.mobile => [
      if (widget.sims.isEmpty) ListItem(title: Text(l.autoTunnelAnySim)),
      for (final sim in widget.sims)
        ListItem.checkbox(
          title: Text(sim.name),
          subtitle: Text('SIM ${sim.slot + 1}'),
          value: _rule.simIds.contains(sim.id),
          onChanged: (checked) => _set(
            _rule.copyWith(
              simIds: checked == true
                  ? [..._rule.simIds, sim.id]
                  : [..._rule.simIds.where((id) => id != sim.id)],
            ),
          ),
        ),
    ],
    TriggerKind.app => [
      if (_rule.apps.isEmpty) ListItem(title: Text(l.autoTunnelNoApps)),
      for (final app in _rule.apps)
        _removable(
          app,
          AppGlyphs.appsList,
          () => _set(
            _rule.copyWith(apps: [..._rule.apps.where((a) => a != app)]),
          ),
        ),
      ListItem(
        leading: const GlyphIcon(AppGlyphs.add),
        title: Text(l.autoTunnelAddApp),
        onTap: _addApp,
      ),
    ],
  };

  bool get _valid => _rule.kind != TriggerKind.app || _rule.apps.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final profiles = ref.watch(profilesProvider);
    return CommonScaffold(
      title: widget.isNew ? l.autoTunnelAddRule : l.autoTunnelEditRule,
      actions: [
        if (!widget.isNew)
          IconButton(
            tooltip: l.delete,
            onPressed: () {
              _update(
                ref,
                (state) => state.copyWith(
                  rules: [...state.rules.where((r) => r.id != _rule.id)],
                ),
              );
              Navigator.of(context).pop();
            },
            icon: const GlyphIcon(AppGlyphs.delete),
          ),
        IconButton(
          tooltip: l.save,
          onPressed: _valid ? () => Navigator.of(context).pop(_rule) : null,
          icon: const GlyphIcon(AppGlyphs.check),
        ),
      ],
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            title: l.autoTunnelTrigger,
            items: [
              Padding(
                padding: const EdgeInsets.all(12),
                child: SegmentedButton<TriggerKind>(
                  showSelectedIcon: false,
                  segments: [
                    for (final kind in TriggerKind.values)
                      ButtonSegment(
                        value: kind,
                        icon: GlyphIcon(_kindGlyph(kind)),
                        tooltip: _kindText(l, kind),
                      ),
                  ],
                  selected: {_rule.kind},
                  onSelectionChanged: (kinds) =>
                      _set(_rule.copyWith(kind: kinds.first)),
                ),
              ),
              ListItem(title: Text(_kindText(l, _rule.kind))),
              ..._triggerItems(l),
            ],
            footer: switch (_rule.kind) {
              TriggerKind.app => l.autoTunnelAppsDesc,
              TriggerKind.wifi when system.isAndroid => l.autoTunnelWifiDesc,
              _ => null,
            },
          ),
          generateSectionV3(
            title: l.autoTunnelTarget,
            items: [
              ListItem(
                leading: const GlyphIcon(AppGlyphs.target),
                title: Text(_targetText(l, profiles, _rule.target)),
                trailing: const GlyphIcon(AppGlyphs.chevronForward),
                onTap: () async {
                  final target = await BaseNavigator.push<TunnelTarget>(
                    context,
                    _TargetPicker(initial: _rule.target),
                  );
                  if (target != null) _set(_rule.copyWith(target: target));
                },
              ),
            ],
          ),
        ],
      ),
    );
  }
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

class _TargetPicker extends ConsumerStatefulWidget {
  const _TargetPicker({required this.initial});

  final TunnelTarget initial;

  @override
  ConsumerState<_TargetPicker> createState() => _TargetPickerState();
}

class _TargetPickerState extends ConsumerState<_TargetPicker> {
  late TunnelTarget _target = widget.initial;
  _Groups _groups = const {};

  @override
  void initState() {
    super.initState();
    unawaited(_loadGroups());
  }

  Future<void> _loadGroups() async {
    final id = _target.profileId;
    final groups = id == null
        ? const <String, List<String>>{}
        : await _groupsOf(id);
    if (mounted) setState(() => _groups = groups);
  }

  void _pickProfile(int? id) {
    setState(() => _target = TunnelTarget(profileId: id));
    unawaited(_loadGroups());
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final profiles = ref.watch(profilesProvider);
    final group = _target.group;
    final groupProxies = group == null
        ? const <String>[]
        : _groups[group] ?? [];
    return CommonScaffold(
      title: l.autoTunnelTarget,
      actions: [
        IconButton(
          tooltip: l.save,
          onPressed: () => Navigator.of(context).pop(_target),
          icon: const GlyphIcon(AppGlyphs.check),
        ),
      ],
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          RadioGroup<int>(
            groupValue: _target.profileId ?? -1,
            onChanged: (id) {
              if (id != null) _pickProfile(id == -1 ? null : id);
            },
            child: generateSectionV3(
              items: [
                ListItem<int>.radio(
                  title: Text(l.autoTunnelDisconnect),
                  value: -1,
                ),
                for (final profile in profiles)
                  ListItem<int>.radio(
                    title: Text(profile.realLabel),
                    value: profile.id,
                  ),
              ],
            ),
          ),
          if (_target.profileId != null && _groups.isNotEmpty)
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
                      profileId: _target.profileId,
                      group: g == null || g.isEmpty ? null : g,
                    ),
                  ),
                ),
                if (group != null && groupProxies.isNotEmpty)
                  ListItem<String>.options(
                    title: Text(l.autoTunnelServer),
                    subtitle: Text(_target.proxy ?? l.autoTunnelKeepServer),
                    dialogTitle: l.autoTunnelServer,
                    options: groupProxies,
                    value: _target.proxy ?? groupProxies.first,
                    textBuilder: (p) => p,
                    onChanged: (p) => setState(
                      () => _target = TunnelTarget(
                        profileId: _target.profileId,
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
