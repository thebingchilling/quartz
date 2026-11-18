import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/windscribe/windscribe.dart';
import 'package:fl_clash/features/windscribe/windscribe_store.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/proxies/top_bar.dart';
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
  bool _openVpnExpanded = false;

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
              title: l.windscribeLocations,
              items: _locationItems(l, settings),
            ),
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
              items: [
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.vpn),
                  title: Text(l.windscribeOpenVpn),
                  subtitle: Text(
                    '${settings.proto.name.toUpperCase()} · ${settings.port}',
                  ),
                  trailing: AnimatedRotation(
                    turns: _openVpnExpanded ? 0.5 : 0,
                    duration: commonDuration,
                    child: const GlyphIcon(AppGlyphs.chevronDown),
                  ),
                  onTap: () =>
                      setState(() => _openVpnExpanded = !_openVpnExpanded),
                ),
              ],
            ),
            AnimatedSize(
              duration: commonDuration,
              curve: Curves.easeOutCubic,
              alignment: Alignment.topCenter,
              child: _openVpnExpanded
                  ? _openVpnSettings(l, settings)
                  : const SizedBox(width: double.infinity),
            ),
          ],
        ],
      ),
    );
  }

  List<Widget> _locationItems(AppLocalizations l, WindscribeSettings settings) {
    if (settings.servers.isEmpty || settings.profileId == null) {
      return [ListItem(title: Text(l.windscribeLocationsEmpty))];
    }
    return [
      for (final country in windscribeCountries(settings.servers))
        WindscribeCountryItem(country: country),
    ];
  }

  Widget _openVpnSettings(AppLocalizations l, WindscribeSettings settings) {
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: generateSectionV3(
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
    );
  }
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

/// A location: a tap writes the profile with its servers, which the proxies
/// page then offers.
class WindscribeCountryItem extends ConsumerWidget {
  const WindscribeCountryItem({
    super.key,
    required this.country,
    this.onSelected,
  });

  final String country;
  final VoidCallback? onSelected;

  Future<void> _select(WidgetRef ref) async {
    await globalState.loadingRun(
      () => ref.read(windscribeProvider.notifier).selectCountry(country),
      title: country,
      tag: LoadingTag.profiles,
    );
    onSelected?.call();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final (:code, :count, :chosen) = ref.watch(
      windscribeProvider.select((s) {
        final servers = s.servers.where((server) => server.country == country);
        return (
          code: servers.firstOrNull?.countryCode ?? '',
          count: servers.length,
          chosen: windscribeCountryOf(s.servers, s.country) == country,
        );
      }),
    );
    final isLoading = ref.watch(loadingProvider(LoadingTag.profiles));
    return ListItem(
      leading: _Flag(code),
      title: Text(country),
      subtitle: Text(context.appLocalizations.windscribeServerCount(count)),
      trailing: chosen ? const GlyphIcon(AppGlyphs.checkCircle) : null,
      onTap: chosen || isLoading ? null : () => _select(ref),
    );
  }
}

/// The locations alone, opened from the proxies page; picking one goes back.
class WindscribeLocationsView extends ConsumerWidget {
  const WindscribeLocationsView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.appLocalizations;
    final servers = ref.watch(windscribeProvider.select((s) => s.servers));
    final isLoading = ref.watch(loadingProvider(LoadingTag.profiles));
    return CommonScaffold(
      title: l.windscribeLocations,
      isLoading: isLoading,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            items: [
              for (final country in windscribeCountries(servers))
                WindscribeCountryItem(
                  country: country,
                  onSelected: () {
                    if (context.mounted) Navigator.of(context).maybePop();
                  },
                ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Puts the chosen location and a way to change it above the proxies.
class WindscribeProxiesHeader extends ConsumerWidget {
  const WindscribeProxiesHeader({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.appLocalizations;
    final (:country, :code) = ref.watch(
      windscribeProvider.select((s) {
        final country = windscribeCountryOf(s.servers, s.country);
        return (
          country: country,
          code:
              s.servers
                  .where((server) => server.country == country)
                  .firstOrNull
                  ?.countryCode ??
              '',
        );
      }),
    );
    return ProxiesTopBar(
      bar: CommonCard(
        radius: AppCorner.xl,
        onPressed: () =>
            BaseNavigator.push<void>(context, const WindscribeLocationsView()),
        child: ListItem(
          leading: _Flag(code),
          title: Text(country ?? l.windscribe),
          subtitle: Text(l.windscribeChangeCountry),
          trailing: const GlyphIcon(AppGlyphs.chevronForward),
        ),
      ),
      child: child,
    );
  }
}
