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
const _fallback = -1;

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

Future<void> _pickTunnel(
  BuildContext context,
  WidgetRef ref, {
  required TunnelTarget? initial,
  required String fallback,
  required AutoTunnelSettings Function(AutoTunnelSettings, TunnelTarget?) write,
}) async {
  final picked = await BaseNavigator.push<(TunnelTarget?,)>(
    context,
    _TunnelPicker(initial: initial, fallback: fallback),
  );
  if (picked == null) return;
  await _update(ref, (s) => write(s, picked.$1));
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
    final hasApps = s.apps.isNotEmpty;
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

  void _openApp(AppSettings app) {
    unawaited(BaseNavigator.push<void>(context, _AppPage(app: app)));
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

  void _setSim(SimCard sim, bool on) {
    unawaited(
      _update(ref, (s) {
        final all = {for (final card in _sims) card.id};
        final counted = s.mobileSims.isEmpty ? all : {...s.mobileSims};
        on ? counted.add(sim.id) : counted.remove(sim.id);
        return s.copyWith(
          mobileSims: counted.containsAll(all) ? const {} : counted,
        );
      }),
    );
  }

  List<Widget> _simItems(AppLocalizations l, AutoTunnelSettings s) {
    final profiles = ref.watch(profilesProvider);
    return [
      for (final sim in _sims)
        ListItem(
          leading: const GlyphIcon(AppGlyphs.dataUsage),
          title: Text(sim.title),
          subtitle: Text(
            _tunnelText(
              profiles,
              s.simTunnels[sim.id],
              l.autoTunnelMobileTunnel,
            ),
          ),
          onTap: () => _pickTunnel(
            context,
            ref,
            initial: s.simTunnels[sim.id],
            fallback: l.autoTunnelMobileTunnel,
            write: (s, target) => s.copyWith(
              simTunnels: {
                for (final MapEntry(:key, :value) in s.simTunnels.entries)
                  if (key != sim.id) key: value,
                sim.id: ?target,
              },
            ),
          ),
          trailing: Switch(
            value: s.mobileSims.isEmpty || s.mobileSims.contains(sim.id),
            onChanged: s.tunnelOnMobileData ? (on) => _setSim(sim, on) : null,
          ),
        ),
    ];
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
                onTap: () => _pickTunnel(
                  context,
                  ref,
                  initial: settings.defaultTunnel,
                  fallback: l.autoTunnelCurrentProfile,
                  write: (s, target) => s.copyWith(defaultTunnel: () => target),
                ),
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
            title: l.autoTunnelWifi,
            items: [
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.wifi),
                title: Text(l.autoTunnelOnWifi),
                value: settings.tunnelOnWifi,
                onChanged: (value) =>
                    _update(ref, (s) => s.copyWith(tunnelOnWifi: value)),
              ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.lockOpen),
                title: Text(l.autoTunnelTrustedNetworks),
                subtitle: Text(
                  settings.trustedNetworks.isEmpty
                      ? l.none
                      : settings.trustedNetworks.join(', '),
                ),
                trailing: const GlyphIcon(AppGlyphs.chevronForward),
                onTap: () => BaseNavigator.push<void>(
                  context,
                  const _TrustedNetworksPage(),
                ),
              ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.link),
                title: Text(l.autoTunnelWifiTunnels),
                subtitle: Text(
                  settings.wifiTunnels.isEmpty
                      ? l.none
                      : settings.wifiTunnels.keys.join(', '),
                ),
                trailing: const GlyphIcon(AppGlyphs.chevronForward),
                onTap: () =>
                    BaseNavigator.push<void>(context, const _WifiTunnelsPage()),
              ),
            ],
            footer: system.isAndroid ? l.autoTunnelWifiDesc : null,
          ),
          generateSectionV3(
            title: l.autoTunnelMobile,
            items: [
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.dataUsage),
                title: Text(l.autoTunnelOnMobile),
                value: settings.tunnelOnMobileData,
                onChanged: (value) =>
                    _update(ref, (s) => s.copyWith(tunnelOnMobileData: value)),
              ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.target),
                title: Text(l.autoTunnelMobileTunnel),
                subtitle: Text(
                  _tunnelText(
                    profiles,
                    settings.mobileTunnel,
                    l.autoTunnelDefaultTunnel,
                  ),
                ),
                trailing: const GlyphIcon(AppGlyphs.chevronForward),
                onTap: () => _pickTunnel(
                  context,
                  ref,
                  initial: settings.mobileTunnel,
                  fallback: l.autoTunnelDefaultTunnel,
                  write: (s, target) => s.copyWith(mobileTunnel: () => target),
                ),
              ),
              ..._simItems(l, settings),
            ],
            footer: _sims.isEmpty ? null : l.autoTunnelSimsDesc,
          ),
          generateSectionV3(
            title: l.autoTunnelApps,
            items: [
              for (final app in settings.apps)
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.appsList),
                  title: Text(app.label),
                  subtitle: Text(
                    [
                      _tunnelText(
                        profiles,
                        app.target,
                        l.autoTunnelDefaultTunnel,
                      ),
                      if (app.killSwitch) l.autoTunnelKillSwitch,
                    ].join(' · '),
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
            title: l.autoTunnelAdvanced,
            items: [
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
            title: l.autoTunnelKillSwitch,
            items: [
              for (final profile in profiles)
                ListItem.toggle(
                  leading: const GlyphIcon(AppGlyphs.lock),
                  title: Text(profile.realLabel),
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

Future<List<String>> _nearbyNetworks() async => system.isAndroid
    ? await _platform.wifiNetworks().catchError((_) => const <String>[])
    : [?await WifiSsidManager.instance.getSsid().catchError((_) => null)];

Future<String?> _pickNetwork(
  BuildContext context,
  Iterable<String> listed,
) async {
  final l = context.appLocalizations;
  final nearby = await _nearbyNetworks();
  if (!context.mounted) return null;
  return _choose(context, l.autoTunnelAddNetwork, [
    for (final ssid in nearby)
      if (!listed.contains(ssid)) (ssid, ssid),
  ], l.autoTunnelEnterName);
}

class _TrustedNetworksPage extends ConsumerWidget {
  const _TrustedNetworksPage();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.appLocalizations;
    final trusted = ref.watch(
      autoTunnelProvider.select((s) => s.trustedNetworks),
    );
    return CommonScaffold(
      title: l.autoTunnelTrustedNetworks,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              for (final ssid in trusted)
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.wifi),
                  title: Text(ssid),
                  trailing: IconButton(
                    tooltip: l.delete,
                    icon: const GlyphIcon(AppGlyphs.delete),
                    onPressed: () => _update(
                      ref,
                      (s) => s.copyWith(
                        trustedNetworks: [
                          for (final t in s.trustedNetworks)
                            if (t != ssid) t,
                        ],
                      ),
                    ),
                  ),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelAddNetwork),
                onTap: () async {
                  final ssid = await _pickNetwork(context, trusted);
                  if (ssid == null) return;
                  await _update(
                    ref,
                    (s) => s.copyWith(
                      trustedNetworks: [...s.trustedNetworks, ssid],
                    ),
                  );
                },
              ),
            ],
            footer: l.autoTunnelTrustedDesc,
          ),
        ],
      ),
    );
  }
}

class _WifiTunnelsPage extends ConsumerWidget {
  const _WifiTunnelsPage();

  AutoTunnelSettings Function(AutoTunnelSettings, TunnelTarget?) _write(
    String ssid,
  ) =>
      (s, target) => s.copyWith(
        wifiTunnels: {
          for (final MapEntry(:key, :value) in s.wifiTunnels.entries)
            if (key != ssid) key: value,
          ssid: ?target,
        },
      );

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.appLocalizations;
    final tunnels = ref.watch(autoTunnelProvider.select((s) => s.wifiTunnels));
    final profiles = ref.watch(profilesProvider);
    return CommonScaffold(
      title: l.autoTunnelWifiTunnels,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              for (final MapEntry(key: ssid, value: target) in tunnels.entries)
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.wifi),
                  title: Text(ssid),
                  subtitle: Text(_targetText(profiles, target)),
                  onTap: () => _pickTunnel(
                    context,
                    ref,
                    initial: target,
                    fallback: l.autoTunnelDefaultTunnel,
                    write: _write(ssid),
                  ),
                  trailing: IconButton(
                    tooltip: l.delete,
                    icon: const GlyphIcon(AppGlyphs.delete),
                    onPressed: () => _update(ref, (s) => _write(ssid)(s, null)),
                  ),
                ),
              ListItem(
                leading: const GlyphIcon(AppGlyphs.add),
                title: Text(l.autoTunnelAddNetwork),
                onTap: () async {
                  final ssid = await _pickNetwork(context, tunnels.keys);
                  if (ssid == null || !context.mounted) return;
                  await _pickTunnel(
                    context,
                    ref,
                    initial: null,
                    fallback: l.autoTunnelDefaultTunnel,
                    write: _write(ssid),
                  );
                },
              ),
            ],
            footer: l.autoTunnelWifiTunnelsDesc,
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

class _AppPage extends ConsumerWidget {
  const _AppPage({required this.app});

  final AppSettings app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.appLocalizations;
    final profiles = ref.watch(profilesProvider);
    final current = ref.watch(
      autoTunnelProvider.select(
        (s) => s.apps.firstWhereOrNull((a) => a.id == app.id) ?? app,
      ),
    );
    return CommonScaffold(
      title: app.label,
      actions: [
        IconButton(
          tooltip: l.delete,
          onPressed: () {
            unawaited(
              _update(
                ref,
                (s) =>
                    s.copyWith(apps: [...s.apps.where((a) => a.id != app.id)]),
              ),
            );
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
                subtitle: Text(
                  _tunnelText(
                    profiles,
                    current.target,
                    l.autoTunnelDefaultTunnel,
                  ),
                ),
                trailing: const GlyphIcon(AppGlyphs.chevronForward),
                onTap: () => _pickTunnel(
                  context,
                  ref,
                  initial: current.target,
                  fallback: l.autoTunnelDefaultTunnel,
                  write: (s, target) =>
                      s.putApp(current.copyWith(target: () => target)),
                ),
              ),
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.lock),
                title: Text(l.autoTunnelKillSwitch),
                value: current.killSwitch,
                onChanged: (value) => _update(
                  ref,
                  (s) => s.putApp(current.copyWith(killSwitch: value)),
                ),
              ),
            ],
            footer: '${app.app}\n\n${l.autoTunnelAppKillDesc}',
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
