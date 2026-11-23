import 'dart:typed_data';

import 'package:fl_clash/features/app_bypass/windows_programs.dart';
import 'package:flutter_test/flutter_test.dart';

/// A shortcut with no ID list and a LinkInfo whose strings follow its header.
Uint8List _shortcut({required bool unicode, int linkInfoFlags = 1}) {
  final header = ByteData(0x4C)
    ..setUint32(0, 0x4C, Endian.little)
    ..setUint32(0x14, 0x2, Endian.little);
  final headerSize = unicode ? 0x24 : 0x1C;
  List<int> ansi(String text) => [...text.codeUnits, 0];
  List<int> wide(String text) => [
    for (final unit in [...text.codeUnits, 0]) ...[unit & 0xFF, unit >> 8],
  ];
  final base = unicode ? wide(r'C:\Apps\') : ansi(r'C:\Apps\');
  final suffix = unicode ? wide('Bank.exe') : ansi('Bank.exe');
  final info = ByteData(headerSize)
    ..setUint32(0, headerSize + base.length + suffix.length, Endian.little)
    ..setUint32(4, headerSize, Endian.little)
    ..setUint32(8, linkInfoFlags, Endian.little);
  if (unicode) {
    info
      ..setUint32(0x1C, headerSize, Endian.little)
      ..setUint32(0x20, headerSize + base.length, Endian.little);
  } else {
    info
      ..setUint32(0x10, headerSize, Endian.little)
      ..setUint32(0x18, headerSize + base.length, Endian.little);
  }
  return Uint8List.fromList([
    ...header.buffer.asUint8List(),
    ...info.buffer.asUint8List(),
    ...base,
    ...suffix,
  ]);
}

void main() {
  test('reads a shortcut\'s local target, ANSI or UTF-16', () {
    expect(shortcutTarget(_shortcut(unicode: false)), r'C:\Apps\Bank.exe');
    expect(shortcutTarget(_shortcut(unicode: true)), r'C:\Apps\Bank.exe');
  });

  test('a shortcut without a local path, or not a shortcut, has no target', () {
    expect(shortcutTarget(_shortcut(unicode: false, linkInfoFlags: 2)), isNull);
    expect(shortcutTarget(Uint8List(10)), isNull);
    expect(shortcutTarget(Uint8List.fromList('not a link'.codeUnits)), isNull);
  });
}
