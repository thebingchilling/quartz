import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';

/// Windows' per-app bypass as mihomo rules: Android does the same in its
/// VPN service. Programs are matched by file name, since paths with
/// parentheses ("Program Files (x86)") break mihomo's logic rules.
List<String> appBypassRules(AccessControlProps props) {
  if (!props.enable) return const [];
  final whitelist = props.mode == AccessControlMode.acceptSelected;
  final names = {
    for (final app in whitelist ? props.acceptList : props.rejectList)
      if (_nameOf(app) case final name? when !name.contains(RegExp('[,()]')))
        name,
  }.toList();
  if (names.isEmpty) return const [];
  if (!whitelist) {
    return [for (final name in names) 'PROCESS-NAME,$name,DIRECT'];
  }
  final match = names.length == 1
      ? '(PROCESS-NAME,${names.single})'
      : '(OR,(${names.map((name) => '(PROCESS-NAME,$name)').join(',')}))';
  return ['NOT,($match),DIRECT'];
}

String? _nameOf(String app) {
  final name = app.split(RegExp(r'[\\/]')).last.trim();
  return name.isEmpty ? null : name;
}
