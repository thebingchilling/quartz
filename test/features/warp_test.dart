import 'dart:convert';
import 'dart:typed_data';

import 'package:fl_clash/features/warp/warp.dart';
import 'package:fl_clash/features/warp/x25519.dart';
import 'package:flutter_test/flutter_test.dart';

Uint8List _hex(String hex) => Uint8List.fromList([
  for (var i = 0; i < hex.length; i += 2)
    int.parse(hex.substring(i, i + 2), radix: 16),
]);

void main() {
  test('X25519 matches RFC 7748', () {
    final base = Uint8List(32)..[0] = 9;
    final public = x25519(
      _hex('77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a'),
      base,
    );
    expect(
      public,
      _hex('8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a'),
    );
  });

  final response = {
    'result': {
      'id': 'abc',
      'token': 'tok',
      'created': '2026-11-01T00:00:00Z',
      'account': {'ttl': '0001-01-01T00:00:00Z'},
      'config': {
        'client_id': base64.encode([1, 2, 3]),
        'peers': [
          {
            'public_key': 'peerkey=',
            'endpoint': {'host': 'engage.cloudflareclient.com:2408'},
          },
        ],
        'interface': {
          'addresses': {'v4': '172.16.0.2/32', 'v6': 'fd01::2/128'},
        },
      },
    },
  };

  test('reads a registration and its 90-day expiry', () {
    final registration = WarpRegistration.parse(response, 'v0a737', 'priv=');
    expect(registration.addressV4, '172.16.0.2');
    expect(registration.ttlAt, isNull);
    expect(registration.expiresAt(), DateTime.utc(2027, 1, 30));
  });

  test('builds a Clash profile with the reserved bytes and AmneziaWG', () {
    final registration = WarpRegistration.parse(response, 'v0a737', 'priv=');
    final yaml = buildWarpProfile(
      name: 'WARP AWG',
      registration: registration,
      options: const WarpOptions(variant: WarpVariant.awg),
      awg: const WarpAwg(['<b 0x01>', '<b 0x02>', '<b 0x03>', '', '']),
    );
    expect(yaml, contains('type: wireguard'));
    expect(yaml, contains('server: "engage.cloudflareclient.com"'));
    expect(yaml, contains('port: 2408'));
    expect(yaml, contains('reserved: [1, 2, 3]'));
    expect(yaml, contains('amnezia-wg-option:'));
    expect(yaml, contains('i1: "<b 0x01>"'));
    expect(yaml, contains('  - MATCH,PROXY'));
  });
}
