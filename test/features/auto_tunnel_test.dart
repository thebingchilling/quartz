import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:flutter_test/flutter_test.dart';

const _warp = TunnelTarget(profileId: 1);
const _windscribe = TunnelTarget(profileId: 2, group: 'windscribe');
const _bnz = RuleApp(app: 'nz.co.bnz', label: 'BNZ');
const _chrome = RuleApp(app: 'com.android.chrome', label: 'Chrome');

const _eduroam = AutoTunnelRule(
  id: 'eduroam',
  networks: [NetworkMatch.wifi('eduroam')],
  target: _warp,
);
const _bankOnSim2 = AutoTunnelRule(
  id: 'bank',
  networks: [NetworkMatch.mobile(2)],
  apps: [_bnz],
  target: _windscribe,
);
const _browsing = AutoTunnelRule(
  id: 'browsing',
  networks: [NetworkMatch.wifi('eduroam'), NetworkMatch.mobile()],
  apps: [_chrome],
);
const _settings = AutoTunnelSettings(
  enabled: true,
  defaultTunnel: _warp,
  rules: [_eduroam, _bankOnSim2, _browsing],
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
  test('a network rule connects its tunnel and nothing else matches', () {
    final eduroam = decideAutoTunnel(_settings, _wifi('eduroam'))!;
    expect(eduroam.key, 'rule:eduroam');
    expect(eduroam.target, _warp);
    final cafe = decideAutoTunnel(_settings, _wifi('Cafe'))!;
    expect(cafe.connect, isFalse);
    expect(cafe.key, 'otherwise');
  });

  test('no match leaves the tunnel alone when told to', () {
    final keep = _settings.copyWith(disconnectOtherwise: false);
    expect(decideAutoTunnel(keep, _wifi('Cafe')), isNull);
  });

  test('a rule with apps needs one open and its network', () {
    expect(decideAutoTunnel(_settings, _sim(2))!.connect, isFalse);
    final bank = decideAutoTunnel(_settings, _sim(2), openApps: ['nz.co.bnz'])!;
    expect(bank.target, _windscribe);
    expect(
      decideAutoTunnel(_settings, _sim(1), openApps: ['nz.co.bnz'])!.connect,
      isFalse,
    );
  });

  test('app rules come before network rules, for the newest app', () {
    final chrome = decideAutoTunnel(
      _settings,
      _wifi('eduroam'),
      openApps: ['com.android.chrome'],
    )!;
    expect(chrome.key, 'rule:browsing');
    expect(chrome.target, _warp);
  });

  test('any Wi-Fi, any SIM, wildcards and an empty network list', () {
    const any = AutoTunnelSettings(
      rules: [
        AutoTunnelRule(id: 'home', networks: [NetworkMatch.wifi('Home*')]),
        AutoTunnelRule(id: 'wifi', networks: [NetworkMatch.wifi()]),
        AutoTunnelRule(id: 'everywhere'),
      ],
    );
    expect(decideAutoTunnel(any, _wifi('Home-5G'))!.key, 'rule:home');
    expect(decideAutoTunnel(any, _wifi(null))!.key, 'rule:wifi');
    expect(decideAutoTunnel(any, _sim(3))!.key, 'rule:everywhere');
  });

  test('a disabled rule is skipped', () {
    final off = _settings.putRule(_eduroam.copyWith(enabled: false));
    expect(decideAutoTunnel(off, _wifi('eduroam'))!.key, 'otherwise');
  });

  test('no network stops the tunnel by default', () {
    expect(decideAutoTunnel(_settings, _offline)!.connect, isFalse);
    final keep = _settings.copyWith(stopOnNoNetwork: false);
    expect(decideAutoTunnel(keep, _offline), isNull);
  });

  test('the kill switch blocks only while a tunnel is wanted', () {
    final on = _settings.copyWith(killSwitch: true);
    expect(blocksAll(on, tunnelWanted: true), isTrue);
    expect(blocksAll(on, tunnelWanted: false), isFalse);
    expect(blocksAll(_settings, tunnelWanted: true), isFalse);
  });

  test('watched apps are every enabled rule\'s apps', () {
    expect(_settings.watchedApps, {'nz.co.bnz', 'com.android.chrome'});
    expect(
      const RuleApp(app: r'C:\Apps\Discord.exe', label: 'Discord').detected,
      'discord.exe',
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
      open: const {},
      ruleApps: apps,
    );
    expect(recent, isEmpty);
  });

  test('settings survive a JSON round trip', () {
    final back = AutoTunnelSettings.fromJson(
      _settings.copyWith(killSwitch: true).toJson(),
    );
    expect(back.rules.map((r) => r.id), ['eduroam', 'bank', 'browsing']);
    expect(back.rules[1].networks, [const NetworkMatch.mobile(2)]);
    expect(back.rules[1].apps, [_bnz]);
    expect(back.rules[1].target, _windscribe);
    expect(back.killSwitch, isTrue);
    expect(back.stopOnNoNetwork, isTrue);
    expect(back.targets, {_warp, _windscribe});
  });

  test('the previous settings become rules in the same precedence', () {
    final migrated = AutoTunnelSettings.fromPrevious({
      'enabled': true,
      'trustedNetworks': ['Home'],
      'wifiTunnels': [
        {'ssid': 'eduroam', 'target': _warp.toJson()},
      ],
      'tunnelOnWifi': true,
      'tunnelOnMobileData': true,
      'mobileSims': [2],
      'apps': [
        {'app': 'nz.co.bnz', 'label': 'BNZ', 'target': _windscribe.toJson()},
      ],
      'killSwitchTunnels': [1],
    });
    expect(decideAutoTunnel(migrated, _wifi('Home'))!.connect, isFalse);
    expect(decideAutoTunnel(migrated, _wifi('eduroam'))!.target, _warp);
    expect(decideAutoTunnel(migrated, _wifi('Cafe'))!.connect, isTrue);
    expect(decideAutoTunnel(migrated, _sim(2))!.connect, isTrue);
    expect(decideAutoTunnel(migrated, _sim(1))!.connect, isFalse);
    expect(
      decideAutoTunnel(migrated, _sim(1), openApps: ['nz.co.bnz'])!.target,
      _windscribe,
    );
    expect(migrated.killSwitch, isTrue);
  });
}
