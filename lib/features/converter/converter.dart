/// Turns WireGuard (`.conf`, AmneziaWG included) and OpenVPN (`.ovpn`)
/// configs into mihomo proxies, so any of them runs as a Clash profile.
library;

import 'dart:io';

class ConvertException implements Exception {
  const ConvertException(this.message);

  final String message;

  @override
  String toString() => message;
}

enum ConfigKind { wireguard, openvpn }

/// One converted config: [proxies] are YAML list items (two-space indented),
/// [names] the proxy names they define.
class ConvertedConfig {
  const ConvertedConfig({
    required this.kind,
    required this.names,
    required this.proxies,
    this.needsCredentials = false,
  });

  final ConfigKind kind;
  final List<String> names;
  final List<String> proxies;

  /// OpenVPN asks for a username and password (`auth-user-pass`) that the
  /// file doesn't carry.
  final bool needsCredentials;
}

ConfigKind? detectConfigKind(String text) {
  if (RegExp(
    r'^\s*\[Interface\]',
    multiLine: true,
    caseSensitive: false,
  ).hasMatch(text)) {
    return ConfigKind.wireguard;
  }
  if (RegExp(
    r'^\s*(remote|client|dev\s+tun)\b',
    multiLine: true,
  ).hasMatch(text)) {
    return ConfigKind.openvpn;
  }
  return null;
}

ConvertedConfig convertConfig(
  String text, {
  required String name,
  String username = '',
  String password = '',
}) {
  return switch (detectConfigKind(text)) {
    ConfigKind.wireguard => convertWireGuard(text, name: name),
    ConfigKind.openvpn => convertOpenVpn(
      text,
      name: name,
      username: username,
      password: password,
    ),
    null => throw const ConvertException('Not a WireGuard or OpenVPN config'),
  };
}

String quoteYaml(String value) =>
    '"${value.replaceAll(r'\', r'\\').replaceAll('"', r'\"')}"';

String _list(Iterable<String> values) =>
    '[${values.map(quoteYaml).join(', ')}]';

List<String> _split(String value) => [
  for (final part in value.split(','))
    if (part.trim().isNotEmpty) part.trim(),
];

(String, int) _endpoint(String value, int fallbackPort) {
  final text = value.trim();
  final bracket = RegExp(r'^\[([^\]]+)\](?::(\d+))?$').firstMatch(text);
  if (bracket != null) {
    return (
      bracket.group(1)!,
      int.tryParse(bracket.group(2) ?? '') ?? fallbackPort,
    );
  }
  final colon = text.lastIndexOf(':');
  if (colon > 0 && text.indexOf(':') == colon) {
    return (
      text.substring(0, colon),
      int.tryParse(text.substring(colon + 1)) ?? fallbackPort,
    );
  }
  return (text, fallbackPort);
}

// ---------------------------------------------------------------- WireGuard

ConvertedConfig convertWireGuard(String text, {required String name}) {
  final sections = <(String, Map<String, String>)>[];
  for (final raw in text.split('\n')) {
    final line = raw.split('#').first.trim();
    if (line.isEmpty) continue;
    final header = RegExp(r'^\[(\w+)\]$').firstMatch(line);
    if (header != null) {
      sections.add((header.group(1)!.toLowerCase(), {}));
      continue;
    }
    final eq = line.indexOf('=');
    if (eq <= 0 || sections.isEmpty) continue;
    final key = line.substring(0, eq).trim().toLowerCase();
    final value = line.substring(eq + 1).trim();
    final values = sections.last.$2;
    values[key] = values.containsKey(key) ? '${values[key]}, $value' : value;
  }
  final iface = [
    for (final (kind, values) in sections)
      if (kind == 'interface') values,
  ].firstOrNull;
  final peers = [
    for (final (kind, values) in sections)
      if (kind == 'peer') values,
  ];
  if (iface == null || iface['privatekey'] == null) {
    throw const ConvertException(
      'WireGuard config has no [Interface] PrivateKey',
    );
  }
  if (peers.isEmpty) {
    throw const ConvertException('WireGuard config has no [Peer]');
  }
  final addresses = _split(
    iface['address'] ?? '',
  ).map((a) => a.split('/').first).toList();
  final v4 = addresses.where((a) => !a.contains(':')).firstOrNull;
  final v6 = addresses.where((a) => a.contains(':')).firstOrNull;
  final dns = [
    for (final entry in _split(iface['dns'] ?? ''))
      if (InternetAddress.tryParse(entry) != null) entry,
  ];

  List<String> peerLines(Map<String, String> peer, String indent) {
    final endpoint = peer['endpoint'];
    if (endpoint == null) {
      throw const ConvertException('A WireGuard [Peer] has no Endpoint');
    }
    final (server, port) = _endpoint(endpoint, 51820);
    final reserved = peer['reserved'] ?? iface['reserved'];
    return [
      '${indent}server: ${quoteYaml(server)}',
      '${indent}port: $port',
      '${indent}public-key: ${quoteYaml(peer['publickey'] ?? '')}',
      if (peer['presharedkey'] case final psk?)
        '${indent}pre-shared-key: ${quoteYaml(psk)}',
      '${indent}allowed-ips: ${_list(_split(peer['allowedips'] ?? '0.0.0.0/0, ::/0'))}',
      if (reserved != null)
        '${indent}reserved: [${_split(reserved).join(', ')}]',
    ];
  }

  final lines = <String>[
    '  - name: ${quoteYaml(name)}',
    '    type: wireguard',
    if (peers.length == 1) ...peerLines(peers.single, '    '),
    if (v4 != null) '    ip: $v4',
    if (v6 != null) '    ipv6: ${quoteYaml(v6)}',
    '    private-key: ${quoteYaml(iface['privatekey']!)}',
    if (int.tryParse(iface['mtu'] ?? '') case final mtu?) '    mtu: $mtu',
    // Search domains share the DNS line; only the servers are resolvers.
    if (dns.isNotEmpty) ...[
      '    remote-dns-resolve: true',
      '    dns: ${_list(dns)}',
    ],
    if (int.tryParse(peers.first['persistentkeepalive'] ?? '')
        case final keepalive? when keepalive > 0)
      '    persistent-keepalive: $keepalive',
    '    udp: true',
    if (peers.length > 1) ...[
      '    peers:',
      for (final peer in peers) ...[
        ...peerLines(peer, '        ').indexed.map(
          (entry) =>
              entry.$1 == 0 ? '      - ${entry.$2.trimLeft()}' : entry.$2,
        ),
      ],
    ],
    ..._amnezia(iface),
  ];
  return ConvertedConfig(
    kind: ConfigKind.wireguard,
    names: [name],
    proxies: [lines.join('\n')],
  );
}

const _amneziaInts = ['jc', 'jmin', 'jmax', 's1', 's2', 's3', 's4'];
const _amneziaStrings = ['h1', 'h2', 'h3', 'h4', 'i1', 'i2', 'i3', 'i4', 'i5'];

List<String> _amnezia(Map<String, String> iface) {
  final present = [
    ..._amneziaInts,
    ..._amneziaStrings,
  ].where((key) => (iface[key] ?? '').isNotEmpty && iface[key] != '0');
  if (present.isEmpty) return const [];
  return [
    '    amnezia-wg-option:',
    for (final key in _amneziaInts)
      if (int.tryParse(iface[key] ?? '') case final value?)
        '      $key: $value',
    for (final key in _amneziaStrings)
      if (iface[key] case final value? when value.isNotEmpty)
        '      $key: ${quoteYaml(value)}',
  ];
}

// ------------------------------------------------------------------ OpenVPN

ConvertedConfig convertOpenVpn(
  String text, {
  required String name,
  String username = '',
  String password = '',
}) {
  final blocks = <String, String>{};
  final directives = <(String, List<String>)>[];
  final blockPattern = RegExp(r'<([\w-]+)>([\s\S]*?)</\1>');
  for (final match in blockPattern.allMatches(text)) {
    blocks[match.group(1)!.toLowerCase()] = match.group(2)!.trim();
  }
  for (final raw in text.replaceAll(blockPattern, '').split('\n')) {
    final line = raw.trim();
    if (line.isEmpty || line.startsWith('#') || line.startsWith(';')) continue;
    final parts = line.split(RegExp(r'\s+'));
    directives.add((parts.first.toLowerCase(), parts.skip(1).toList()));
  }
  List<String>? first(String key) =>
      directives.where((d) => d.$1 == key).map((d) => d.$2).firstOrNull;

  final defaultProto = _proto(first('proto')?.firstOrNull ?? 'udp');
  final defaultPort = int.tryParse(first('port')?.firstOrNull ?? '') ?? 1194;
  final remotes = [
    for (final (key, args) in directives)
      if (key == 'remote' && args.isNotEmpty)
        (
          args[0],
          int.tryParse(args.elementAtOrNull(1) ?? '') ?? defaultPort,
          args.length > 2 ? _proto(args[2]) : defaultProto,
        ),
  ];
  if (remotes.isEmpty) {
    throw const ConvertException('OpenVPN config has no remote');
  }
  final ca = blocks['ca'];
  if (ca == null) {
    throw ConvertException(
      first('ca') == null
          ? 'OpenVPN config has no <ca>'
          : 'OpenVPN config points to the file ${first('ca')!.first}: '
                'paste the config with its certificates inline',
    );
  }

  var user = username;
  var pass = password;
  if (blocks['auth-user-pass'] case final inline?) {
    final lines = inline.split('\n').map((l) => l.trim()).toList();
    if (user.isEmpty) user = lines.elementAtOrNull(0) ?? '';
    if (pass.isEmpty) pass = lines.elementAtOrNull(1) ?? '';
  }
  final needsCredentials =
      first('auth-user-pass') != null && (user.isEmpty || pass.isEmpty);

  final tlsAuthArgs = first('tls-auth');
  // `tls-auth <file|inline> 1` carries the direction as its last argument.
  final inlineDirection = tlsAuthArgs?.lastOrNull;
  final keyDirection =
      first('key-direction')?.firstOrNull ??
      (inlineDirection == '0' || inlineDirection == '1'
          ? inlineDirection
          : null);
  final dataCiphers = first('data-ciphers') ?? first('ncp-ciphers');
  final compLzo = first('comp-lzo');

  String block(String key, String value) => [
    '    $key: |',
    for (final line in value.split('\n')) '      ${line.trim()}',
  ].join('\n');

  final names = <String>[];
  final proxies = <String>[];
  for (final (index, (host, port, proto)) in remotes.indexed) {
    final proxyName = remotes.length == 1 ? name : '$name ${index + 1}';
    names.add(proxyName);
    proxies.add(
      [
        '  - name: ${quoteYaml(proxyName)}',
        '    type: openvpn',
        '    server: ${quoteYaml(host)}',
        '    port: $port',
        '    proto: $proto',
        if (user.isNotEmpty) '    username: ${quoteYaml(user)}',
        if (pass.isNotEmpty) '    password: ${quoteYaml(pass)}',
        if (first('cipher')?.firstOrNull case final cipher?)
          '    cipher: $cipher',
        if (dataCiphers?.firstOrNull case final ciphers?)
          '    data-ciphers: [${ciphers.split(':').join(', ')}]',
        if (first('data-ciphers-fallback')?.firstOrNull case final fallback?)
          '    data-ciphers-fallback: $fallback',
        if (first('auth')?.firstOrNull case final auth?) '    auth: $auth',
        if (compLzo != null)
          '    comp-lzo: ${quoteYaml(compLzo.firstOrNull ?? 'adaptive')}',
        if (keyDirection != null) '    key-direction: "$keyDirection"',
        '    udp: true',
        block('ca', ca),
        if (blocks['cert'] case final cert?) block('cert', cert),
        if (blocks['key'] case final key?) block('key', key),
        if (blocks['tls-auth'] case final tlsAuth?) block('tls-auth', tlsAuth),
        if (blocks['tls-crypt'] case final tlsCrypt?)
          block('tls-crypt', tlsCrypt),
        if (blocks['tls-crypt-v2'] case final tlsCryptV2?)
          block('tls-crypt-v2', tlsCryptV2),
      ].join('\n'),
    );
  }
  return ConvertedConfig(
    kind: ConfigKind.openvpn,
    names: names,
    proxies: proxies,
    needsCredentials: needsCredentials,
  );
}

String _proto(String value) =>
    value.toLowerCase().startsWith('tcp') ? 'tcp' : 'udp';

/// A Clash profile holding [configs]: a select group of every proxy, an
/// automatic group when there's more than one, and everything sent through it.
String buildConvertedProfile(List<ConvertedConfig> configs) {
  final names = [for (final config in configs) ...config.names];
  if (names.isEmpty) {
    throw const ConvertException('Nothing to convert');
  }
  const group = 'PROXY';
  const auto = 'Auto';
  return [
    'proxies:',
    for (final config in configs) ...config.proxies,
    'proxy-groups:',
    '  - name: $group',
    '    type: select',
    '    proxies: ${_list([if (names.length > 1) auto, ...names])}',
    if (names.length > 1) ...[
      '  - name: $auto',
      '    type: url-test',
      '    url: https://cp.cloudflare.com/generate_204',
      '    interval: 600',
      '    lazy: true',
      '    proxies: ${_list(names)}',
    ],
    'rules:',
    '  - MATCH,$group',
    '',
  ].join('\n');
}
