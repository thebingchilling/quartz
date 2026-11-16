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
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

const _platform = AutoTunnelNotifier.platform;
const _manual = '\u0000manual';
const _doNothing = -2;
const _disconnect = -1;

Future<void> _update(
  WidgetRef ref,
  AutoTunnelSettings Function(AutoTunnelSettings) change,
) async {
  final before = ref.read(autoTunnelProvider).usesKillSwitch;
  await ref.read(autoTunnelProvider.notifier).update(change);
  final after = ref.read(autoTunnelProvider).usesKillSwitch;
  if (system.isWindows && after && !before) await _ensureHelper();
}

/// Windows' kill switch is a firewall the Helper service sets up.
Future<void> _ensureHelper() async {
  final ready = await helperClient.readiness(logFailure: false);
  if (ready == HelperReadiness.ready) return;
  await system.authorizeCore();
}

String _targetText(
  AppLocalizations l,
  List<Profile> profiles,
  TunnelTarget? target,
) {
  if (target == null) return l.autoTunnelDoNothing;
  if (target.disconnects) return l.autoTunnelDisconnect;
  final profile = profiles.firstWhereOrNull((p) => p.id == target.profileId);
  final name = profile?.realLabel ?? '?';
  return target.proxy == null ? name : '$name · ${target.proxy}';
}

String _summary(
  AppLocalizations l,
  List<Profile> profiles,
  TunnelTarget? target,
  bool killSwitch,
) {
  final text = _targetText(l, profiles, target);
  return killSwitch ? '$text · ${l.autoTunnelKillSwitch}' : text;
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
    final hasApps = s.apps.any((app) => app.target != null);
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

  void _openConnection(String title, ConnectionSettings blank) {
    unawaited(
      BaseNavigator.push<void>(
        context,
        _ItemPage(
          title: title,
          killSwitchDesc: context.appLocalizations.autoTunnelConnectionKillDesc,
          read: (s) {
            final c =
                s.connections.firstWhereOrNull((c) => c.id == blank.id) ??
                blank;
            return (c.target, c.killSwitch);
          },
          write: (s, target, killSwitch) => s.putConnection(
            ConnectionSettings(
              kind: blank.kind,
              ssid: blank.ssid,
              simId: blank.simId,
              target: target,
              killSwitch: killSwitch,
            ),
          ),
          onDelete: blank.ssid == null
              ? null
              : (s) => s.copyWith(
                  connections: [
                    ...s.connections.where((c) => c.id != blank.id),
                  ],
                ),
        ),
      ),
    );
  }

  void _openApp(AppSettings app) {
    unawaited(
      BaseNavigator.push<void>(
        context,
        _ItemPage(
          title: app.label,
          subtitle: app.app,
          killSwitchDesc: context.appLocalizations.autoTunnelAppKillDesc,
          read: (s) {
            final a = s.apps.firstWhereOrNull((a) => a.id == app.id) ?? app;
            return (a.target, a.killSwitch);
          },
          write: (s, target, killSwitch) => s.putApp(
            AppSettings(
              app: app.app,
              label: app.label,
              target: target,
              killSwitch: killSwitch,
            ),
          ),
          onDelete: (s) =>
              s.copyWith(apps: [...s.apps.where((a) => a.id != app.id)]),
        ),
      ),
    );
  }

  Future<void> _addNetwork(AppLocalizations l) async {
    final nearby = system.isAndroid
        ? await _platform.wifiNetworks().catchError((_) => const <String>[])
        : [?await WifiSsidManager.instance.getSsid().catchError((_) => null)];
    if (!mounted) return;
    final listed = ref.read(autoTunnelProvider).connections.map((c) => c.ssid);
    final ssid = await _choose(context, l.autoTunnelAddNetwork, [
      for (final ssid in nearby)
        if (!listed.contains(ssid)) (ssid, ssid),
    ], l.autoTunnelEnterName);
    if (ssid == null || !mounted) return;
    final blank = ConnectionSettings(kind: ConnectionKind.wifi, ssid: ssid);
    await _update(ref, (s) => s.putConnection(blank));
    _openConnection(ssid, blank);
  }

  Future<void> _addApp(AppLocalizations l) async {
    final choices = await _appChoices(ref);
    if (!mounted) return;
    final listed = ref.read(autoTunnelProvider).apps.map((a) => a.app).toSet();
    final app = await _choose(context, l.autoTunnelAddApp, [
      for (final choice in choices)
        if (!listed.contains(choice.$1)) choice,
    ], Platform.isWindows ? l.autoTunnelExeName : l.autoTunnelPackageName);
    if (app == null || !mounted) return;
    final label =
        choices.firstWhereOrNull((c) => c.$1 == app)?.$2 ??
        app.split(RegExp(r'[\\/]')).last;
    final settings = AppSettings(app: app, label: label);
    await _update(ref, (s) => s.putApp(settings));
    _openApp(settings);
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final settings = ref.watch(autoTunnelProvider);
    final profiles = ref.watch(profilesProvider);
    final permissionItems = _permissionItems(l, settings);
    Widget connection(String title, Glyph glyph, ConnectionSettings blank) {
      final c =
          settings.connections.firstWhereOrNull((c) => c.id == blank.id) ??
          blank;
      return ListItem(
        leading: GlyphIcon(glyph),
        title: Text(title),
        subtitle: Text(_summary(l, profiles, c.target, c.killSwitch)),
        trailing: const GlyphIcon(AppGlyphs.chevronForward),
        onTap: () => _openConnection(title, blank),
      );
    }

    final namedWifi = settings.connections.where(
      (c) => c.kind == ConnectionKind.wifi && c.ssid != null,
    );
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
            title: l.autoTunnelConnections,
            items: [
              for (final c in namedWifi) connection(c.ssid!, AppGlyphs.wifi, c),
              connection(
                l.autoTunnelOtherWifi,
                AppGlyphs.wifi,
                const ConnectionSettings(kind: ConnectionKind.wifi),
              ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelAddNetwork),
                onTap: () => _addNetwork(l),
              ),
              for (final sim in _sims)
                connection(
                  '${sim.name} · SIM ${sim.slot + 1}',
                  AppGlyphs.dataUsage,
                  ConnectionSettings(
                    kind: ConnectionKind.mobile,
                    simId: sim.id,
                  ),
                ),
              connection(
                _sims.isEmpty ? l.autoTunnelMobile : l.autoTunnelOtherMobile,
                AppGlyphs.dataUsage,
                const ConnectionSettings(kind: ConnectionKind.mobile),
              ),
              connection(
                l.autoTunnelEthernet,
                AppGlyphs.devices,
                const ConnectionSettings(kind: ConnectionKind.ethernet),
              ),
            ],
            footer: system.isAndroid ? l.autoTunnelWifiDesc : null,
          ),
          generateSectionV3(
            title: l.autoTunnelApps,
            items: [
              for (final app in settings.apps)
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.appsList),
                  title: Text(app.label),
                  subtitle: Text(
                    _summary(l, profiles, app.target, app.killSwitch),
                  ),
                  trailing: const GlyphIcon(AppGlyphs.chevronForward),
                  onTap: () => _openApp(app),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelAddApp),
                onTap: () => _addApp(l),
              ),
            ],
            footer: l.autoTunnelAppsDesc,
          ),
          generateSectionV3(
            title: l.autoTunnelTunnels,
            items: [
              for (final profile in profiles)
                ListItem.toggle(
                  leading: const GlyphIcon(AppGlyphs.lock),
                  title: Text(profile.realLabel),
                  subtitle: Text(l.autoTunnelKillSwitch),
                  value: settings.killSwitchTunnels.contains(profile.id),
                  onChanged: (value) => _update(
                    ref,
                    (s) => s.copyWith(
                      killSwitchTunnels: {
                        for (final id in s.killSwitchTunnels)
                          if (id != profile.id) id,
                        if (value) profile.id,
                      },
                    ),
                  ),
                ),
            ],
            footer: system.isWindows
                ? '${l.autoTunnelTunnelKillDesc} ${l.autoTunnelWindowsKillDesc}'
                : l.autoTunnelTunnelKillDesc,
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

Future<List<(String, String)>> _appChoices(WidgetRef ref) async {
  if (Platform.isWindows) {
    final running = await runningProgramPaths();
    return [
      for (final path in running) (path, path.split(RegExp(r'[\\/]')).last),
    ];
  }
  final packages = await ref
      .read(systemActionProvider.notifier)
      .getPackages()
      .then((_) => ref.read(packagesProvider))
      .catchError((_) => const <Package>[]);
  final seen = await _platform.seenApps().catchError((_) => const <String>[]);
  return [
    for (final entry in seen) (entry.split('\t').first, entry.split('\t').last),
    for (final package in packages.sortedBy((p) => p.label.toLowerCase()))
      if (!package.system) (package.packageName, package.label),
  ];
}

typedef _ItemState = (TunnelTarget?, bool);

class _ItemPage extends ConsumerWidget {
  const _ItemPage({
    required this.title,
    required this.read,
    required this.write,
    required this.killSwitchDesc,
    this.subtitle,
    this.onDelete,
  });

  final String title;
  final String? subtitle;
  final String killSwitchDesc;
  final _ItemState Function(AutoTunnelSettings) read;
  final AutoTunnelSettings Function(AutoTunnelSettings, TunnelTarget?, bool)
  write;
  final AutoTunnelSettings Function(AutoTunnelSettings)? onDelete;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.appLocalizations;
    final profiles = ref.watch(profilesProvider);
    final (target, killSwitch) = read(ref.watch(autoTunnelProvider));
    final delete = onDelete;
    return CommonScaffold(
      title: title,
      actions: [
        if (delete != null)
          IconButton(
            tooltip: l.delete,
            onPressed: () {
              unawaited(_update(ref, delete));
              Navigator.of(context).pop();
            },
            icon: const GlyphIcon(AppGlyphs.delete),
          ),
      ],
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              ListItem(
                leading: const GlyphIcon(AppGlyphs.target),
                title: Text(l.autoTunnelTarget),
                subtitle: Text(_targetText(l, profiles, target)),
                trailing: const GlyphIcon(AppGlyphs.chevronForward),
                onTap: () async {
                  final picked = await BaseNavigator.push<(TunnelTarget?,)>(
                    context,
                    _TargetPicker(initial: target),
                  );
                  if (picked == null) return;
                  await _update(ref, (s) => write(s, picked.$1, killSwitch));
                },
              ),
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.lock),
                title: Text(l.autoTunnelKillSwitch),
                value: killSwitch,
                onChanged: (value) =>
                    _update(ref, (s) => write(s, target, value)),
              ),
            ],
            footer: subtitle == null
                ? killSwitchDesc
                : '$subtitle\n\n$killSwitchDesc',
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

/// Pops `(target,)`, where a null target leaves the tunnel as it is.
class _TargetPicker extends ConsumerStatefulWidget {
  const _TargetPicker({required this.initial});

  final TunnelTarget? initial;

  @override
  ConsumerState<_TargetPicker> createState() => _TargetPickerState();
}

class _TargetPickerState extends ConsumerState<_TargetPicker> {
  late TunnelTarget? _target = widget.initial;
  _Groups _groups = const {};

  int get _choice => switch (_target) {
    null => _doNothing,
    TunnelTarget(:final profileId?) => profileId,
    _ => _disconnect,
  };

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
      () => _target = switch (choice) {
        _doNothing => null,
        _disconnect => const TunnelTarget.disconnect(),
        _ => TunnelTarget(profileId: choice),
      },
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
            groupValue: _choice,
            onChanged: (choice) {
              if (choice != null) _pick(choice);
            },
            child: generateSectionV3(
              items: [
                ListItem<int>.radio(
                  title: Text(l.autoTunnelDoNothing),
                  value: _doNothing,
                ),
                ListItem<int>.radio(
                  title: Text(l.autoTunnelDisconnect),
                  value: _disconnect,
                ),
                for (final profile in profiles)
                  ListItem<int>.radio(
                    title: Text(profile.realLabel),
                    value: profile.id,
                  ),
              ],
            ),
          ),
          if (target?.profileId != null && _groups.isNotEmpty)
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
                      profileId: target?.profileId,
                      group: g == null || g.isEmpty ? null : g,
                    ),
                  ),
                ),
                if (group != null && groupProxies.isNotEmpty)
                  ListItem<String>.options(
                    title: Text(l.autoTunnelServer),
                    subtitle: Text(target?.proxy ?? l.autoTunnelKeepServer),
                    dialogTitle: l.autoTunnelServer,
                    options: groupProxies,
                    value: target?.proxy ?? groupProxies.first,
                    textBuilder: (p) => p,
                    onChanged: (p) => setState(
                      () => _target = TunnelTarget(
                        profileId: target?.profileId,
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
