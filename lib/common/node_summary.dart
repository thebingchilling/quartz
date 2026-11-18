import 'dart:io';

import 'yaml.dart';

/// One condensed line for a proxy, from its settings in the config: the
/// protocol, then what tells nodes of that protocol apart, e.g.
/// `VLESS · Vision · Reality` or `Shadowsocks · rc4-md5`. Null for anything
/// that isn't a proxy.
String? nodeSummary(Map proxy) {
  final type = proxy['type'];
  if (type is! String) return null;
  String? text(String key) {
    final value = proxy[key];
    return value is String && value.isNotEmpty ? value : null;
  }

  bool on(String key) => proxy[key] == true;
  final parts = <String?>[];
  switch (type.toLowerCase()) {
    case 'ss':
      parts.addAll(['Shadowsocks', text('cipher'), text('plugin')]);
    case 'ssr':
      parts.addAll(['ShadowsocksR', text('cipher'), text('obfs')]);
    case 'vmess':
      parts.addAll(['VMess', _security(proxy), _network(proxy)]);
    case 'vless':
      final flow = text('flow');
      parts.addAll([
        'VLESS',
        if (flow != null && flow.contains('vision')) 'Vision',
        _security(proxy),
        _network(proxy),
      ]);
    case 'trojan':
      parts.addAll([
        'Trojan',
        _security(proxy, tlsByDefault: true),
        _network(proxy),
      ]);
    case 'wireguard':
      final awg = proxy['amnezia-wg-option'];
      parts.addAll([
        'WireGuard',
        if (awg is Map && awg.isNotEmpty) 'AmneziaWG',
      ]);
    case 'openvpn':
      final proto = text('proto')?.toUpperCase();
      final port = proxy['port'];
      parts.addAll([
        'OpenVPN',
        [?proto, if (port != null) '$port'].join(' '),
      ]);
    case 'hysteria2':
      parts.addAll(['Hysteria2', _capitalized(text('obfs'))]);
    case 'hysteria':
      parts.addAll(['Hysteria', text('protocol')?.toUpperCase()]);
    case 'tuic':
      parts.addAll(['TUIC', proxy['token'] != null ? 'v4' : 'v5']);
    case 'socks5':
      parts.addAll([
        'SOCKS5',
        if (text('username') != null) 'auth',
        if (on('tls')) 'TLS',
      ]);
    case 'http':
      parts.addAll([
        'HTTP',
        if (text('username') != null) 'auth',
        if (on('tls')) 'TLS',
      ]);
    case 'snell':
      final version = proxy['version'];
      parts.addAll(['Snell', if (version != null) 'v$version']);
    case 'anytls':
      parts.add('AnyTLS');
    case 'ssh':
      parts.add('SSH');
    case 'mieru':
      parts.add('Mieru');
    default:
      parts.add(_capitalized(type));
  }
  return parts.where((part) => part != null && part.isNotEmpty).join(' · ');
}

String? _security(Map proxy, {bool tlsByDefault = false}) {
  final reality = proxy['reality-opts'];
  if (reality is Map && reality.isNotEmpty) return 'Reality';
  if (proxy['tls'] == true || (tlsByDefault && proxy['tls'] != false)) {
    return 'TLS';
  }
  return null;
}

String? _network(Map proxy) => switch (proxy['network']) {
  'ws' => 'WS',
  'grpc' => 'gRPC',
  'h2' => 'H2',
  'http' => 'HTTP',
  'xhttp' => 'XHTTP',
  'httpupgrade' => 'HTTPUpgrade',
  'kcp' => 'mKCP',
  'quic' => 'QUIC',
  _ => null,
};

String? _capitalized(String? text) => text == null || text.isEmpty
    ? null
    : '${text[0].toUpperCase()}${text.substring(1)}';

/// The summaries of a config's proxies by name, and the provider files it
/// could not read as YAML (share links, which the Core converts).
typedef NodeSummaryScan = ({
  Map<String, String> summaries,
  List<NodeProviderFile> pending,
});

/// A provider's downloaded file and how the provider renames its proxies.
typedef NodeProviderFile = ({String path, Map<String, dynamic> override});

/// Reads the config the app applied, then each proxy provider's file.
NodeSummaryScan scanNodeSummaries(String configPath) {
  final summaries = <String, String>{};
  final pending = <NodeProviderFile>[];
  final config = _loadMap(configPath);
  if (config == null) return (summaries: summaries, pending: pending);
  addNodeSummaries(summaries, config['proxies']);
  final providers = config['proxy-providers'];
  if (providers is Map) {
    for (final provider in providers.values) {
      if (provider is! Map) continue;
      final override = provider['override'] is Map
          ? Map<String, dynamic>.from(provider['override'] as Map)
          : <String, dynamic>{};
      if (provider['type'] == 'inline') {
        addNodeSummaries(summaries, provider['payload'], override: override);
        continue;
      }
      final path = provider['path'];
      if (path is! String) continue;
      final file = _loadMap(path);
      if (file != null && file['proxies'] is List) {
        addNodeSummaries(summaries, file['proxies'], override: override);
      } else if (File(path).existsSync()) {
        pending.add((path: path, override: override));
      }
    }
  }
  return (summaries: summaries, pending: pending);
}

/// Adds [proxies] under the names a provider's [override] gives them.
void addNodeSummaries(
  Map<String, String> summaries,
  Object? proxies, {
  Map<String, dynamic> override = const {},
}) {
  if (proxies is! List) return;
  for (final proxy in proxies) {
    if (proxy is! Map || proxy['name'] is! String) continue;
    final summary = nodeSummary(proxy);
    if (summary == null) continue;
    summaries[_overriddenName(proxy['name'] as String, override)] = summary;
  }
}

String _overriddenName(String name, Map<String, dynamic> override) {
  var result = name;
  final renames = override['proxy-name'];
  if (renames is List) {
    for (final rename in renames) {
      if (rename is! Map || rename['pattern'] is! String) continue;
      try {
        result = result.replaceAll(
          RegExp(rename['pattern'] as String),
          '${rename['target'] ?? ''}',
        );
      } on FormatException {
        continue;
      }
    }
  }
  final prefix = override['additional-prefix'];
  final suffix = override['additional-suffix'];
  return '${prefix is String ? prefix : ''}$result'
      '${suffix is String ? suffix : ''}';
}

Map? _loadMap(String path) {
  try {
    final file = File(path);
    if (!file.existsSync()) return null;
    final data = loadPlainYaml(file.readAsStringSync());
    return data is Map ? data : null;
  } on Exception {
    return null;
  }
}
