import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/views/dashboard/widget_metrics.dart';
import 'package:fl_clash/views/dashboard/widgets/widgets.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';

extension DashboardWidgetView on DashboardWidget {
  GridItem get widget => switch (this) {
    DashboardWidget.networkSpeed => const GridItem(
      key: ValueKey(DashboardWidget.networkSpeed),
      crossAxisCellCount: 8,
      child: NetworkSpeed(),
    ),
    DashboardWidget.outboundMode => const GridItem(
      key: ValueKey(DashboardWidget.outboundMode),
      crossAxisCellCount: 4,
      child: OutboundMode(),
    ),
    DashboardWidget.trafficUsage => const GridItem(
      key: ValueKey(DashboardWidget.trafficUsage),
      crossAxisCellCount: 4,
      child: TrafficUsage(),
    ),
    DashboardWidget.networkDetection => const GridItem(
      key: ValueKey(DashboardWidget.networkDetection),
      crossAxisCellCount: 8,
      child: NetworkDetection(),
    ),
    DashboardWidget.tunButton => const GridItem(
      key: ValueKey(DashboardWidget.tunButton),
      crossAxisCellCount: 4,
      child: TUNButton(),
    ),
    DashboardWidget.vpnButton => const GridItem(
      key: ValueKey(DashboardWidget.vpnButton),
      crossAxisCellCount: 4,
      child: VpnButton(),
    ),
    DashboardWidget.systemProxyButton => const GridItem(
      key: ValueKey(DashboardWidget.systemProxyButton),
      crossAxisCellCount: 4,
      child: SystemProxyButton(),
    ),
    DashboardWidget.intranetIp => const GridItem(
      key: ValueKey(DashboardWidget.intranetIp),
      crossAxisCellCount: 4,
      child: IntranetIP(),
    ),
    DashboardWidget.memoryInfo => const GridItem(
      key: ValueKey(DashboardWidget.memoryInfo),
      crossAxisCellCount: 4,
      child: MemoryInfo(),
    ),
    DashboardWidget.serviceStatus => const GridItem(
      key: ValueKey(DashboardWidget.serviceStatus),
      crossAxisCellCount: 8,
      child: ServiceStatusCard(),
    ),
    DashboardWidget.dnsQueries => const GridItem(
      key: ValueKey(DashboardWidget.dnsQueries),
      crossAxisCellCount: 4,
      child: DnsQueriesCard(),
    ),
    DashboardWidget.requests => const GridItem(
      key: ValueKey(DashboardWidget.requests),
      crossAxisCellCount: 4,
      child: RequestsCard(),
    ),
    DashboardWidget.connections => const GridItem(
      key: ValueKey(DashboardWidget.connections),
      crossAxisCellCount: 4,
      child: ConnectionsCard(),
    ),
    DashboardWidget.runTime => const GridItem(
      key: ValueKey(DashboardWidget.runTime),
      crossAxisCellCount: 4,
      child: RunTimeCard(),
    ),
    DashboardWidget.proxyGroups => const GridItem(
      key: ValueKey(DashboardWidget.proxyGroups),
      crossAxisCellCount: 4,
      child: ProxyGroupsCard(),
    ),
    DashboardWidget.profiles => const GridItem(
      key: ValueKey(DashboardWidget.profiles),
      crossAxisCellCount: 4,
      child: ProfilesCard(),
    ),
  };
}

/// The rows each card is drawn with when it hasn't been resized.
const _defaultRows = {
  DashboardWidget.networkSpeed: 2,
  DashboardWidget.outboundMode: 2,
  DashboardWidget.trafficUsage: 2,
  DashboardWidget.proxyGroups: 2,
};

/// A resize: [columns] in grid cells, the unit of a card's default
/// `crossAxisCellCount`, and [rows] in card rows.
typedef DashboardCardDimensions = ({int columns, int rows});

/// The widths a card can take in a grid of [gridColumns]: every second cell,
/// so a wider window offers wider cards rather than coarser steps.
List<int> dashboardCardColumnsFor(int gridColumns) => [
  for (var columns = 2; columns <= gridColumns; columns += 2) columns,
];

const dashboardCardRows = [1, 2, 3, 4];

extension DashboardWidgetSize on DashboardWidget {
  DashboardCardDimensions get defaultSize =>
      (columns: widget.crossAxisCellCount, rows: _defaultRows[this] ?? 1);

  DashboardCardDimensions sizeIn(Map<String, String> sizes) {
    final parts = sizes[name]?.split(',');
    final columns = int.tryParse(parts?.first ?? '');
    final rows = int.tryParse(parts?.last ?? '');
    if (columns == null || rows == null) return defaultSize;
    return (columns: columns, rows: rows);
  }

  /// The card at its saved size in a grid of [gridColumns].
  GridItem sizedWidget(Map<String, String> sizes, int gridColumns) {
    final size = sizeIn(sizes);
    if (size == defaultSize) return widget;
    final span = size.columns.clamp(1, gridColumns);
    return GridItem(
      key: widget.key,
      crossAxisCellCount: span,
      child: _ResizedCard(
        size: size,
        defaultSize: defaultSize,
        child: widget.child,
      ),
    );
  }
}

/// A card bigger than its default fills the cell; a smaller one is laid out
/// at its default size and scaled down, so nothing on it is ever cut off.
class _ResizedCard extends StatelessWidget {
  const _ResizedCard({
    required this.size,
    required this.defaultSize,
    required this.child,
  });

  final DashboardCardDimensions size;
  final DashboardCardDimensions defaultSize;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final rows = size.rows < defaultSize.rows ? defaultSize.rows : size.rows;
    return SizedBox(
      height: DashboardWidgetMetrics.heightOf(context, size.rows),
      child: LayoutBuilder(
        builder: (_, constraints) {
          final width = size.columns < defaultSize.columns
              ? constraints.maxWidth * defaultSize.columns / size.columns
              : constraints.maxWidth;
          return FittedBox(
            fit: BoxFit.scaleDown,
            child: SizedBox(
              width: width,
              child: DashboardCardSize(rows: rows, child: child),
            ),
          );
        },
      ),
    );
  }
}

DashboardWidget dashboardWidgetOf(GridItem gridItem) {
  return (gridItem.key! as ValueKey<DashboardWidget>).value;
}
