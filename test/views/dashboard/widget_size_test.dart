import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/views/dashboard/widget_registry.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('a wider grid offers wider cards in the same two-cell steps', () {
    expect(dashboardCardColumnsFor(8), [2, 4, 6, 8]);
    expect(dashboardCardColumnsFor(16), [2, 4, 6, 8, 10, 12, 14, 16]);
  });

  test('a resized card spans the cells it was given on every grid', () {
    const sizes = {'runTime': '2,1'};
    const card = DashboardWidget.runTime;
    expect(card.sizedWidget(sizes, 8).crossAxisCellCount, 2);
    expect(card.sizedWidget(sizes, 16).crossAxisCellCount, 2);
    expect(
      card.sizedWidget(const {'runTime': '16,1'}, 8).crossAxisCellCount,
      8,
    );
  });

  test('an unresized card keeps its default span', () {
    const card = DashboardWidget.runTime;
    expect(
      card.sizedWidget(const {}, 16).crossAxisCellCount,
      card.widget.crossAxisCellCount,
    );
  });
}
