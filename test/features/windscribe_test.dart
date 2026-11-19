import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:fl_clash/common/node_summary.dart';
import 'package:fl_clash/common/yaml.dart';
import 'package:fl_clash/features/windscribe/windscribe.dart';
import 'package:flutter_test/flutter_test.dart';

final _status = {
  'data': {
    'locations': {
      '1': {
        'name': 'Australia',
        'country_code': 'AU',
        'pops': [
          {
            'status': 1,
            'datacenter_id': 354,
            'iata_code': 'ADL',
            'name': 'Adelaide - Lofty',
            'usage': 5.5,
            'is_premium': 0,
          },
          {
            'status': 0,
            'datacenter_id': 355,
            'iata_code': 'ADL',
            'name': 'Adelaide - Offline',
            'is_premium': 0,
          },
        ],
      },
      '2': {
        'name': 'Albania',
        'country_code': 'AL',
        'pops': [
          {
            'status': 1,
            'datacenter_id': 283,
            'iata_code': 'TIA',
            'name': 'Tirana - Besa',
            'is_premium': 1,
          },
        ],
      },
      '3': {
        'name': 'New Zealand',
        'country_code': 'NZ',
        'pops': [
          {
            'status': 1,
            'datacenter_id': 99,
            'iata_code': 'AKL',
            'name': 'Auckland - Hauraki',
            'is_premium': 1,
          },
        ],
      },
    },
  },
};

class _HtmlLabelledJson implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async => ResponseBody.fromString(
    jsonEncode(_status),
    200,
    headers: {
      Headers.contentTypeHeader: ['text/html; charset=UTF-8'],
    },
  );

  @override
  void close({bool force = false}) {}
}

void main() {
  test('reads the list though the API labels its JSON as HTML', () async {
    final dio = Dio()..httpClientAdapter = _HtmlLabelledJson();
    final servers = await WindscribeApi(dio).servers();
    expect(servers, hasLength(3));
  });

  test('reads the online servers and their hostnames', () {
    final servers = WindscribeApi.parseServers(_status);
    expect(servers.map((s) => s.host), [
      'tia-283.whiskergalaxy.com',
      'adl-354.whiskergalaxy.com',
      'akl-99.whiskergalaxy.com',
    ]);
    expect(servers.first.premium, isTrue);
  });

  test('builds OpenVPN proxies for the chosen country only', () {
    final yaml = buildWindscribeProfile(
      servers: WindscribeApi.parseServers(_status),
      country: 'Australia',
      username: 'u',
      password: 'p',
      proto: WindscribeProto.tcp,
      port: 8080,
    );
    expect(yaml, contains('type: openvpn'));
    expect(yaml, contains('proto: tcp'));
    expect(yaml, contains('port: 8080'));
    expect(yaml, contains('-----BEGIN CERTIFICATE-----'));
    expect(yaml, contains('-----BEGIN OpenVPN Static key V1-----'));
    expect(yaml, contains('proxies: ["AU Adelaide - Lofty"]'));
    expect(yaml, contains('server: adl-354.whiskergalaxy.com'));
    expect(yaml, isNot(contains('tia-283')));
    expect(yaml, isNot(contains('akl-99')));
    expect(yaml, contains('  - MATCH,windscribe'));
    expect(yaml, isNot(contains('url-test')));
    expect(yaml, isNot(contains('hidden: true')));
    expect(yaml, contains('dev: tun'));
    expect(yaml, contains('remote-dns-resolve: true'));
    expect(yaml, contains('dns: ["10.255.255.1"]'));
    expect(yaml, contains('  - DOMAIN-SUFFIX,whiskergalaxy.com,DIRECT'));
    expect(yaml, contains('  - GEOIP,LAN,DIRECT,no-resolve'));
    final proxy = ((loadPlainYaml(yaml) as Map)['proxies'] as List).single;
    expect(nodeSummary(proxy as Map), 'OpenVPN · TCP');
  });

  test('a country no longer listed falls back to the home country', () {
    final servers = WindscribeApi.parseServers(_status);
    expect(windscribeCountryOf(servers, 'Atlantis'), 'New Zealand');
    expect(windscribeCountryOf(servers, null), 'New Zealand');
    expect(windscribeCountryOf(servers, 'Albania'), 'Albania');
  });

  test('the server list survives a save, for the location picker', () {
    final servers = WindscribeApi.parseServers(_status);
    final restored = [
      for (final server in servers)
        WindscribeServer.fromJson(jsonDecode(jsonEncode(server.toJson())))!,
    ];
    expect(restored.map((s) => s.host), servers.map((s) => s.host));
    expect(windscribeCountries(restored).first, 'New Zealand');
  });
}
