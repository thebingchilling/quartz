import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:wifi_ssid/wifi_ssid.dart';

/// Auto-tunnel rules (Android): Wi-Fi by name as a blacklist or whitelist,
/// mobile data per SIM, and Ethernet.
class AutoTunnelView extends ConsumerStatefulWidget {
  const AutoTunnelView({super.key});

  @override
  ConsumerState<AutoTunnelView> createState() => _AutoTunnelViewState();
}

class _AutoTunnelViewState extends ConsumerState<AutoTunnelView>
    with WidgetsBindingObserver {
  static const _platform = AutoTunnelNotifier.platform;
  static const _manual = '\u0000manual';

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
    // Permissions granted in system settings show up on return.
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

  void _update(AutoTunnelSettings Function(AutoTunnelSettings) change) {
    unawaited(ref.read(autoTunnelProvider.notifier).update(change));
  }

  Future<void> _addNetwork(AppLocalizations l) async {
    final nearby = system.isAndroid
        ? await _platform.wifiNetworks().catchError((_) => const <String>[])
        : [?await WifiSsidManager.instance.getSsid().catchError((_) => null)];
    final listed = ref.read(autoTunnelProvider).ssids;
    final choices = [
      for (final ssid in nearby)
        if (!listed.contains(ssid)) ssid,
    ];
    var picked = _manual;
    if (choices.isNotEmpty) {
      final selected = await dialogs.showCommonDialog<(String,)>(
        child: OptionsDialog<(String,)>(
          title: l.autoTunnelAddNetwork,
          options: [
            for (final ssid in [...choices, _manual]) (ssid,),
          ],
          textBuilder: (option) =>
              option.$1 == _manual ? l.autoTunnelEnterName : option.$1,
          value: (_manual,),
        ),
      );
      if (selected == null) return;
      picked = selected.$1;
    }
    if (picked == _manual) {
      final typed = await dialogs.showCommonDialog<String>(
        child: InputDialog(
          title: l.autoTunnelAddNetwork,
          value: '',
          labelText: l.autoTunnelEnterName,
          validator: (value) => value == null || value.trim().isEmpty
              ? l.emptyTip(l.autoTunnelEnterName)
              : null,
        ),
      );
      if (typed == null || typed.trim().isEmpty) return;
      picked = typed.trim();
    }
    final ssid = picked;
    _update(
      (state) => state.ssids.contains(ssid)
          ? state
          : state.copyWith(ssids: [...state.ssids, ssid]),
    );
  }

  List<Widget> _permissionItems(AppLocalizations l) {
    final permissions = _permissions;
    if (permissions == null) return const [];
    Widget item(String title, Glyph glyph, String kind) => ListItem(
      leading: GlyphIcon(glyph),
      title: Text(title),
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
    ];
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final settings = ref.watch(autoTunnelProvider);
    final permissionItems = _permissionItems(l);
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
                    _update((state) => state.copyWith(enabled: value)),
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
            title: l.autoTunnelNetworks,
            items: [
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.wifi),
                title: Text(l.autoTunnelOnWifi),
                value: settings.wifiEnabled,
                onChanged: (value) =>
                    _update((state) => state.copyWith(wifiEnabled: value)),
              ),
              if (settings.wifiEnabled) ...[
                ListItem<WifiRuleMode>.options(
                  leading: const GlyphIcon(AppGlyphs.filter),
                  title: Text(l.autoTunnelWifiMode),
                  subtitle: Text(_modeText(l, settings.wifiMode)),
                  dialogTitle: l.autoTunnelWifiMode,
                  options: WifiRuleMode.values,
                  value: settings.wifiMode,
                  textBuilder: (mode) => _modeText(l, mode),
                  onChanged: (mode) {
                    if (mode != null) {
                      _update((state) => state.copyWith(wifiMode: mode));
                    }
                  },
                ),
                if (settings.ssids.isEmpty)
                  ListItem(title: Text(l.autoTunnelNoNetworks)),
                for (final ssid in settings.ssids)
                  ListItem(
                    leading: const GlyphIcon(AppGlyphs.wifi),
                    title: Text(ssid),
                    trailing: IconButton(
                      tooltip: l.delete,
                      onPressed: () => _update(
                        (state) => state.copyWith(
                          ssids: [
                            for (final s in state.ssids)
                              if (s != ssid) s,
                          ],
                        ),
                      ),
                      icon: const GlyphIcon(AppGlyphs.delete),
                    ),
                  ),
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.add),
                  title: Text(l.autoTunnelAddNetwork),
                  onTap: () => _addNetwork(l),
                ),
              ],
            ],
            footer: system.isAndroid ? l.autoTunnelWifiDesc : null,
          ),
          generateSectionV3(
            items: [
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.dataUsage),
                title: Text(l.autoTunnelOnMobile),
                value: settings.mobileEnabled,
                onChanged: (value) =>
                    _update((state) => state.copyWith(mobileEnabled: value)),
              ),
              if (settings.mobileEnabled && _sims.length > 1) ...[
                ListItem.checkbox(
                  title: Text(l.autoTunnelAllSims),
                  value: settings.simIds.isEmpty,
                  onChanged: (_) =>
                      _update((state) => state.copyWith(simIds: const [])),
                ),
                for (final sim in _sims)
                  ListItem.checkbox(
                    title: Text(sim.name),
                    subtitle: Text('SIM ${sim.slot + 1}'),
                    value: settings.simIds.contains(sim.id),
                    onChanged: (checked) => _update(
                      (state) => state.copyWith(
                        simIds: checked == true
                            ? [...state.simIds, sim.id]
                            : [
                                for (final id in state.simIds)
                                  if (id != sim.id) id,
                              ],
                      ),
                    ),
                  ),
              ],
              ListItem.toggle(
                leading: const GlyphIcon(AppGlyphs.devices),
                title: Text(l.autoTunnelOnEthernet),
                value: settings.ethernetEnabled,
                onChanged: (value) =>
                    _update((state) => state.copyWith(ethernetEnabled: value)),
              ),
            ],
          ),
        ],
      ),
    );
  }

  String _modeText(AppLocalizations l, WifiRuleMode mode) => switch (mode) {
    WifiRuleMode.blacklist => l.autoTunnelBlacklist,
    WifiRuleMode.whitelist => l.autoTunnelWhitelist,
  };
}
