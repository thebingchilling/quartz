import 'dart:convert';

import 'package:dio/dio.dart';
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
    expect(servers, hasLength(2));
  });

  test('reads the online servers and their hostnames', () {
    final servers = WindscribeApi.parseServers(_status);
    expect(servers.map((s) => s.host), [
      'tia-283.whiskergalaxy.com',
      'adl-354.whiskergalaxy.com',
    ]);
    expect(servers.first.premium, isTrue);
  });

  test('builds OpenVPN proxies grouped by country', () {
    final yaml = buildWindscribeProfile(
      servers: WindscribeApi.parseServers(_status),
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
    expect(
      yaml,
      contains('proxies: ["Windscribe Auto", "Albania", "Australia"]'),
    );
    expect(yaml, contains('  - MATCH,Windscribe'));
  });
}
