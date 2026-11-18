import 'package:fl_clash/common/proxy_list.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('reads a bare SOCKS list, dropping repeats', () {
    final proxies = parseBareSocksList(
      '# free list\n1.2.3.4:1080\n1.2.3.4:1080\n'
      'proxy.example:9050:me:secret\nu:p@[2001:db8::1]:1081\n',
    )!;
    expect(proxies, hasLength(3));
    expect(proxies.first, {
      'name': '1.2.3.4:1080',
      'type': 'socks5',
      'server': '1.2.3.4',
      'port': 1080,
      'udp': true,
    });
    expect(proxies[1]['username'], 'me');
    expect(proxies[1]['password'], 'secret');
    expect(proxies[2]['server'], '2001:db8::1');
    expect(proxies[2]['name'], '[2001:db8::1]:1081');
  });

  test('anything else is not a bare list', () {
    expect(parseBareSocksList('1.2.3.4:1080\nnot a proxy\n'), isNull);
    expect(parseBareSocksList('proxies:\n  - name: a\n'), isNull);
    expect(parseBareSocksList('1.2.3.4:99999'), isNull);
    expect(parseBareSocksList(''), isNull);
  });

  test('finds a dialer chain that loops through a group', () {
    final config = <String, Object?>{
      'proxies': [
        {'name': 'a', 'dialer-proxy': 'chain'},
        {'name': 'b', 'dialer-proxy': 'a'},
        {'name': 'c', 'dialer-proxy': 'hop'},
        {'name': 'd'},
      ],
      'proxy-groups': [
        {
          'name': 'chain',
          'proxies': ['DIRECT', 'b'],
        },
        {
          'name': 'hop',
          'proxies': ['d'],
        },
      ],
    };
    expect(loopingDialers(config), {'a', 'b'});
    dropLoopingDialers(config);
    final proxies = config['proxies']! as List;
    expect(proxies[0], {'name': 'a'});
    expect(proxies[2], {'name': 'c', 'dialer-proxy': 'hop'});
  });
}
