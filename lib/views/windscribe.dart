import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/windscribe/windscribe.dart';
import 'package:fl_clash/features/windscribe/windscribe_store.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Adds Windscribe's servers as a Clash profile of OpenVPN proxies and keeps
/// the list current. Asks for the OpenVPN credentials once.
class WindscribeView extends ConsumerStatefulWidget {
  const WindscribeView({super.key});

  @override
  ConsumerState<WindscribeView> createState() => _WindscribeViewState();
}

class _WindscribeViewState extends ConsumerState<WindscribeView> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _editing = false;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  WindscribeNotifier get _store => ref.read(windscribeProvider.notifier);

  Future<void> _refresh() async {
    await globalState.loadingRun(
      _store.refresh,
      title: context.appLocalizations.windscribeUpdate,
      tag: LoadingTag.profiles,
    );
  }

  Future<void> _saveCredentials() async {
    final username = _username.text.trim();
    final password = _password.text.trim();
    if (username.isEmpty || password.isEmpty) return;
    await _store.update(
      (state) => state.copyWith(username: username, password: password),
    );
    setState(() => _editing = false);
    await _refresh();
  }

  void _changeOptions(WindscribeSettings Function(WindscribeSettings) change) {
    unawaited(() async {
      await _store.update(change);
      if (ref.read(windscribeProvider).profileId != null) await _refresh();
    }());
  }

  Widget _credentialsForm(AppLocalizations l) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _username,
            autocorrect: false,
            decoration: InputDecoration(labelText: l.windscribeUsername),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _password,
            obscureText: true,
            autocorrect: false,
            decoration: InputDecoration(labelText: l.windscribePassword),
            onSubmitted: (_) => _saveCredentials(),
          ),
          const SizedBox(height: 8),
          Text(
            l.windscribeCredentialsDesc,
            style: context.textTheme.bodySmall?.copyWith(
              color: context.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 12),
          PageAction(
            child: FilledButton(
              onPressed: _saveCredentials,
              child: Text(l.windscribeSave),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final settings = ref.watch(windscribeProvider);
    final isLoading = ref.watch(loadingProvider(LoadingTag.profiles));
    final updatedAt = settings.updatedAt;
    final askCredentials = !settings.hasCredentials || _editing;
    return CommonScaffold(
      title: l.windscribe,
      isLoading: isLoading,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          if (askCredentials)
            _credentialsForm(l)
          else ...[
            generateSectionV3(
              items: [
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.key),
                  title: Text(l.windscribeCredentials),
                  subtitle: Text(settings.username),
                  trailing: TextButton(
                    onPressed: () => setState(() {
                      _username.text = settings.username;
                      _password.clear();
                      _editing = true;
                    }),
                    child: Text(l.windscribeChange),
                  ),
                ),
              ],
            ),
            generateSectionV3(
              title: l.options,
              items: [
                ListItem<WindscribeProto>.options(
                  leading: const GlyphIcon(AppGlyphs.vpn),
                  title: Text(l.windscribeProtocol),
                  subtitle: Text(settings.proto.name.toUpperCase()),
                  dialogTitle: l.windscribeProtocol,
                  options: WindscribeProto.values,
                  value: settings.proto,
                  textBuilder: (proto) => proto.name.toUpperCase(),
                  onChanged: (proto) {
                    if (proto == null || proto == settings.proto) return;
                    _changeOptions(
                      (state) => state.copyWith(proto: proto, port: 443),
                    );
                  },
                ),
                ListItem<int>.options(
                  leading: const GlyphIcon(AppGlyphs.link),
                  title: Text(l.windscribePort),
                  subtitle: Text('${settings.port}'),
                  dialogTitle: l.windscribePort,
                  options: settings.proto.ports,
                  value: settings.port,
                  textBuilder: (port) => '$port',
                  onChanged: (port) {
                    if (port == null || port == settings.port) return;
                    _changeOptions((state) => state.copyWith(port: port));
                  },
                ),
              ],
            ),
            const SizedBox(height: 16),
            generateSectionV3(
              items: [
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.sync),
                  title: Text(l.windscribeUpdate),
                  subtitle: Text(
                    updatedAt == null
                        ? l.windscribeNotAdded
                        : l.windscribeStatus(
                            settings.count,
                            updatedAt.getLastUpdateTimeDesc(context),
                          ),
                  ),
                  onTap: isLoading ? null : _refresh,
                ),
              ],
              footer: l.windscribeListDesc,
            ),
            generateSectionV3(
              title: l.windscribeLocations,
              items: settings.servers.isEmpty || settings.profileId == null
                  ? [ListItem(title: Text(l.windscribeLocationsEmpty))]
                  : [
                      for (final country in windscribeCountries(
                        settings.servers,
                      ))
                        _CountryItem(country: country),
                    ],
            ),
          ],
        ],
      ),
    );
  }
}

/// The Windscribe profile's choices: a country in the Windscribe group, and a
/// server in that country's group.
({String? country, Map<String, String> selected}) _choices(WidgetRef ref) {
  final profileId = ref.watch(windscribeProvider.select((s) => s.profileId));
  final selected = ref.watch(
    profilesProvider.select(
      (profiles) => profileId == null
          ? const <String, String>{}
          : profiles.getProfile(profileId)?.selectedMap ?? const {},
    ),
  );
  final servers = ref.watch(windscribeProvider.select((s) => s.servers));
  return (
    country:
        selected[windscribeGroupName] ??
        windscribeCountries(servers).firstOrNull,
    selected: selected,
  );
}

List<(WindscribeServer, String)> _serversIn(WidgetRef ref, String country) {
  final servers = ref.watch(windscribeProvider.select((s) => s.servers));
  return [
    for (final entry in windscribeProxyNames(servers))
      if (entry.$1.country == country) entry,
  ];
}

class _Flag extends StatelessWidget {
  const _Flag(this.countryCode);

  final String countryCode;

  @override
  Widget build(BuildContext context) {
    return Text(
      countryCode.countryFlagEmoji,
      style: context.textTheme.titleLarge?.copyWith(
        fontFamily: FontFamily.twEmoji.value,
      ),
    );
  }
}

class _CountryItem extends ConsumerWidget {
  const _CountryItem({required this.country});

  final String country;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final servers = _serversIn(ref, country);
    final choices = _choices(ref);
    final chosen = choices.country == country;
    final server = choices.selected[country];
    return ListItem(
      leading: _Flag(servers.firstOrNull?.$1.countryCode ?? ''),
      title: Text(country),
      subtitle: Text(
        chosen && server != null
            ? server
            : context.appLocalizations.windscribeServerCount(servers.length),
      ),
      trailing: GlyphIcon(
        chosen ? AppGlyphs.checkCircle : AppGlyphs.chevronForward,
      ),
      onTap: () =>
          BaseNavigator.push<void>(context, _CountryView(country: country)),
    );
  }
}

/// One country's servers: a tap connects through one, and each tests alone,
/// since Windscribe turns away a burst of logins.
class _CountryView extends ConsumerWidget {
  const _CountryView({required this.country});

  final String country;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final servers = _serversIn(ref, country);
    final choices = _choices(ref);
    final chosen = choices.selected[country] ?? servers.firstOrNull?.$2;
    final testable = ref.watch(windscribeProfileActiveProvider);
    return CommonScaffold(
      title: country,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              for (final (server, proxyName) in servers)
                ListItem(
                  leading: _Flag(server.countryCode),
                  title: Text(server.name),
                  subtitle: testable ? _ServerDelay(proxyName) : null,
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (testable)
                        IconButton(
                          tooltip: context.appLocalizations.delayTest,
                          icon: const GlyphIcon(AppGlyphs.bolt),
                          onPressed: () => ref
                              .read(proxiesActionProvider.notifier)
                              .proxyDelayTest(
                                Proxy(name: proxyName, type: 'OpenVPN'),
                              ),
                        ),
                      if (proxyName == chosen && choices.country == country)
                        const GlyphIcon(AppGlyphs.checkCircle),
                    ],
                  ),
                  onTap: () => ref
                      .read(windscribeProvider.notifier)
                      .select(country, proxyName),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ServerDelay extends ConsumerWidget {
  const _ServerDelay(this.proxyName);

  final String proxyName;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final delay = ref.watch(delayProvider(proxyName: proxyName));
    final phase = ref.watch(delayTestPhaseProvider(proxyName: proxyName));
    final l = context.appLocalizations;
    final text = switch ((phase, delay)) {
      (DelayTestPhase _, _) => '…',
      (null, final int delay) when delay > 0 => '$delay ms',
      (null, final int delay) => delayFailureText(delay, l),
      (null, null) => '—',
    };
    return Text(
      text,
      style: TextStyle(color: context.colorScheme.delayColor(delay)),
    );
  }
}
