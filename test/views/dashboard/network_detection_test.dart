import 'package:fl_clash/common/ip_details.dart';
import 'package:fl_clash/providers/app.dart';
import 'package:fl_clash/providers/ip_details.dart';
import 'package:fl_clash/views/dashboard/widget_metrics.dart';
import 'package:fl_clash/views/dashboard/widgets/network_detection.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../helpers/test_app.dart';

const _details = IpDetails(
  ip: '203.0.113.9',
  countryCode: 'GB',
  country: 'United Kingdom',
  city: 'London',
  region: 'England',
  isp: 'Example Broadband',
);

class _FakeDetails extends IpDetailsNotifier {
  _FakeDetails(this.answer);

  final Future<IpDetails> Function() answer;
  int lookups = 0;

  @override
  Future<IpDetails> build() {
    lookups++;
    return answer();
  }
}

Widget _app(_FakeDetails details) => TestApp(
  locale: const Locale('en'),
  overrides: [
    ipDetailsProvider.overrideWith(() => details),
    viewSizeProvider.overrideWithBuild((_, _) => const Size(400, 900)),
  ],
  child: Scaffold(
    body: Center(
      child: SizedBox(
        width: 260,
        child: DashboardWidgetMetrics(
          unitHeight: 84,
          child: const NetworkDetection(),
        ),
      ),
    ),
  ),
);

void main() {
  testWidgets('shows country, address, place and ISP', (tester) async {
    await tester.pumpWidget(_app(_FakeDetails(() async => _details)));
    await tester.pumpAndSettle();

    expect(find.text('203.0.113.9'), findsOneWidget);
    expect(find.text('United Kingdom · London, England'), findsOneWidget);
    expect(find.text('Example Broadband'), findsOneWidget);
  });

  testWidgets('says so when no lookup answers', (tester) async {
    await tester.pumpWidget(
      _app(_FakeDetails(() async => throw const FormatException('down'))),
    );
    await tester.pumpAndSettle();

    expect(find.text('Check failed'), findsOneWidget);
  });

  testWidgets('a tap shows every detail', (tester) async {
    await tester.pumpWidget(_app(_FakeDetails(() async => _details)));
    await tester.pumpAndSettle();

    await tester.tap(find.text('203.0.113.9'));
    await tester.pumpAndSettle();

    expect(find.text('ISP'), findsOneWidget);
    expect(find.text('London'), findsOneWidget);
  });

  testWidgets('a failed lookup looks again on a tap', (tester) async {
    var calls = 0;
    final details = _FakeDetails(() async {
      calls++;
      throw const FormatException('down');
    });
    await tester.pumpWidget(_app(details));
    await tester.pumpAndSettle();
    final before = calls;

    await tester.tap(find.text('Check failed'));
    await tester.pumpAndSettle();

    expect(calls, greaterThan(before));
  });
}
