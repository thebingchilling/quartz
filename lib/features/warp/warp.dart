import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'x25519.dart';

enum WarpVariant {
  wg('WireGuard'),
  awg('AmneziaWG');

  const WarpVariant(this.label);

  final String label;
}

enum WarpEndpointMode { auto, random, custom }

/// AmneziaWG junk packets. Only client-side junk works against WARP: its end
/// is stock WireGuard, so S1/S2 and H1..H4 stay locked.
class WarpJunk {
  const WarpJunk({this.jc = 4, this.jMin = 40, this.jMax = 70});

  final int jc;
  final int jMin;
  final int jMax;

  bool get isValid =>
      jc >= 0 &&
      jc <= 128 &&
      jMin >= 0 &&
      jMin <= 1280 &&
      jMax >= 0 &&
      jMax <= 1280;

  Map<String, dynamic> toJson() => {'jc': jc, 'jmin': jMin, 'jmax': jMax};

  factory WarpJunk.fromJson(Map<String, dynamic> json) => WarpJunk(
    jc: json['jc'] as int? ?? 4,
    jMin: json['jmin'] as int? ?? 40,
    jMax: json['jmax'] as int? ?? 70,
  );
}

/// The generator's options, as in the original Quartz generator.
class WarpOptions {
  const WarpOptions({
    this.variant = WarpVariant.wg,
    this.endpointMode = WarpEndpointMode.auto,
    this.endpoint = '',
    this.mtu = 1280,
    this.dns = '1.1.1.1, 1.0.0.1',
    this.keepalive = 0,
    this.junk = const WarpJunk(),
  });

  final WarpVariant variant;
  final WarpEndpointMode endpointMode;

  /// The random or custom endpoint, `host:port`.
  final String endpoint;
  final int mtu;
  final String dns;
  final int keepalive;
  final WarpJunk junk;

  WarpOptions copyWith({
    WarpVariant? variant,
    WarpEndpointMode? endpointMode,
    String? endpoint,
    int? mtu,
    String? dns,
    int? keepalive,
    WarpJunk? junk,
  }) => WarpOptions(
    variant: variant ?? this.variant,
    endpointMode: endpointMode ?? this.endpointMode,
    endpoint: endpoint ?? this.endpoint,
    mtu: mtu ?? this.mtu,
    dns: dns ?? this.dns,
    keepalive: keepalive ?? this.keepalive,
    junk: junk ?? this.junk,
  );

  Map<String, dynamic> toJson() => {
    'variant': variant.name,
    'endpointMode': endpointMode.name,
    'endpoint': endpoint,
    'mtu': mtu,
    'dns': dns,
    'keepalive': keepalive,
    'junk': junk.toJson(),
  };

  factory WarpOptions.fromJson(Map<String, dynamic> json) => WarpOptions(
    variant: WarpVariant.values.asNameMap()[json['variant']] ?? WarpVariant.wg,
    endpointMode:
        WarpEndpointMode.values.asNameMap()[json['endpointMode']] ??
        WarpEndpointMode.auto,
    endpoint: json['endpoint'] as String? ?? '',
    mtu: json['mtu'] as int? ?? 1280,
    dns: json['dns'] as String? ?? '1.1.1.1, 1.0.0.1',
    keepalive: json['keepalive'] as int? ?? 0,
    junk: WarpJunk.fromJson(
      (json['junk'] as Map?)?.cast<String, dynamic>() ?? const {},
    ),
  );
}

abstract final class WarpEndpoints {
  static const defaultHost = 'engage.cloudflareclient.com:2408';

  /// WARP anycast addresses and the UDP ports its servers answer on besides
  /// 2408, for networks that block the default port.
  static const _ips = [
    1,
    11,
    14,
    21,
    24,
    31,
    34,
    41,
    44,
    51,
    54,
    61,
    64,
    71,
    74,
    81,
    84,
    91,
    94, //
    101, 105, 106, 205, 215,
  ];
  static const _ports = [
    854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942, //
    943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074,
    1180, 1387, 1843, 2371, 2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233,
    5279, 5956, 7103, 7152, 7156, 7281, 7559, 8319, 8742, 8854, 8886,
  ];

  static String random([Random? random]) {
    final rng = random ?? Random();
    return '162.159.192.${_ips[rng.nextInt(_ips.length)]}:'
        '${_ports[rng.nextInt(_ports.length)]}';
  }
}

class WarpException implements Exception {
  const WarpException(this.message, {this.rejected = false});

  final String message;

  /// Cloudflare answered `success: false`; other API versions won't help.
  final bool rejected;

  @override
  String toString() => message;
}

/// A WARP registration and the private key it was made with.
class WarpRegistration {
  const WarpRegistration({
    required this.id,
    required this.token,
    required this.apiVersion,
    required this.privateKey,
    required this.peerPublicKey,
    required this.endpointHost,
    required this.addressV4,
    this.clientId,
    this.createdAt,
    this.ttlAt,
  });

  final String id;
  final String token;
  final String apiVersion;
  final String privateKey;
  final String peerPublicKey;
  final String endpointHost;
  final String addressV4;

  /// Three base64 bytes WARP expects in WireGuard's reserved field.
  final String? clientId;
  final DateTime? createdAt;
  final DateTime? ttlAt;

  static const lifetime = Duration(days: 90);

  /// Free registrations last 90 days, or less when Cloudflare sets a TTL.
  DateTime expiresAt([DateTime? now]) {
    final fixed = (createdAt ?? now ?? DateTime.now()).add(lifetime);
    final ttl = ttlAt;
    return ttl != null && ttl.isBefore(fixed) ? ttl : fixed;
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'token': token,
    'apiVersion': apiVersion,
    'privateKey': privateKey,
    'peerPublicKey': peerPublicKey,
    'endpointHost': endpointHost,
    'addressV4': addressV4,
    'clientId': clientId,
    'createdAt': createdAt?.toIso8601String(),
    'ttlAt': ttlAt?.toIso8601String(),
  };

  factory WarpRegistration.fromJson(Map<String, dynamic> json) =>
      WarpRegistration(
        id: json['id'] as String,
        token: json['token'] as String,
        apiVersion: json['apiVersion'] as String,
        privateKey: json['privateKey'] as String,
        peerPublicKey: json['peerPublicKey'] as String,
        endpointHost: json['endpointHost'] as String,
        addressV4: json['addressV4'] as String,
        clientId: json['clientId'] as String?,
        createdAt: DateTime.tryParse(json['createdAt'] as String? ?? ''),
        ttlAt: DateTime.tryParse(json['ttlAt'] as String? ?? ''),
      );

  /// Accepts both the bare and the `{"result": …}` response shapes.
  static WarpRegistration parse(
    Map<String, dynamic> response,
    String apiVersion,
    String privateKey, {
    DateTime? now,
  }) {
    final result = response['result'];
    final root = result is Map<String, dynamic> && result.containsKey('config')
        ? result
        : response;
    Map<String, dynamic>? obj(Object? value) =>
        value is Map ? value.cast<String, dynamic>() : null;
    String? str(Object? value) =>
        value == null || value.toString().isEmpty ? null : value.toString();
    final config =
        obj(root['config']) ?? (throw const WarpException('No config'));
    final peers = config['peers'];
    final peer = peers is List && peers.isNotEmpty ? obj(peers.first) : null;
    if (peer == null) throw const WarpException('No peer');
    final addresses = obj(obj(config['interface'])?['addresses']);
    final v4 = str(addresses?['v4'])?.split('/').first;
    final ttl = DateTime.tryParse(str(obj(root['account'])?['ttl']) ?? '');
    return WarpRegistration(
      id: str(root['id']) ?? (throw const WarpException('No id')),
      token: str(root['token']) ?? (throw const WarpException('No token')),
      apiVersion: apiVersion,
      privateKey: privateKey,
      peerPublicKey:
          str(peer['public_key']) ?? (throw const WarpException('No peer key')),
      endpointHost:
          str(obj(peer['endpoint'])?['host']) ?? WarpEndpoints.defaultHost,
      addressV4: v4 ?? (throw const WarpException('No IPv4 address')),
      clientId: str(config['client_id']),
      createdAt: DateTime.tryParse(str(root['created']) ?? ''),
      // An unset TTL comes back as 0001-01-01: only a future time counts.
      ttlAt: ttl != null && ttl.isAfter(now ?? DateTime.now()) ? ttl : null,
    );
  }
}

/// Cloudflare's WARP client API (unofficial).
class WarpApi {
  WarpApi(this._dio);

  final Dio _dio;

  static const _base = 'https://api.cloudflareclient.com';
  static const apiVersions = ['v0a737', 'v0a1909051800', 'v0i1909051800'];
  static const _headers = {
    'User-Agent': 'okhttp/3.12.1',
    'CF-Client-Version': 'a-6.30-3596',
  };

  /// Every API version in turn; a `success: false` answer stops at once.
  Future<WarpRegistration> register() async {
    final keys = generateWireGuardKeys();
    final privateKey = base64.encode(keys.privateKey);
    final body = {
      'key': base64.encode(keys.publicKey),
      'install_id': '',
      'warp_enabled': true,
      'tos': DateTime.now().toUtc().toIso8601String(),
      'type': 'Linux',
      'locale': 'en_US',
    };
    WarpException? last;
    for (final version in apiVersions) {
      try {
        final response = await _send(
          () => _dio.post<Object>(
            '$_base/$version/reg',
            data: body,
            options: _options,
          ),
        );
        return WarpRegistration.parse(response, version, privateKey);
      } on WarpException catch (error) {
        if (error.rejected) rethrow;
        last = error;
      }
    }
    throw WarpException('WARP registration failed: ${last?.message}');
  }

  /// Revokes a registration; one already gone counts as done.
  Future<void> delete(WarpRegistration registration) async {
    try {
      await _dio.delete<Object>(
        '$_base/${registration.apiVersion}/reg/${registration.id}',
        options: _options.copyWith(
          headers: {
            ..._headers,
            'Authorization': 'Bearer ${registration.token}',
          },
        ),
      );
    } on DioException catch (error) {
      final status = error.response?.statusCode;
      if (status != 401 && status != 403 && status != 404) {
        throw WarpException('Delete failed: ${error.message}');
      }
    }
  }

  Options get _options => Options(
    headers: _headers,
    contentType: Headers.jsonContentType,
    responseType: ResponseType.json,
    sendTimeout: const Duration(seconds: 15),
    receiveTimeout: const Duration(seconds: 30),
  );

  Future<Map<String, dynamic>> _send(
    Future<Response<Object>> Function() request,
  ) async {
    try {
      final response = await request();
      final data = response.data;
      final json = data is String ? jsonDecode(data) : data;
      if (json is! Map<String, dynamic>) {
        throw const WarpException('Unreadable answer');
      }
      if (json['success'] == false) {
        throw WarpException(_errorMessage(json) ?? 'Rejected', rejected: true);
      }
      return json;
    } on DioException catch (error) {
      final data = error.response?.data;
      final message = data is Map<String, dynamic> ? _errorMessage(data) : null;
      throw WarpException(
        'HTTP ${error.response?.statusCode ?? '-'}: '
        '${message ?? error.message ?? error.type.name}',
      );
    }
  }

  static String? _errorMessage(Map<String, dynamic> json) {
    final errors = json['errors'];
    final first = errors is List && errors.isNotEmpty ? errors.first : null;
    final error = first is Map ? first : json['error'];
    return error is Map ? error['message']?.toString() : null;
  }
}

/// AmneziaWG's I1..I5: a captured WARP QUIC Initial for I1, random byte
/// blocks for the rest. Picked once per registration.
class WarpAwg {
  const WarpAwg(this.i);

  final List<String> i;

  static List<String> parseCaptured(String text) => [
    for (final line in const LineSplitter().convert(text))
      if (line.trim().isNotEmpty && !line.trim().startsWith('#')) line.trim(),
  ];

  factory WarpAwg.pick(List<String> captured, [Random? random]) {
    final rng = random ?? Random.secure();
    String tag(int length) {
      final bytes = Uint8List.fromList(
        List.generate(length, (_) => rng.nextInt(256)),
      );
      return '<b 0x${bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join()}>';
    }

    String junk() => [
      for (var n = 1 + rng.nextInt(3); n > 0; n--) tag(16 + rng.nextInt(48)),
    ].join();
    final i1 = captured.isNotEmpty
        ? captured[rng.nextInt(captured.length)]
        : tag(1200);
    return WarpAwg([i1, junk(), junk(), junk(), junk()]);
  }
}

String _quote(String value) =>
    '"${value.replaceAll(r'\', r'\\').replaceAll('"', r'\"')}"';

/// A Clash (mihomo) profile with the WARP `wireguard` proxy in a PROXY group.
String buildWarpProfile({
  required String name,
  required WarpRegistration registration,
  required WarpOptions options,
  WarpAwg? awg,
}) {
  final endpoint = switch (options.endpointMode) {
    WarpEndpointMode.auto => registration.endpointHost,
    _ =>
      options.endpoint.trim().isEmpty
          ? registration.endpointHost
          : options.endpoint.trim(),
  };
  final colon = endpoint.lastIndexOf(':');
  final server = colon > 0 ? endpoint.substring(0, colon) : endpoint;
  final port = colon > 0 ? int.tryParse(endpoint.substring(colon + 1)) : null;
  final reserved = _reserved(registration.clientId);
  final dns = [
    for (final part in options.dns.split(','))
      if (part.trim().isNotEmpty) part.trim(),
  ];
  final lines = <String>[
    'proxies:',
    '  - name: ${_quote(name)}',
    '    type: wireguard',
    '    server: ${_quote(server.replaceAll(RegExp(r'^\[|\]$'), ''))}',
    '    port: ${port ?? 2408}',
    // IPv4 only: WARP's IPv6 range often geolocates far from the user.
    '    ip: ${registration.addressV4}',
    '    private-key: ${_quote(registration.privateKey)}',
    '    public-key: ${_quote(registration.peerPublicKey)}',
    '    allowed-ips: ["0.0.0.0/0", "::/0"]',
    if (reserved != null) '    reserved: [${reserved.join(', ')}]',
    '    mtu: ${options.mtu}',
    if (dns.isNotEmpty) ...[
      '    remote-dns-resolve: true',
      '    dns: [${dns.map(_quote).join(', ')}]',
    ],
    if (options.keepalive > 0) '    persistent-keepalive: ${options.keepalive}',
    '    udp: true',
    if (options.variant == WarpVariant.awg && awg != null) ...[
      '    amnezia-wg-option:',
      '      jc: ${options.junk.jc}',
      '      jmin: ${min(options.junk.jMin, options.junk.jMax)}',
      '      jmax: ${max(options.junk.jMin, options.junk.jMax)}',
      '      s1: 0',
      '      s2: 0',
      '      h1: 1',
      '      h2: 2',
      '      h3: 3',
      '      h4: 4',
      for (var n = 0; n < awg.i.length; n++)
        '      i${n + 1}: ${_quote(awg.i[n])}',
    ],
    'proxy-groups:',
    '  - name: PROXY',
    '    type: select',
    '    proxies: [${_quote(name)}]',
    'rules:',
    '  - MATCH,PROXY',
  ];
  return '${lines.join('\n')}\n';
}

List<int>? _reserved(String? clientId) {
  if (clientId == null) return null;
  try {
    final bytes = base64.decode(clientId.trim());
    return bytes.length == 3 ? bytes : null;
  } on FormatException {
    return null;
  }
}
