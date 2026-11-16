import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/views/dashboard/widget_registry.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';

/// Picks a card's width in cells of the grid it is shown in, and its rows.
class DashboardResizeDialog extends StatefulWidget {
  const DashboardResizeDialog({
    super.key,
    required this.initial,
    required this.gridColumns,
  });

  final DashboardCardDimensions initial;
  final int gridColumns;

  @override
  State<DashboardResizeDialog> createState() => _DashboardResizeDialogState();
}

class _DashboardResizeDialogState extends State<DashboardResizeDialog> {
  late final _widths = dashboardCardColumnsFor(widget.gridColumns);
  late int _columns = _nearest(widget.initial.columns);
  late int _rows = widget.initial.rows;

  int _nearest(int columns) => _widths.reduce(
    (a, b) => (a - columns).abs() <= (b - columns).abs() ? a : b,
  );

  String _fraction(int columns) {
    final divisor = columns.gcd(widget.gridColumns);
    final top = columns ~/ divisor;
    final bottom = widget.gridColumns ~/ divisor;
    return bottom == 1 ? '1' : '$top/$bottom';
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    return CommonDialog(
      title: l.dashboardResize,
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l.cancel),
        ),
        TextButton(
          onPressed: () =>
              Navigator.of(context).pop((columns: _columns, rows: _rows)),
          child: Text(l.confirm),
        ),
      ],
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  l.dashboardWidth,
                  style: context.textTheme.labelLarge,
                ),
              ),
              Text(_fraction(_columns), style: context.textTheme.labelLarge),
            ],
          ),
          Slider(
            value: _widths.indexOf(_columns).toDouble(),
            max: (_widths.length - 1).toDouble(),
            divisions: _widths.length - 1,
            label: _fraction(_columns),
            onChanged: (value) =>
                setState(() => _columns = _widths[value.round()]),
          ),
          const SizedBox(height: 16),
          Text(l.dashboardHeight, style: context.textTheme.labelLarge),
          const SizedBox(height: 8),
          SegmentedButton<int>(
            segments: [
              for (final rows in dashboardCardRows)
                ButtonSegment(value: rows, label: Text('$rows')),
            ],
            selected: {_rows},
            showSelectedIcon: false,
            onSelectionChanged: (value) => setState(() => _rows = value.first),
          ),
        ],
      ),
    );
  }
}
