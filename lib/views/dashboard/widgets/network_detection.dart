import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/providers/ip_details.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/views/dashboard/widget_metrics.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Where the connection reaches the internet from: flag, address (green while
/// no tunnel runs), country, place and ISP, wrapped to fit whatever size the
/// card is given. A tap shows everything the lookup found.
class NetworkDetection extends ConsumerWidget {
  const NetworkDetection({super.key});

  static const defaultRows = 1;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final details = ref.watch(ipDetailsProvider);
    final info = details.value;
    final direct = !ref.watch(isStartProvider);
    return SizedBox(
      height: DashboardWidgetMetrics.cardHeightOf(context, defaultRows),
      child: CommonCard(
        radius: DashboardWidgetMetrics.radiusOf(context),
        onPressed: info == null
            ? () => ref.read(ipDetailsProvider.notifier).refresh()
            : () => _showDetails(context, ref, info),
        child: Padding(
          padding: DashboardWidgetMetrics.paddingOf(context),
          child: FitWrapped(
            child: _Summary(
              info: info,
              direct: direct,
              loading: details.isLoading,
              failed: details.hasError,
            ),
          ),
        ),
      ),
    );
  }

  void _showDetails(BuildContext context, WidgetRef ref, IpDetails info) {
    final l = context.appLocalizations;
    dialogs.showCommonDialog<void>(
      child: Builder(
        builder: (dialogContext) => CommonDialog(
          title: l.networkDetection,
          actions: [
            TextButton(
              onPressed: () {
                Navigator.of(dialogContext).pop();
                ref.read(ipDetailsProvider.notifier).refresh();
              },
              child: Text(l.retry),
            ),
          ],
          child: _DetailsList(info: info),
        ),
      ),
    );
  }
}

/// Lays [child] out at the full width so its text wraps, then scales it down
/// when it is taller than the space: nothing is ever cut off.
class FitWrapped extends StatelessWidget {
  const FitWrapped({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (_, constraints) => FittedBox(
        fit: BoxFit.scaleDown,
        alignment: Alignment.centerLeft,
        child: SizedBox(width: constraints.maxWidth, child: child),
      ),
    );
  }
}

class _Summary extends StatelessWidget {
  const _Summary({
    required this.info,
    required this.direct,
    required this.loading,
    required this.failed,
  });

  final IpDetails? info;
  final bool direct;
  final bool loading;
  final bool failed;

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final colorScheme = context.colorScheme;
    final textTheme = context.textTheme;
    final info = this.info;
    final muted = textTheme.bodySmall?.copyWith(
      color: colorScheme.onSurfaceVariant,
    );
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        if (info != null)
          Text(
            info.countryCode.countryFlagEmoji,
            style: textTheme.titleLarge?.copyWith(
              fontFamily: FontFamily.twEmoji.value,
            ),
          )
        else
          GlyphIcon(
            AppGlyphs.networkCheck,
            color: colorScheme.onSurfaceVariant,
          ),
        const SizedBox(width: 12),
        Expanded(
          child: info == null
              ? Text(
                  failed ? l.serviceFailed : l.networkDetection,
                  style: textTheme.bodyMedium?.copyWith(
                    color: failed ? colorScheme.error : null,
                  ),
                )
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      info.ip,
                      style: textTheme.bodyMedium
                          ?.adjustSize(1)
                          .copyWith(
                            color: direct ? colorScheme.success : null,
                            fontWeight: FontWeight.w600,
                          ),
                    ),
                    Text(
                      [
                        ?info.country,
                        if (info.place.isNotEmpty) info.place,
                      ].join(' · '),
                      style: muted,
                    ),
                    if (info.isp != null) Text(info.isp!, style: muted),
                  ],
                ),
        ),
        if (loading) ...[
          const SizedBox(width: 8),
          const SizedBox.square(dimension: 16, child: CommonCircleLoading()),
        ],
      ],
    );
  }
}

class _DetailsList extends StatelessWidget {
  const _DetailsList({required this.info});

  final IpDetails info;

  @override
  Widget build(BuildContext context) {
    final rows = <(String, String)>[
      ('IP', info.ip),
      (
        'Country',
        '${info.countryCode.countryFlagEmoji} ${info.country ?? info.countryCode}',
      ),
      if (info.region != null) ('Region', info.region!),
      if (info.city != null) ('City', info.city!),
      if (info.isp != null) ('ISP', info.isp!),
      for (final MapEntry(:key, :value) in info.details.entries) (key, value),
    ];
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final (label, value) in rows)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: context.textTheme.labelMedium?.copyWith(
                    color: context.colorScheme.onSurfaceVariant,
                  ),
                ),
                SelectableText(value, style: context.textTheme.bodyLarge),
              ],
            ),
          ),
      ],
    );
  }
}
