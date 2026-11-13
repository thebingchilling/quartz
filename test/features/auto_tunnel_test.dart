import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:flutter_test/flutter_test.dart';

NetworkSnapshot _wifi(String? ssid) =>
    (kind: NetworkKind.wifi, ssid: ssid, simId: null);

void main() {
  test('a blacklisted Wi-Fi disconnects, any other connects', () {
    const settings = AutoTunnelSettings(enabled: true, ssids: ['Home']);
    expect(decideAutoTunnel(settings, _wifi('Home')), isFalse);
    expect(decideAutoTunnel(settings, _wifi('Cafe')), isTrue);
    expect(decideAutoTunnel(settings, _wifi(null)), isNull);
  });

  test('only a whitelisted Wi-Fi connects', () {
    const settings = AutoTunnelSettings(
      enabled: true,
      wifiMode: WifiRuleMode.whitelist,
      ssids: ['Cafe'],
    );
    expect(decideAutoTunnel(settings, _wifi('Cafe')), isTrue);
    expect(decideAutoTunnel(settings, _wifi('Home')), isFalse);
  });

  test('mobile data follows the chosen SIMs, Ethernet its switch', () {
    const settings = AutoTunnelSettings(
      enabled: true,
      simIds: [2],
      ethernetEnabled: false,
    );
    expect(
      decideAutoTunnel(settings, (
        kind: NetworkKind.mobile,
        ssid: null,
        simId: 2,
      )),
      isTrue,
    );
    expect(
      decideAutoTunnel(settings, (
        kind: NetworkKind.mobile,
        ssid: null,
        simId: 1,
      )),
      isFalse,
    );
    expect(
      decideAutoTunnel(settings, (
        kind: NetworkKind.ethernet,
        ssid: null,
        simId: null,
      )),
      isFalse,
    );
  });
}
