import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'route_state.dart';
import 'state.dart';

/// Moves when the Core's listener has really started or stopped, or a profile
/// finished applying while it runs. `isStart` flips the moment the user asks,
/// before the mixed port answers, so a lookup keyed on it alone went out too
/// early and the card held the old address until a later retry won.
final routeEpochProvider = NotifierProvider<RouteEpoch, int>(RouteEpoch.new);

class RouteEpoch extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state++;
}

/// The Network card's lookup, asked again when the route out changes, the
/// proxy picks change, or the card is tapped.
final ipDetailsProvider = AsyncNotifierProvider<IpDetailsNotifier, IpDetails>(
  IpDetailsNotifier.new,
);

class IpDetailsNotifier extends AsyncNotifier<IpDetails> {
  static const _retryDelays = [
    Duration(milliseconds: 400),
    Duration(milliseconds: 1200),
  ];

  /// A start or stop that never moves the epoch, such as one Android's service
  /// made by itself, would otherwise leave the card loading for good.
  static const _settleTimeout = Duration(seconds: 6);

  @override
  Future<IpDetails> build() async {
    ref.watch(routeEpochProvider);
    ref.watch(routeTrackerProvider.select((state) => state.picks));
    Timer? settle;
    ref.onDispose(() => settle?.cancel());
    ref.listen(isStartProvider, (_, _) {
      state = const AsyncLoading();
      settle?.cancel();
      settle = Timer(_settleTimeout, () {
        if (ref.mounted && state.isLoading) ref.invalidateSelf();
      });
    });
    var attempt = 0;
    while (true) {
      final started = ref.read(isStartProvider);
      try {
        final details = await lookupIpDetails(request.getJsonThroughApp);
        if (!ref.mounted || ref.read(isStartProvider) == started) {
          return details;
        }
      } catch (_) {
        if (!ref.mounted || attempt >= _retryDelays.length) rethrow;
        await Future<void>.delayed(_retryDelays[attempt++]);
      }
    }
  }

  void refresh() => ref.invalidateSelf();
}
