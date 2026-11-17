import 'dart:convert';

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

  Map<String, dynamic> toJson() => {
    'country': country,
    'countryCode': countryCode,
    'name': name,
    'datacenterId': datacenterId,
    'iata': iata,
    'premium': premium,
    'usage': usage,
  };

  static WindscribeServer? fromJson(Object? json) {
    if (json is! Map) return null;
    final id = json['datacenterId'];
    final iata = json['iata'];
    if (id is! int || iata is! String) return null;
    return WindscribeServer(
      country: '${json['country'] ?? ''}',
      countryCode: '${json['countryCode'] ?? ''}',
      name: '${json['name'] ?? iata}',
      datacenterId: id,
      iata: iata,
      premium: json['premium'] == true,
      usage: (json['usage'] as num?)?.toDouble() ?? 0,
    );
  }
}

/// Each server with the proxy name it has in the profile; a label two
/// servers share gets a number.
List<(WindscribeServer, String)> windscribeProxyNames(
  List<WindscribeServer> servers,
) {
  final seen = <String>{};
  String unique(String label) {
    var name = label;
    for (var n = 2; !seen.add(name); n++) {
      name = '$label $n';
    }
    return name;
  }

  return [for (final server in servers) (server, unique(server.label))];
}

/// Countries in the order the profile and the location list show them.
List<String> windscribeCountries(List<WindscribeServer> servers) => {
  for (final server in servers)
    if (server.countryCode == windscribeHomeCountryCode) server.country,
  for (final server in servers) server.country,
}.toList();

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

  /// The endpoint labels its JSON `text/html`, which Dio will not decode.
  Future<List<WindscribeServer>> servers() async {
    final response = await _dio.get<String>(
      _url,
      options: Options(
        headers: {'Authorization': 'Bearer 1234'},
        responseType: ResponseType.plain,
        receiveTimeout: const Duration(seconds: 20),
      ),
    );
    final json = jsonDecode(response.data ?? '');
    if (json is! Map<String, dynamic>) {
      throw const FormatException('Unexpected server list response');
    }
    return parseServers(json);
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

const windscribeGroupName = 'Windscribe';

/// Listed first among the locations, so it is what a new profile is written for.
const windscribeHomeCountryCode = 'NZ';

/// The resolver every Windscribe server offers inside its tunnel.
const windscribeDns = '10.255.255.1';

/// The country a profile is written for: [chosen] while the list still has
/// it, else the first one listed.
String? windscribeCountryOf(List<WindscribeServer> servers, String? chosen) {
  final countries = windscribeCountries(servers);
  return countries.contains(chosen) ? chosen : countries.firstOrNull;
}

/// The servers of [country] as mihomo `openvpn` proxies in the one Windscribe
/// group, so the proxies page offers only that country's nodes.
String buildWindscribeProfile({
  required List<WindscribeServer> servers,
  required String? country,
  required String username,
  required String password,
  required WindscribeProto proto,
  required int port,
}) {
  final selected = windscribeCountryOf(servers, country);
  final named = [
    for (final entry in windscribeProxyNames(servers))
      if (entry.$1.country == selected) entry,
  ];
  final lines = <String>['proxies:'];
  for (final (server, name) in named) {
    lines.addAll([
      '  - name: ${_quote(name)}',
      '    type: openvpn',
      '    server: ${server.host}',
      '    port: $port',
      '    proto: ${proto.name}',
      '    dev: tun',
      '    username: ${_quote(username)}',
      '    password: ${_quote(password)}',
      '    cipher: AES-256-GCM',
      '    data-ciphers: [AES-256-GCM, AES-256-CBC, AES-128-GCM]',
      '    auth: SHA512',
      '    key-direction: "1"',
      '    udp: true',
      '    remote-dns-resolve: true',
      '    dns: ["$windscribeDns"]',
      '    ca: |',
      _block(windscribeCa, '      '),
      '    tls-auth: |',
      _block(windscribeTlsAuth, '      '),
    ]);
  }
  lines.addAll([
    'proxy-groups:',
    '  - name: $windscribeGroupName',
    '    type: select',
    '    proxies: [${named.map((entry) => _quote(entry.$2)).join(', ')}]',
    'rules:',
    '  - DOMAIN-SUFFIX,whiskergalaxy.com,DIRECT',
    '  - GEOIP,LAN,DIRECT,no-resolve',
    '  - MATCH,$windscribeGroupName',
  ]);
  return '${lines.join('\n')}\n';
}
