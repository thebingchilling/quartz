import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/app_bypass/app_bypass.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('a blacklist sends each program around the tunnel', () {
    expect(
      appBypassRules(
        const AccessControlProps(
          enable: true,
          rejectList: [r'C:\Games\game.exe', 'steam.exe'],
        ),
      ),
      ['PROCESS-NAME,game.exe,DIRECT', 'PROCESS-NAME,steam.exe,DIRECT'],
    );
  });

  test('a whitelist sends everything else around the tunnel', () {
    expect(
      appBypassRules(
        const AccessControlProps(
          enable: true,
          mode: AccessControlMode.acceptSelected,
          acceptList: [r'C:\Program Files (x86)\Bank\bank.exe', 'chrome.exe'],
        ),
      ),
      ['NOT,((OR,((PROCESS-NAME,bank.exe),(PROCESS-NAME,chrome.exe)))),DIRECT'],
    );
    expect(
      appBypassRules(
        const AccessControlProps(
          enable: true,
          mode: AccessControlMode.acceptSelected,
          acceptList: ['bank.exe'],
        ),
      ),
      ['NOT,((PROCESS-NAME,bank.exe)),DIRECT'],
    );
  });

  test('off, empty or unsafe names add nothing', () {
    expect(
      appBypassRules(const AccessControlProps(rejectList: ['a.exe'])),
      isEmpty,
    );
    expect(appBypassRules(const AccessControlProps(enable: true)), isEmpty);
    expect(
      appBypassRules(
        const AccessControlProps(enable: true, rejectList: ['a,b.exe']),
      ),
      isEmpty,
    );
  });
}
