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
  rules: [
    AutoTunnelRule(
      id: 'uni',
      kind: TriggerKind.wifi,
      ssids: ['eduroam'],
      target: _warp,
    ),
    AutoTunnelRule(
      id: 'sim2',
      kind: TriggerKind.mobile,
      simIds: [2],
      target: _auckland,
    ),
    AutoTunnelRule(
      id: 'bank',
      kind: TriggerKind.app,
      apps: ['nz.bank'],
      target: _auckland,
    ),
  ],
);

NetworkSnapshot _wifi(String ssid) =>
    (kind: NetworkKind.wifi, ssid: ssid, simId: null);

NetworkSnapshot _sim(int id) =>
    (kind: NetworkKind.mobile, ssid: null, simId: id);

void main() {
  test('network rules pick their tunnel', () {
    expect(decideAutoTunnel(_settings, _wifi('eduroam'))?.$1, 'uni');
    expect(decideAutoTunnel(_settings, _sim(2))?.$2, _auckland);
    expect(decideAutoTunnel(_settings, _sim(1)), isNull);
  });

  test('an open rule app wins over the network', () {
    final decision = decideAutoTunnel(
      _settings,
      _wifi('eduroam'),
      openApps: ['nz.bank'],
    );
    expect(decision?.$1, 'bank');
  });

  test('the fallback applies when nothing matches, not without a network', () {
    final off = _settings.copyWith(fallback: FallbackMode.disconnect);
    final decision = decideAutoTunnel(off, _wifi('Cafe'));
    expect(decision?.$1, 'fallback');
    expect(decision?.$2.disconnects, isTrue);
    expect(
      decideAutoTunnel(off, (kind: NetworkKind.none, ssid: null, simId: null)),
      isNull,
    );
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
    final json = _settings
        .copyWith(fallback: FallbackMode.target, fallbackTarget: _warp)
        .toJson();
    final back = AutoTunnelSettings.fromJson(json);
    expect(back.rules.map((rule) => rule.id), ['uni', 'sim2', 'bank']);
    expect(back.rules[1].target, _auckland);
    expect(back.fallbackTarget, _warp);
  });
}
