import 'dart:io';

import 'package:fl_clash/common/node_summary.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('each protocol gets its condensed line', () {
    expect(
      nodeSummary({
        'type': 'vless',
        'flow': 'xtls-rprx-vision',
        'tls': true,
        'reality-opts': {'public-key': 'k'},
      }),
      'VLESS · Vision · Reality',
    );
    expect(
      nodeSummary({'type': 'vless', 'tls': true, 'network': 'xhttp'}),
      'VLESS · TLS · XHTTP',
    );
    expect(
      nodeSummary({'type': 'vmess', 'tls': true, 'network': 'grpc'}),
      'VMess · TLS · gRPC',
    );
    expect(
      nodeSummary({'type': 'trojan', 'network': 'ws'}),
      'Trojan · TLS · WS',
    );
    expect(
      nodeSummary({'type': 'ss', 'cipher': 'rc4-md5'}),
      'Shadowsocks · rc4-md5',
    );
    expect(
      nodeSummary({'type': 'ss', 'cipher': 'aes-128-gcm', 'plugin': 'obfs'}),
      'Shadowsocks · aes-128-gcm · obfs',
    );
    expect(nodeSummary({'type': 'wireguard'}), 'WireGuard');
    expect(
      nodeSummary({
        'type': 'wireguard',
        'port': 2408,
        'mtu': 1280,
        'amnezia-wg-option': {'jc': 4},
      }),
      'WireGuard · AmneziaWG',
    );
    expect(
      nodeSummary({'type': 'openvpn', 'proto': 'udp', 'port': 443}),
      'OpenVPN · UDP',
    );
    expect(
      nodeSummary({'type': 'hysteria2', 'obfs': 'salamander'}),
      'Hysteria2 · Salamander',
    );
    expect(nodeSummary({'type': 'tuic', 'uuid': 'u'}), 'TUIC · v5');
    expect(
      nodeSummary({'type': 'vmess', 'cipher': 'auto', 'network': 'ws'}),
      'VMess · WS',
    );
    expect(nodeSummary({'type': 'snell', 'version': 3}), 'Snell · v3');
    expect(nodeSummary({'type': 'mieru', 'transport': 'tcp'}), 'Mieru · TCP');
    expect(nodeSummary({'type': 'socks5', 'username': 'me'}), 'SOCKS5 · auth');
    expect(nodeSummary({'name': 'no type'}), isNull);
  });

  test('reads the applied config and its provider files, renamed', () {
    final dir = Directory.systemTemp.createTempSync('node_summary_test');
    addTearDown(() => dir.deleteSync(recursive: true));
    final providerFile = File('${dir.path}/sub.yaml')
      ..writeAsStringSync(
        'proxies:\n  - {name: tokyo, type: ss, cipher: rc4-md5}\n',
      );
    File('${dir.path}/links.txt').writeAsStringSync('ss://abc');
    final config = File('${dir.path}/config.yaml')
      ..writeAsStringSync('''
proxies:
  - {name: warp, type: wireguard}
proxy-providers:
  sub:
    type: http
    path: ${providerFile.path}
    override:
      additional-prefix: "JP "
      proxy-name:
        - {pattern: tokyo, target: Tokyo}
  links:
    type: http
    path: ${dir.path}/links.txt
  inline:
    type: inline
    payload:
      - {name: home, type: socks5}
''');

    final scan = scanNodeSummaries(config.path);

    expect(scan.summaries, {
      'warp': 'WireGuard',
      'JP Tokyo': 'Shadowsocks · rc4-md5',
      'home': 'SOCKS5',
    });
    expect(scan.pending.single.path, '${dir.path}/links.txt');
  });
}
