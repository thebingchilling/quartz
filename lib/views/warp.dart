import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/warp/warp.dart';
import 'package:fl_clash/features/warp/warp_store.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Generates free Cloudflare WARP profiles (WireGuard or AmneziaWG) as Clash
/// profiles, and shows how long each has left.
class WarpView extends ConsumerStatefulWidget {
  const WarpView({super.key});

  @override
  ConsumerState<WarpView> createState() => _WarpViewState();
}

class _WarpViewState extends ConsumerState<WarpView> {
  var _options = const WarpOptions();

  void _update(WarpOptions options) => setState(() => _options = options);

  Future<void> _run(Future<void> Function() action) async {
    await globalState.loadingRun(
      action,
      title: context.appLocalizations.warpGenerator,
      tag: LoadingTag.profiles,
    );
  }

  int? _intIn(String? value, int min, int max) {
    final number = int.tryParse(value?.trim() ?? '');
    return number != null && number >= min && number <= max ? number : null;
  }

  String? _validateInt(String? value, int min, int max) =>
      _intIn(value, min, max) == null ? '$min–$max' : null;

  List<Widget> _optionItems(AppLocalizations l) {
    final junk = _options.junk;
    return [
      ListItem<WarpVariant>.options(
        leading: const GlyphIcon(AppGlyphs.vpn),
        title: Text(l.warpVariant),
        subtitle: Text(_options.variant.label),
        dialogTitle: l.warpVariant,
        options: WarpVariant.values,
        value: _options.variant,
        textBuilder: (value) => value.label,
        onChanged: (value) {
          if (value != null) _update(_options.copyWith(variant: value));
        },
      ),
      ListItem<WarpEndpointMode>.options(
        leading: const GlyphIcon(AppGlyphs.target),
        title: Text(l.warpEndpoint),
        subtitle: Text(switch (_options.endpointMode) {
          WarpEndpointMode.auto => l.warpEndpointAuto,
          _ => _options.endpoint,
        }),
        dialogTitle: l.warpEndpoint,
        options: WarpEndpointMode.values,
        value: _options.endpointMode,
        textBuilder: (value) => switch (value) {
          WarpEndpointMode.auto => l.warpEndpointAuto,
          WarpEndpointMode.random => l.warpEndpointRandom,
          WarpEndpointMode.custom => l.custom,
        },
        onChanged: (value) {
          if (value == null) return;
          _update(
            _options.copyWith(
              endpointMode: value,
              endpoint: switch (value) {
                WarpEndpointMode.auto => '',
                WarpEndpointMode.random => WarpEndpoints.random(),
                WarpEndpointMode.custom =>
                  _options.endpoint.isEmpty
                      ? WarpEndpoints.defaultHost
                      : _options.endpoint,
              },
            ),
          );
        },
      ),
      if (_options.endpointMode == WarpEndpointMode.random)
        ListItem(
          leading: const GlyphIcon(AppGlyphs.shuffle),
          title: Text(l.warpRandomize),
          onTap: () =>
              _update(_options.copyWith(endpoint: WarpEndpoints.random())),
        ),
      if (_options.endpointMode == WarpEndpointMode.custom)
        ListItem.input(
          leading: const GlyphIcon(AppGlyphs.edit),
          title: Text(l.custom),
          subtitle: Text(_options.endpoint),
          dialogTitle: l.warpEndpoint,
          value: _options.endpoint,
          validator: (value) =>
              RegExp(r'^\S+:\d{1,5}$').hasMatch(value?.trim() ?? '')
              ? null
              : 'host:port',
          onChanged: (value) {
            if (value != null) {
              _update(_options.copyWith(endpoint: value.trim()));
            }
          },
        ),
      ListItem.input(
        leading: const GlyphIcon(AppGlyphs.speed),
        title: Text(l.warpMtu),
        subtitle: Text('${_options.mtu}'),
        dialogTitle: l.warpMtu,
        value: '${_options.mtu}',
        keyboardType: TextInputType.number,
        validator: (value) => _validateInt(value, 576, 1500),
        onChanged: (value) {
          final mtu = _intIn(value, 576, 1500);
          if (mtu != null) _update(_options.copyWith(mtu: mtu));
        },
      ),
      ListItem.input(
        leading: const GlyphIcon(AppGlyphs.dns),
        title: Text(l.warpDns),
        subtitle: Text(_options.dns),
        dialogTitle: l.warpDns,
        value: _options.dns,
        onChanged: (value) {
          if (value != null) _update(_options.copyWith(dns: value.trim()));
        },
      ),
      ListItem.input(
        leading: const GlyphIcon(AppGlyphs.clock),
        title: Text(l.warpKeepalive),
        subtitle: Text('${_options.keepalive} s'),
        dialogTitle: l.warpKeepalive,
        value: '${_options.keepalive}',
        suffixText: 's',
        keyboardType: TextInputType.number,
        validator: (value) => _validateInt(value, 0, 600),
        onChanged: (value) {
          final seconds = _intIn(value, 0, 600);
          if (seconds != null) _update(_options.copyWith(keepalive: seconds));
        },
      ),
      if (_options.variant == WarpVariant.awg) ...[
        _junkItem(l.warpJunkCount, junk.jc, 0, 128, (v) {
          return WarpJunk(jc: v, jMin: junk.jMin, jMax: junk.jMax);
        }),
        _junkItem(l.warpJunkMin, junk.jMin, 0, 1280, (v) {
          return WarpJunk(jc: junk.jc, jMin: v, jMax: junk.jMax);
        }),
        _junkItem(l.warpJunkMax, junk.jMax, 0, 1280, (v) {
          return WarpJunk(jc: junk.jc, jMin: junk.jMin, jMax: v);
        }),
      ],
    ];
  }

  Widget _junkItem(
    String title,
    int value,
    int min,
    int max,
    WarpJunk Function(int value) change,
  ) {
    return ListItem.input(
      leading: const GlyphIcon(AppGlyphs.sliders),
      title: Text(title),
      subtitle: Text('$value'),
      dialogTitle: title,
      value: '$value',
      keyboardType: TextInputType.number,
      validator: (text) => _validateInt(text, min, max),
      onChanged: (text) {
        final number = _intIn(text, min, max);
        if (number != null) _update(_options.copyWith(junk: change(number)));
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final entries = ref.watch(warpEntriesProvider);
    final store = ref.read(warpEntriesProvider.notifier);
    final isLoading = ref.watch(loadingProvider(LoadingTag.profiles));
    return CommonScaffold(
      title: l.warpGenerator,
      isLoading: isLoading,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          generateSectionV3(
            title: l.options,
            items: _optionItems(l),
            footer: _options.variant == WarpVariant.awg
                ? l.warpAwgDesc
                : l.warpGeneratorDesc,
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: isLoading
                ? null
                : () => _run(() async {
                    await store.generate(_options);
                  }),
            icon: const GlyphIcon(AppGlyphs.add, fill: 1),
            label: Text(l.warpGenerate),
          ),
          const SizedBox(height: 8),
          generateSectionV3(
            title: l.warpGenerated,
            items: entries.isEmpty
                ? [ListItem(title: Text(l.warpEmpty))]
                : [
                    for (final entry in entries)
                      _EntryItem(
                        entry: entry,
                        onRegenerate: () =>
                            unawaited(_run(() => store.regenerate(entry))),
                        onDelete: () =>
                            unawaited(_run(() => store.delete(entry))),
                      ),
                  ],
          ),
        ],
      ),
    );
  }
}

class _EntryItem extends StatelessWidget {
  const _EntryItem({
    required this.entry,
    required this.onRegenerate,
    required this.onDelete,
  });

  final WarpEntry entry;
  final VoidCallback onRegenerate;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final colorScheme = context.colorScheme;
    final remaining = entry.expiresAt.difference(DateTime.now());
    final days = remaining.inDays;
    final color = switch (days) {
      < 1 => colorScheme.error,
      < 7 => colorScheme.tertiary,
      _ => colorScheme.primary,
    };
    final fraction = remaining.inMinutes / WarpRegistration.lifetime.inMinutes;
    return ListItem(
      leading: const GlyphIcon(AppGlyphs.cloud),
      title: Text(entry.name),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            remaining.isNegative ? l.warpExpired : l.warpDaysLeft(days),
            style: context.textTheme.bodySmall?.copyWith(color: color),
          ),
          const SizedBox(height: 4),
          LinearProgressIndicator(
            minHeight: 4,
            value: fraction.clamp(0.0, 1.0),
            color: color,
            backgroundColor: color.opacity15,
          ),
        ],
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            tooltip: l.warpRegenerate,
            onPressed: onRegenerate,
            icon: const GlyphIcon(AppGlyphs.refresh),
          ),
          IconButton(
            tooltip: l.delete,
            onPressed: onDelete,
            icon: const GlyphIcon(AppGlyphs.delete),
          ),
        ],
      ),
    );
  }
}
