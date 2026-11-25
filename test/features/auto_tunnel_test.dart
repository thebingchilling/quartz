import 'package:fl_clash/features/auto_tunnel/auto_tunnel.dart';
import 'package:flutter_test/flutter_test.dart';

const _vless = TunnelTarget(profileId: 1);
const _warp = TunnelTarget(profileId: 2);
const _chinaIp = TunnelTarget(profileId: 3, group: 'PROXY', proxy: 'CN');

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
const _cafe = AutoTunnelRule(
  id: 'cafe',
  networks: [NetworkMatch.wifi('Cafe*')],
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
  rules: [_chinaWifi, _chinaData, _home, _data, _cafe],
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

  test('a rule that ends with none other matching disconnects', () {
    final ended = decideAutoTunnel(
      _settings,
      _wifi('Elsewhere'),
      active: 'data',
    )!;
    expect((ended.key, ended.connect, ended.ruleId), ('ended', false, null));
  });

  test('a rule that ends can leave the tunnel or connect another', () {
    final keep = _settings.putRule(_data.copyWith(onEnd: EndAction.keep));
    final kept = decideAutoTunnel(keep, _wifi('Elsewhere'), active: 'data')!;
    expect((kept.connect, kept.ruleId), (null, null));
    final hop = _settings.putRule(
      _data.copyWith(onEnd: EndAction.connect, endTarget: () => _warp),
    );
    final hopped = decideAutoTunnel(hop, _wifi('Elsewhere'), active: 'data')!;
    expect((hopped.connect, hopped.target), (true, _warp));
    expect(
      decideAutoTunnel(hop, _wifi('MyHome'), active: 'data')!.ruleId,
      'home',
    );
  });

  test('a Wi-Fi name with a wildcard, and a rule with no networks', () {
    expect(decideAutoTunnel(_settings, _wifi('Cafe-5G'))!.ruleId, 'cafe');
    const everywhere = AutoTunnelSettings(
      enabled: true,
      rules: [AutoTunnelRule(id: 'all', target: _warp)],
    );
    expect(decideAutoTunnel(everywhere, _sim(1))!.ruleId, 'all');
  });

  test('rules that waited for apps are dropped, not widened', () {
    final kept = withoutAppRules({
      'rules': [
        {
          'id': 'bank',
          'apps': [
            {'app': 'nz.co.bnz'},
          ],
        },
        {
          'id': 'home',
          'networks': [
            {'kind': 'wifi', 'ssid': 'MyHome'},
          ],
          'apps': <Object>[],
        },
      ],
    });
    expect(AutoTunnelSettings.fromJson(kept).rules.map((r) => r.id), ['home']);
  });

  test('with no rule in effect and none matching, nothing changes', () {
    expect(decideAutoTunnel(_settings, _wifi('Elsewhere')), isNull);
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

  test('the kill switch holds through a drop but never after a user stop', () {
    KillSwitchState state({
      bool enabled = true,
      bool wanted = true,
      bool up = true,
      bool userStopped = false,
      String? active,
      AutoTunnelSettings? settings,
    }) => killSwitchState(
      settings ?? _settings,
      active: active,
      enabled: enabled,
      wanted: wanted,
      up: up,
      userStopped: userStopped,
    );

    expect(state(), (blockAll: false, blockOnCoreExit: true));
    expect(state(up: false), (blockAll: true, blockOnCoreExit: false));
    expect(state(wanted: false, up: false), (
      blockAll: false,
      blockOnCoreExit: false,
    ));
    expect(state(enabled: false, up: false), (
      blockAll: false,
      blockOnCoreExit: false,
    ));
    final guarded = _settings.putRule(_data.copyWith(killSwitch: true));
    expect(state(settings: guarded, active: 'data', wanted: false, up: false), (
      blockAll: true,
      blockOnCoreExit: false,
    ));
    expect(
      state(
        settings: guarded,
        active: 'data',
        wanted: false,
        up: false,
        userStopped: true,
      ),
      (blockAll: false, blockOnCoreExit: false),
    );
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

  test('wildcards match Wi-Fi names', () {
    expect(ssidMatches('Home*', 'Home-5G'), isTrue);
    expect(ssidMatches('Cafe?', 'Cafe'), isFalse);
  });

  test('settings survive a JSON round trip', () {
    final back = AutoTunnelSettings.fromJson(
      _settings
          .putRule(
            _cafe.copyWith(
              name: 'Café',
              killSwitch: true,
              onEnd: EndAction.connect,
              endTarget: () => _chinaIp,
            ),
          )
          .toJson(),
    );
    expect(back.rules.map((r) => r.id), _settings.rules.map((r) => r.id));
    final cafe = back.rules.last;
    expect((cafe.name, cafe.killSwitch), ('Café', true));
    expect((cafe.onEnd, cafe.endTarget), (EndAction.connect, _chinaIp));
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
