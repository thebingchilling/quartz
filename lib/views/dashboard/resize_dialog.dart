import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/views/dashboard/widget_registry.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';

/// Picks a card's width (a quarter to the full row) and height (one to four
/// rows); a quarter wide and one row high is a square tile.
class DashboardResizeDialog extends StatefulWidget {
  const DashboardResizeDialog({super.key, required this.initial});

  final DashboardCardDimensions initial;

  @override
  State<DashboardResizeDialog> createState() => _DashboardResizeDialogState();
}

class _DashboardResizeDialogState extends State<DashboardResizeDialog> {
  late int _columns = widget.initial.columns;
  late int _rows = widget.initial.rows;

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
          Text(l.dashboardWidth, style: context.textTheme.labelLarge),
          const SizedBox(height: 8),
          SegmentedButton<int>(
            segments: [
              for (final columns in dashboardCardColumns)
                ButtonSegment(value: columns, label: Text('$columns/8')),
            ],
            selected: {_columns},
            showSelectedIcon: false,
            onSelectionChanged: (value) =>
                setState(() => _columns = value.first),
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
