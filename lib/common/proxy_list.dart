/// A bare list of SOCKS5 servers as free proxy lists publish them: lines of
/// `host:port`, `host:port:user:pass` or `user:pass@host:port`. Null when any
/// other line shows the text is something else.
List<Map<String, dynamic>>? parseBareSocksList(String text) {
  final proxies = <Map<String, dynamic>>[];
  final seen = <String>{};
  for (final raw in text.split('\n')) {
    final line = raw.trim();
    if (line.isEmpty || line.startsWith('#')) continue;
    final proxy = _parseBareSocks(line);
    if (proxy == null) return null;
    final host = proxy['server'] as String;
    final name = '${host.contains(':') ? '[$host]' : host}:${proxy['port']}';
    if (!seen.add(name)) continue;
    proxies.add({'name': name, ...proxy});
  }
  return proxies.isEmpty ? null : proxies;
}

Map<String, dynamic>? _parseBareSocks(String line) {
  String? user;
  String? pass;
  var address = line;
  final at = line.lastIndexOf('@');
  if (at >= 0) {
    final credentials = line.substring(0, at);
    final colon = credentials.indexOf(':');
    if (colon < 0) return null;
    user = credentials.substring(0, colon);
    pass = credentials.substring(colon + 1);
    address = line.substring(at + 1);
  } else if (!line.startsWith('[') && ':'.allMatches(line).length == 3) {
    final parts = line.split(':');
    address = '${parts[0]}:${parts[1]}';
    user = parts[2];
    pass = parts[3];
  }
  final String host;
  final String portText;
  if (address.startsWith('[')) {
    final close = address.indexOf(']:');
    if (close < 0) return null;
    host = address.substring(1, close);
    portText = address.substring(close + 2);
  } else {
    final colon = address.lastIndexOf(':');
    if (colon <= 0 || address.indexOf(':') != colon) return null;
    host = address.substring(0, colon);
    portText = address.substring(colon + 1);
  }
  final port = int.tryParse(portText);
  if (host.isEmpty ||
      host.contains(RegExp(r'[\s/]')) ||
      port == null ||
      port < 1 ||
      port > 65535) {
    return null;
  }
  return {
    'type': 'socks5',
    'server': host,
    'port': port,
    'udp': true,
    if (user != null && user.isNotEmpty) ...{
      'username': user,
      'password': pass,
    },
  };
}

/// The proxies whose `dialer-proxy` chain leads back to themselves, through
/// other proxies' dialers or a group's members. mihomo follows such a chain
/// on every dial until its stack overflows, which kills the whole app.
Set<String> loopingDialers(Object? config) {
  if (config is! Map) return const {};
  final dialers = <String, String>{};
  for (final proxy in (config['proxies'] as List?) ?? const []) {
    if (proxy is Map &&
        proxy['name'] is String &&
        proxy['dialer-proxy'] is String) {
      dialers[proxy['name'] as String] = proxy['dialer-proxy'] as String;
    }
  }
  if (dialers.isEmpty) return const {};
  final members = <String, List<String>>{
    for (final group in (config['proxy-groups'] as List?) ?? const [])
      if (group is Map && group['name'] is String)
        group['name'] as String: [
          for (final member in (group['proxies'] as List?) ?? const [])
            if (member is String) member,
        ],
  };
  final looping = <String>{};
  for (final MapEntry(key: name, value: dialer) in dialers.entries) {
    final seen = <String>{};
    final queue = [dialer];
    while (queue.isNotEmpty) {
      final next = queue.removeLast();
      if (next == name) {
        looping.add(name);
        break;
      }
      if (!seen.add(next)) continue;
      queue
        ..addAll([?dialers[next]])
        ..addAll(members[next] ?? const []);
    }
  }
  return looping;
}

/// [config] with the looping dialers dropped, the way the profile editor
/// already treats its own proxies.
void dropLoopingDialers(Map config) {
  final looping = loopingDialers(config);
  if (looping.isEmpty) return;
  config['proxies'] = [
    for (final proxy in config['proxies'] as List)
      if (proxy is Map && looping.contains(proxy['name']))
        Map.of(proxy)..remove('dialer-proxy')
      else
        proxy,
  ];
}
