import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:flutter_test/flutter_test.dart';

const _warp = TunnelTarget(profileId: 1);
const _auckland = TunnelTarget(
  profileId: 2,
  group: 'Windscribe',
  proxy: 'NZ Auckland',
);
const _settings = AutoTunnelSettings(
  enabled: true,
  connections: [
    ConnectionSettings(
      kind: ConnectionKind.wifi,
      ssid: 'eduroam',
      target: _warp,
    ),
    ConnectionSettings(kind: ConnectionKind.wifi, killSwitch: true),
    ConnectionSettings(
      kind: ConnectionKind.mobile,
      simId: 2,
      target: _auckland,
    ),
  ],
  apps: [
    AppSettings(app: 'nz.bank', label: 'Bank', target: _auckland),
    AppSettings(
      app: r'C:\Apps\Torrent.exe',
      label: 'Torrent',
      killSwitch: true,
    ),
  ],
  killSwitchTunnels: {1},
);

NetworkSnapshot _wifi(String ssid) =>
    (kind: NetworkKind.wifi, ssid: ssid, simId: null);

NetworkSnapshot _sim(int id) =>
    (kind: NetworkKind.mobile, ssid: null, simId: id);

void main() {
  test('each connection picks its own tunnel', () {
    expect(decideAutoTunnel(_settings, _wifi('eduroam'))?.$1, 'wifi:eduroam');
    expect(decideAutoTunnel(_settings, _sim(2))?.$2, _auckland);
    expect(decideAutoTunnel(_settings, _sim(1)), isNull);
    expect(decideAutoTunnel(_settings, _wifi('Cafe')), isNull);
  });

  test('an open app wins over the connection', () {
    final decision = decideAutoTunnel(
      _settings,
      _wifi('eduroam'),
      openApps: ['nz.bank'],
    );
    expect(decision?.$1, 'app:nz.bank');
  });

  test('named Wi-Fi falls back to the other Wi-Fi entry', () {
    expect(connectionFor(_settings, _wifi('Cafe'))?.id, 'wifi:*');
    expect(blocksAll(_settings, _wifi('Cafe'), null), isTrue);
    expect(blocksAll(_settings, _wifi('eduroam'), 2), isFalse);
    expect(blocksAll(_settings, _wifi('eduroam'), 1), isTrue);
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
    expect(back.connections.map((c) => c.id), [
      'wifi:eduroam',
      'wifi:*',
      'mobile:2',
    ]);
    expect(back.connections.last.target, _auckland);
    expect(back.connections[1].target, isNull);
    expect(back.apps.last.killSwitch, isTrue);
    expect(back.killSwitchTunnels, {1});
    expect(back.usesKillSwitch, isTrue);
  });
}
