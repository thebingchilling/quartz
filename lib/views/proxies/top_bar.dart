import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:material_ui/material_ui.dart';

/// A [bar] under the app bar for a profile Quartz manages, with the proxies
/// laid out below it rather than under the bar.
class ProxiesTopBar extends StatelessWidget {
  const ProxiesTopBar({super.key, required this.bar, required this.child});

  final Widget bar;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(height: context.appBarInset),
        Padding(padding: const EdgeInsets.fromLTRB(16, 8, 16, 0), child: bar),
        Expanded(
          child: MediaQuery.removePadding(
            context: context,
            removeTop: true,
            child: FloatingBarScope(inset: 0, child: child),
          ),
        ),
      ],
    );
  }
}
