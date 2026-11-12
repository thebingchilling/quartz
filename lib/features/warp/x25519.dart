import 'dart:math';
import 'dart:typed_data';

/// X25519 (RFC 7748) for WireGuard keys: a WARP registration needs one key
/// pair, so a plain BigInt ladder is fast enough.
final _p = (BigInt.one << 255) - BigInt.from(19);
final _a24 = BigInt.from(121665);

BigInt _decode(Uint8List bytes) {
  var value = BigInt.zero;
  for (var i = bytes.length - 1; i >= 0; i--) {
    value = (value << 8) | BigInt.from(bytes[i]);
  }
  return value;
}

Uint8List _encode(BigInt value) {
  final out = Uint8List(32);
  var rest = value;
  for (var i = 0; i < 32; i++) {
    out[i] = (rest & BigInt.from(0xff)).toInt();
    rest = rest >> 8;
  }
  return out;
}

Uint8List _clamp(Uint8List scalar) {
  final k = Uint8List.fromList(scalar);
  k[0] &= 248;
  k[31] &= 127;
  k[31] |= 64;
  return k;
}

Uint8List x25519(Uint8List scalar, Uint8List uCoordinate) {
  final k = _decode(_clamp(scalar));
  final uBytes = Uint8List.fromList(uCoordinate)..[31] &= 127;
  final x1 = _decode(uBytes) % _p;
  var x2 = BigInt.one, z2 = BigInt.zero, x3 = x1, z3 = BigInt.one;
  var swap = 0;
  for (var t = 254; t >= 0; t--) {
    final kt = ((k >> t) & BigInt.one).toInt();
    if ((swap ^ kt) == 1) {
      (x2, x3) = (x3, x2);
      (z2, z3) = (z3, z2);
    }
    swap = kt;
    final a = (x2 + z2) % _p;
    final aa = (a * a) % _p;
    final b = (x2 - z2) % _p;
    final bb = (b * b) % _p;
    final e = (aa - bb) % _p;
    final c = (x3 + z3) % _p;
    final d = (x3 - z3) % _p;
    final da = (d * a) % _p;
    final cb = (c * b) % _p;
    final sum = (da + cb) % _p;
    final diff = (da - cb) % _p;
    x3 = (sum * sum) % _p;
    z3 = (x1 * diff * diff) % _p;
    x2 = (aa * bb) % _p;
    z2 = (e * (aa + _a24 * e)) % _p;
  }
  if (swap == 1) {
    (x2, x3) = (x3, x2);
    (z2, z3) = (z3, z2);
  }
  return _encode((x2 * z2.modPow(_p - BigInt.two, _p)) % _p);
}

/// A clamped WireGuard private key and its public key.
({Uint8List privateKey, Uint8List publicKey}) generateWireGuardKeys([
  Random? random,
]) {
  final rng = random ?? Random.secure();
  final privateKey = _clamp(
    Uint8List.fromList(List.generate(32, (_) => rng.nextInt(256))),
  );
  final base = Uint8List(32)..[0] = 9;
  return (privateKey: privateKey, publicKey: x25519(privateKey, base));
}
