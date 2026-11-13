import 'package:dio/dio.dart';

import 'windscribe_keys.dart';

class WindscribeServer {
  const WindscribeServer({
    required this.country,
    required this.countryCode,
    required this.name,
    required this.datacenterId,
    required this.iata,
    required this.premium,
    required this.usage,
  });

  final String country;
  final String countryCode;

  /// The city and node, e.g. "Adelaide - Lofty".
  final String name;
  final int datacenterId;
  final String iata;
  final bool premium;

  final double usage;

  String get host => '${iata.toLowerCase()}-$datacenterId.whiskergalaxy.com';

  String get label => '$countryCode $name';
}

enum WindscribeProto {
  udp([443, 80, 53, 123, 1194, 54783]),
  tcp([443, 587, 21, 22, 80, 123, 143, 3306, 8080, 54783, 1194]);

  const WindscribeProto(this.ports);

  final List<int> ports;
}

/// Windscribe's public server list: the same request its website makes, with
/// the fixed token it uses for public data, so no account login is needed.
class WindscribeApi {
  WindscribeApi(this._dio);

  final Dio _dio;

  static const _url = 'https://api.windscribe.com/WebsiteStatus';

  Future<List<WindscribeServer>> servers() async {
    final response = await _dio.get<Map<String, dynamic>>(
      _url,
      options: Options(
        headers: {'Authorization': 'Bearer 1234'},
        responseType: ResponseType.json,
        receiveTimeout: const Duration(seconds: 20),
      ),
    );
    return parseServers(response.data ?? const {});
  }

  static List<WindscribeServer> parseServers(Map<String, dynamic> json) {
    final data = json['data'];
    final locations = data is Map ? data['locations'] : null;
    if (locations is! Map) {
      throw const FormatException('No locations in the server list');
    }
    final servers = <WindscribeServer>[];
    for (final location in locations.values.whereType<Map>()) {
      final pops = location['pops'];
      if (pops is! List) continue;
      for (final pop in pops.whereType<Map>()) {
        final id = pop['datacenter_id'];
        final iata = pop['iata_code'];
        if (pop['status'] != 1 || id is! int || iata is! String) continue;
        servers.add(
          WindscribeServer(
            country: '${location['name'] ?? ''}',
            countryCode: '${location['country_code'] ?? ''}',
            name: '${pop['name'] ?? iata}',
            datacenterId: id,
            iata: iata,
            premium: pop['is_premium'] == 1,
            usage: (pop['usage'] as num?)?.toDouble() ?? 0,
          ),
        );
      }
    }
    servers.sort(
      (a, b) => a.country == b.country
          ? a.name.compareTo(b.name)
          : a.country.compareTo(b.country),
    );
    return servers;
  }
}

String _quote(String value) =>
    '"${value.replaceAll(r'\', r'\\').replaceAll('"', r'\"')}"';

String _block(String text, String indent) =>
    text.trim().split('\n').map((line) => '$indent${line.trim()}').join('\n');

/// Every server as a mihomo `openvpn` proxy, grouped by country.
String buildWindscribeProfile({
  required List<WindscribeServer> servers,
  required String username,
  required String password,
  required WindscribeProto proto,
  required int port,
}) {
  final seen = <String>{};
  String unique(String label) {
    var name = label;
    for (var n = 2; !seen.add(name); n++) {
      name = '$label $n';
    }
    return name;
  }

  final named = [for (final server in servers) (server, unique(server.label))];
  final countries = <String, List<String>>{};
  for (final (server, name) in named) {
    countries.putIfAbsent(server.country, () => []).add(name);
  }
  const auto = 'Windscribe Auto';
  final lines = <String>['proxies:'];
  for (final (server, name) in named) {
    lines.addAll([
      '  - name: ${_quote(name)}',
      '    type: openvpn',
      '    server: ${server.host}',
      '    port: $port',
      '    proto: ${proto.name}',
      '    username: ${_quote(username)}',
      '    password: ${_quote(password)}',
      '    cipher: AES-256-GCM',
      '    data-ciphers: [AES-256-GCM, AES-256-CBC, AES-128-GCM]',
      '    auth: SHA512',
      '    key-direction: "1"',
      '    udp: true',
      '    ca: |',
      _block(windscribeCa, '      '),
      '    tls-auth: |',
      _block(windscribeTlsAuth, '      '),
    ]);
  }
  lines.addAll([
    'proxy-groups:',
    '  - name: Windscribe',
    '    type: select',
    '    proxies: [${[auto, ...countries.keys].map(_quote).join(', ')}]',
    '  - name: $auto',
    '    type: url-test',
    '    url: https://cp.cloudflare.com/generate_204',
    '    interval: 600',
    '    lazy: true',
    '    proxies: [${named.map((entry) => _quote(entry.$2)).join(', ')}]',
    for (final MapEntry(key: country, value: names) in countries.entries) ...[
      '  - name: ${_quote(country)}',
      '    type: select',
      '    proxies: [${names.map(_quote).join(', ')}]',
    ],
    'rules:',
    '  - MATCH,Windscribe',
  ]);
  return '${lines.join('\n')}\n';
}
