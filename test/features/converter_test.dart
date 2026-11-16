import 'package:fl_clash/features/converter/converter.dart';
import 'package:flutter_test/flutter_test.dart';

const _wg = '''
[Interface]
PrivateKey = priv=
Address = 10.0.0.2/32, fd00::2/128
MTU = 1420
Jc = 4
I1 = <b 0x01>

[Peer]
PublicKey = peer1=
Endpoint = [2001:db8::1]:51820
PersistentKeepalive = 25

[Peer]
PublicKey = peer2=
AllowedIPs = 10.9.0.0/16
Endpoint = vpn.example.com:443
''';

const _ovpn = '''
client
proto tcp-client
remote a.example.com 1194 udp
remote b.example.com 443
auth-user-pass
tls-auth [inline] 1
<ca>
-----BEGIN CERTIFICATE-----
AAAA
-----END CERTIFICATE-----
</ca>
<tls-auth>
-----BEGIN OpenVPN Static key V1-----
00
-----END OpenVPN Static key V1-----
</tls-auth>
''';

void main() {
  test('WireGuard: addresses, every peer and AmneziaWG fields', () {
    final config = convertConfig(_wg, name: 'Home');
    final yaml = config.proxies.single;
    expect(config.kind, ConfigKind.wireguard);
    expect(yaml, contains('ip: 10.0.0.2'));
    expect(yaml, contains('ipv6: "fd00::2"'));
    expect(yaml, contains('peers:'));
    expect(yaml, contains('server: "2001:db8::1"'));
    expect(yaml, contains('port: 443'));
    expect(yaml, contains('jc: 4'));
    expect(yaml, contains('i1: "<b 0x01>"'));
  });

  test('OpenVPN: one proxy per remote, protocol and key direction', () {
    final config = convertConfig(_ovpn, name: 'Work');
    expect(config.names, ['Work 1', 'Work 2']);
    expect(config.needsCredentials, isTrue);
    expect(config.proxies.first, contains('proto: udp'));
    expect(config.proxies.last, contains('proto: tcp'));
    expect(config.proxies.last, contains('key-direction: "1"'));

    final withLogin = convertConfig(
      _ovpn,
      name: 'Work',
      username: 'u',
      password: 'p',
    );
    expect(withLogin.needsCredentials, isFalse);
    expect(withLogin.proxies.first, contains('username: "u"'));
  });

  test('a certificate kept in another file is reported', () {
    expect(
      () =>
          convertConfig('client\nremote a.example.com\nca ca.crt\n', name: 'x'),
      throwsA(isA<ConvertException>()),
    );
  });

  test('the profile groups every proxy', () {
    final yaml = buildConvertedProfile([
      convertConfig(_wg, name: 'Home'),
      convertConfig(_ovpn, name: 'Work', username: 'u', password: 'p'),
    ]);
    expect(yaml, contains('proxies: ["Auto", "Home", "Work 1", "Work 2"]'));
    expect(yaml, contains('  - MATCH,PROXY'));
  });
}
