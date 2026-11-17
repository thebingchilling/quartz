import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:flutter_test/flutter_test.dart';

const _warp = TunnelTarget(profileId: 1);
const _auckland = TunnelTarget(
  profileId: 2,
  group: 'Windscribe',
  proxy: 'NZ Auckland',
);
const _windscribe = TunnelTarget(profileId: 3);
const _settings = AutoTunnelSettings(
  enabled: true,
  defaultTunnel: _warp,
  trustedNetworks: ['Home', 'Office-*'],
  wifiTunnels: {'eduroam': _windscribe},
  mobileSims: {1, 2},
  simTunnels: {2: _auckland},
  apps: [
    AppSettings(app: 'nz.bank', label: 'Bank', target: _auckland),
    AppSettings(app: 'nz.news', label: 'News'),
    AppSettings(
      app: r'C:\Apps\Torrent.exe',
      label: 'Torrent',
      killSwitch: true,
    ),
  ],
  killSwitchTunnels: {1},
);

NetworkSnapshot _wifi(String? ssid) =>
    (kind: NetworkKind.wifi, ssid: ssid, simId: null);

NetworkSnapshot _sim(int? id) =>
    (kind: NetworkKind.mobile, ssid: null, simId: id);

const NetworkSnapshot _offline = (
  kind: NetworkKind.none,
  ssid: null,
  simId: null,
);

void main() {
  test('untrusted Wi-Fi connects, trusted Wi-Fi disconnects', () {
    final cafe = decideAutoTunnel(_settings, _wifi('Cafe'))!;
    expect(cafe.connect, isTrue);
    expect(cafe.target, _warp);
    expect(decideAutoTunnel(_settings, _wifi('Home'))!.connect, isFalse);
    expect(decideAutoTunnel(_settings, _wifi('Office-3'))!.connect, isFalse);
    expect(decideAutoTunnel(_settings, _wifi(null))!.connect, isTrue);
  });

  test('a network listed with a tunnel connects that one', () {
    expect(decideAutoTunnel(_settings, _wifi('eduroam'))!.target, _windscribe);
  });

  test('Wi-Fi with the tunnel off disconnects', () {
    final off = _settings.copyWith(tunnelOnWifi: false);
    expect(decideAutoTunnel(off, _wifi('Cafe'))!.connect, isFalse);
  });

  test('mobile data follows the chosen SIMs and their tunnels', () {
    expect(decideAutoTunnel(_settings, _sim(2))!.target, _auckland);
    expect(decideAutoTunnel(_settings, _sim(1))!.target, _warp);
    expect(decideAutoTunnel(_settings, _sim(3))!.connect, isFalse);
    expect(decideAutoTunnel(_settings, _sim(null))!.connect, isTrue);
    final off = _settings.copyWith(tunnelOnMobileData: false);
    expect(decideAutoTunnel(off, _sim(1))!.connect, isFalse);
  });

  test('a mobile data tunnel stands in for the default', () {
    final mobile = _settings.copyWith(mobileTunnel: () => _windscribe);
    expect(decideAutoTunnel(mobile, _sim(1))!.target, _windscribe);
  });

  test('no default tunnel keeps whichever profile is current', () {
    final current = _settings.copyWith(defaultTunnel: () => null);
    final action = decideAutoTunnel(current, _wifi('Cafe'))!;
    expect(action.connect, isTrue);
    expect(action.target, isNull);
  });

  test('no network leaves the tunnel unless told to stop', () {
    expect(decideAutoTunnel(_settings, _offline), isNull);
    final stop = _settings.copyWith(stopOnNoNetwork: true);
    expect(decideAutoTunnel(stop, _offline)!.connect, isFalse);
  });

  test('an open app wins over the network, even a trusted one', () {
    final bank = decideAutoTunnel(
      _settings,
      _wifi('Home'),
      openApps: ['nz.bank'],
    )!;
    expect(bank.key, 'app:nz.bank');
    expect(bank.target, _auckland);
    final news = decideAutoTunnel(
      _settings,
      _wifi('Home'),
      openApps: ['nz.news'],
    )!;
    expect(news.target, _warp);
  });

  test('the tunnel kill switch spares trusted networks', () {
    expect(blocksAll(_settings, _wifi('Cafe'), 1), isTrue);
    expect(blocksAll(_settings, _wifi('Home'), 1), isFalse);
    expect(blocksAll(_settings, _wifi('Cafe'), 2), isFalse);
  });

  test('Windows programs are detected by file name', () {
    expect(_settings.apps.last.detected, 'torrent.exe');
    expect(_settings.apps.first.detected, 'nz.bank');
  });

  test('an app counts as open until it is closed', () {
    const apps = {'bank.exe'};
    var recent = stepOpenApps(
      const [],
      foreground: 'bank.exe',
      open: const {},
      ruleApps: apps,
    );
    expect(recent, ['bank.exe']);
    recent = stepOpenApps(
      recent,
      foreground: 'explorer.exe',
      open: {'bank.exe'},
      ruleApps: apps,
    );
    expect(recent, ['bank.exe']);
    recent = stepOpenApps(
      recent,
      foreground: 'explorer.exe',
      open: const {},
      ruleApps: apps,
    );
    expect(recent, isEmpty);
  });

  test('settings survive a JSON round trip', () {
    final back = AutoTunnelSettings.fromJson(_settings.toJson());
    expect(back.defaultTunnel, _warp);
    expect(back.trustedNetworks, ['Home', 'Office-*']);
    expect(back.wifiTunnels, {'eduroam': _windscribe});
    expect(back.mobileSims, {1, 2});
    expect(back.simTunnels, {2: _auckland});
    expect(back.apps[1].target, isNull);
    expect(back.apps.last.killSwitch, isTrue);
    expect(back.killSwitchTunnels, {1});
    expect(back.usesKillSwitch, isTrue);
    expect(back.targets, {_warp, _windscribe, _auckland});
  });
}
