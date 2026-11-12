import 'package:fl_clash/common/common.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'route_state.dart';
import 'state.dart';

/// The Network card's lookup, asked again when the app connects or
/// disconnects, the proxy picks change, or the card is tapped.
final ipDetailsProvider = AsyncNotifierProvider<IpDetailsNotifier, IpDetails>(
  IpDetailsNotifier.new,
);

class IpDetailsNotifier extends AsyncNotifier<IpDetails> {
  /// A tunnel that just came up can drop the first request.
  static const _settle = Duration(milliseconds: 800);

  @override
  Future<IpDetails> build() async {
    final started = ref.watch(isStartProvider);
    ref.watch(routeTrackerProvider.select((state) => state.picks));
    if (started) await Future<void>.delayed(_settle);
    return lookupIpDetails(request.getJsonThroughApp);
  }

  void refresh() => ref.invalidateSelf();
}
