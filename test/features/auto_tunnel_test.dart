import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:flutter_test/flutter_test.dart';

const _vless = TunnelTarget(profileId: 1);
const _warp = TunnelTarget(profileId: 2);
const _chinaIp = TunnelTarget(profileId: 3, group: 'PROXY', proxy: 'CN');
const _discord = RuleApp(app: 'com.discord', label: 'Discord');
const _bank = RuleApp(app: 'nz.co.bnz', label: 'BNZ');
const _netflix = RuleApp(app: 'com.netflix', label: 'Netflix');

const _home = AutoTunnelRule(
  id: 'home',
  networks: [NetworkMatch.wifi('MyHome')],
  connect: false,
);
const _data = AutoTunnelRule(
  id: 'data',
  networks: [NetworkMatch.mobile()],
  target: _vless,
);
const _discordWarp = AutoTunnelRule(
  id: 'discord',
  apps: [_discord],
  target: _warp,
);
const _banking = AutoTunnelRule(id: 'bank', apps: [_bank], connect: false);
const _cafe = AutoTunnelRule(
  id: 'cafe',
  networks: [NetworkMatch.wifi('Cafe')],
  apps: [_netflix],
  target: _warp,
);
const _chinaWifi = AutoTunnelRule(
  id: 'china-wifi',
  categoryId: 'china',
  networks: [NetworkMatch.wifi()],
  target: _vless,
);
const _chinaData = AutoTunnelRule(
  id: 'china-data',
  categoryId: 'china',
  networks: [NetworkMatch.mobile()],
  target: _chinaIp,
);
const _china = RuleCategory(id: 'china', name: 'China', enabled: false);

const _settings = AutoTunnelSettings(
  enabled: true,
  rules: [_chinaWifi, _chinaData, _home, _data, _discordWarp, _banking, _cafe],
  categories: [_china],
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
  test('a network rule connects its own tunnel or disconnects', () {
    final home = decideAutoTunnel(_settings, _wifi('MyHome'))!;
    expect((home.connect, home.ruleId), (false, 'home'));
    final data = decideAutoTunnel(_settings, _sim(1), active: 'home')!;
    expect((data.connect, data.target, data.ruleId), (true, _vless, 'data'));
  });

  test('an app rule takes over, and the network rule comes back after', () {
    final discord = decideAutoTunnel(
      _settings,
      _sim(1),
      openApps: ['com.discord'],
      active: 'data',
    )!;
    expect((discord.target, discord.ruleId), (_warp, 'discord'));
    final closed = decideAutoTunnel(_settings, _sim(1), active: 'discord')!;
    expect(closed.ruleId, 'data');
  });

  test('the app opened last wins', () {
    final bank = decideAutoTunnel(
      _settings,
      _sim(1),
      openApps: ['nz.co.bnz', 'com.discord'],
    )!;
    expect((bank.connect, bank.ruleId), (false, 'bank'));
  });

  test('a rule that ends with none other matching disconnects', () {
    final ended = decideAutoTunnel(
      _settings,
      _wifi('Elsewhere'),
      active: 'data',
    )!;
    expect((ended.key, ended.connect, ended.ruleId), ('ended', false, null));
  });

  test('with no rule in effect and none matching, nothing changes', () {
    expect(decideAutoTunnel(_settings, _wifi('Elsewhere')), isNull);
  });

  test('AND needs the network and the app, OR either', () {
    expect(
      decideAutoTunnel(
        _settings,
        _wifi('Cafe'),
        openApps: ['com.netflix'],
      )!.ruleId,
      'cafe',
    );
    expect(decideAutoTunnel(_settings, _wifi('Cafe')), isNull);
    expect(
      decideAutoTunnel(
        _settings,
        _wifi('Elsewhere'),
        openApps: ['com.netflix'],
      ),
      isNull,
    );
    final either = _settings.putRule(_cafe.copyWith(matchAll: false));
    expect(decideAutoTunnel(either, _wifi('Cafe'))!.ruleId, 'cafe');
    expect(
      decideAutoTunnel(
        either,
        _wifi('Elsewhere'),
        openApps: ['com.netflix'],
      )!.ruleId,
      'cafe',
    );
  });

  test('a category turns its rules on and off together, top first', () {
    final abroad = _settings.putCategory(_china.copyWith(enabled: true));
    expect(decideAutoTunnel(abroad, _wifi('MyHome'))!.ruleId, 'china-wifi');
    expect(decideAutoTunnel(abroad, _sim(1))!.target, _chinaIp);
    expect(decideAutoTunnel(_settings, _sim(1))!.ruleId, 'data');
  });

  test('turning off the rule in effect ends it', () {
    final off = _settings.putRule(_data.copyWith(enabled: false));
    expect(decideAutoTunnel(off, _sim(1), active: 'data')!.key, 'ended');
  });

  test('no network stops the tunnel unless the rule has a kill switch', () {
    expect(decideAutoTunnel(_settings, _offline, active: 'data')!.key, 'none');
    final guarded = _settings.putRule(_data.copyWith(killSwitch: true));
    expect(decideAutoTunnel(guarded, _offline, active: 'data'), isNull);
  });

  test('the kill switch blocks while its rule is in effect and down', () {
    final guarded = _settings.putRule(_data.copyWith(killSwitch: true));
    expect(blocksAll(guarded, active: 'data', running: false), isTrue);
    expect(blocksAll(guarded, active: 'data', running: true), isFalse);
    expect(blocksAll(guarded, active: 'home', running: false), isFalse);
    expect(blocksAll(guarded, active: null, running: false), isFalse);
    expect(blocksAll(_settings, active: 'data', running: false), isFalse);
  });

  test('a category kill switch covers its connecting rules', () {
    final abroad = _settings.putCategory(
      _china.copyWith(enabled: true, killSwitch: true),
    );
    expect(blocksAll(abroad, active: 'china-data', running: false), isTrue);
    expect(abroad.killSwitchOf(_home), isFalse);
    expect(abroad.anyKillSwitch, isTrue);
  });

  test('deleting a category keeps its rules', () {
    final removed = _settings.removeCategory('china');
    expect(removed.categories, isEmpty);
    expect(removed.rules.first.categoryId, isNull);
  });

  test('watched apps are every active rule\'s apps', () {
    expect(_settings.watchedApps, {'com.discord', 'nz.co.bnz', 'com.netflix'});
    expect(
      const RuleApp(app: r'C:\Apps\Discord.exe', label: 'Discord').detected,
      'discord.exe',
    );
  });

  test('wildcards match Wi-Fi names', () {
    expect(ssidMatches('Home*', 'Home-5G'), isTrue);
    expect(ssidMatches('Cafe?', 'Cafe'), isFalse);
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
      _settings
          .putRule(_cafe.copyWith(name: 'Café', killSwitch: true))
          .toJson(),
    );
    expect(back.rules.map((r) => r.id), _settings.rules.map((r) => r.id));
    final cafe = back.rules.last;
    expect((cafe.name, cafe.killSwitch, cafe.matchAll), ('Café', true, true));
    expect(cafe.apps, [_netflix]);
    expect(back.categories.single.name, 'China');
    expect(back.rules.first.categoryId, 'china');
    expect(back.targets, {_vless, _chinaIp, _warp});
  });

  test('the previous settings keep their rules without the seeded one', () {
    final migrated = AutoTunnelSettings.fromPrevious({
      'enabled': true,
      'defaultTunnel': _warp.toJson(),
      'killSwitch': true,
      'rules': [
        {
          'id': 'any-wifi',
          'networks': [
            {'kind': 'wifi'},
          ],
          'connect': false,
        },
        {
          'id': 'data',
          'networks': [
            {'kind': 'mobile'},
          ],
        },
        {
          'id': 'home',
          'networks': [
            {'kind': 'wifi', 'ssid': 'MyHome'},
          ],
          'connect': false,
        },
      ],
    });
    expect(migrated.rules.map((r) => r.id), ['data', 'home']);
    expect(migrated.rules.first.target, _warp);
    expect(migrated.rules.first.killSwitch, isTrue);
    expect(migrated.rules.last.killSwitch, isFalse);
  });
}
