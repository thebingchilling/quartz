import 'package:fl_clash/providers/app.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// A page's primary button: full width on a phone, its own width at the end
/// of the row on a wider window, where a stretched button reads as a banner.
class PageAction extends ConsumerWidget {
  const PageAction({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (ref.watch(isMobileViewProvider)) return child;
    return Align(alignment: AlignmentDirectional.centerEnd, child: child);
  }
}
