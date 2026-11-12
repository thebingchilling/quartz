import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/providers/ip_details.dart';
import 'package:fl_clash/views/dashboard/widget_metrics.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Where the connection reaches the internet from: flag, country, IP, city and
/// ISP, connected or not. Tapping it looks again.
class NetworkDetection extends ConsumerWidget {
  const NetworkDetection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appLocalizations = context.appLocalizations;
    final colorScheme = context.colorScheme;
    final textTheme = context.textTheme;
    final details = ref.watch(ipDetailsProvider);
    final info = details.value;
    final padding = DashboardWidgetMetrics.paddingOf(context);
    return SizedBox(
      height: DashboardWidgetMetrics.heightOf(context, 2),
      child: CommonCard(
        radius: DashboardWidgetMetrics.radiusOf(context),
        onPressed: () => ref.read(ipDetailsProvider.notifier).refresh(),
        child: Padding(
          padding: padding,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  info != null
                      ? Text(
                          info.countryCode.countryFlagEmoji,
                          style: textTheme.titleLarge?.copyWith(
                            fontFamily: FontFamily.twEmoji.value,
                          ),
                        )
                      : GlyphIcon(
                          AppGlyphs.networkCheck,
                          color: colorScheme.onSurfaceVariant,
                        ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      info?.country ?? appLocalizations.networkDetection,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: context.sectionHeaderStyle,
                    ),
                  ),
                  if (details.isLoading)
                    const SizedBox.square(
                      dimension: 16,
                      child: CommonCircleLoading(),
                    ),
                ],
              ),
              const Spacer(),
              FadeThroughBox(
                child: info != null
                    ? Column(
                        key: ValueKey(info.ip),
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            info.ip,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: textTheme.titleLarge?.copyWith(
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 6),
                          _Line(glyph: AppGlyphs.locate, text: info.place),
                          _Line(glyph: AppGlyphs.dns, text: info.isp),
                        ],
                      )
                    : details.hasError
                    ? Text(
                        appLocalizations.serviceFailed,
                        style: textTheme.bodyMedium?.copyWith(
                          color: colorScheme.error,
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Line extends StatelessWidget {
  const _Line({required this.glyph, required this.text});

  final Glyph glyph;
  final String? text;

  @override
  Widget build(BuildContext context) {
    final value = text;
    if (value == null || value.isEmpty) return const SizedBox.shrink();
    final color = context.colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(top: 2),
      child: Row(
        children: [
          GlyphIcon(glyph, size: 14, color: color),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: context.textTheme.bodyMedium?.copyWith(color: color),
            ),
          ),
        ],
      ),
    );
  }
}
