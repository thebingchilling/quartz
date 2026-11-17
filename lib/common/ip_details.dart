import 'dart:async';

import 'package:flutter/foundation.dart';

/// Where this connection reaches the internet from.
@immutable
class IpDetails {
  const IpDetails({
    required this.ip,
    required this.countryCode,
    this.country,
    this.city,
    this.region,
    this.isp,
    this.latitude,
    this.longitude,
    this.details = const {},
  });

  final String ip;
  final String countryCode;
  final String? country;
  final String? city;
  final String? region;
  final String? isp;
  final double? latitude;
  final double? longitude;

  /// Everything else the service told, by label, for the details popup.
  final Map<String, String> details;

  /// "City, Region" without repeats or blanks.
  String get place => {
    ?city,
    ?region,
  }.map((part) => part.trim()).where((part) => part.isNotEmpty).join(', ');

  String? get coordinates {
    final lat = latitude;
    final lon = longitude;
    if (lat == null || lon == null) return null;
    return '${lat.toStringAsFixed(4)}, ${lon.toStringAsFixed(4)}';
  }

  String? get mapsUrl {
    final lat = latitude;
    final lon = longitude;
    if (lat == null || lon == null) return null;
    return Uri.https('www.google.com', '/maps/search/', {
      'api': '1',
      'query': '$lat,$lon',
    }).toString();
  }

  static double? _number(Object? value) => switch (value) {
    final num number => number.toDouble(),
    final String text => double.tryParse(text.trim()),
    _ => null,
  };

  static Map<String, String> _pick(
    Map<String, dynamic> json,
    Map<String, String> labels,
  ) => {
    for (final MapEntry(key: path, value: label) in labels.entries)
      label: ?_text(_at(json, path)),
  };

  static Object? _at(Map<String, dynamic> json, String path) {
    Object? value = json;
    for (final key in path.split('.')) {
      value = value is Map ? value[key] : null;
    }
    return value;
  }

  static String? _text(Object? value) {
    final text = value?.toString().trim();
    return text == null || text.isEmpty ? null : text;
  }

  static IpDetails? fromIpWhoIs(Map<String, dynamic> json) {
    final ip = _text(json['ip']);
    final code = _text(json['country_code']);
    if (json['success'] == false || ip == null || code == null) return null;
    final connection = json['connection'];
    return IpDetails(
      ip: ip,
      countryCode: code,
      country: _text(json['country']),
      city: _text(json['city']),
      region: _text(json['region']),
      isp: connection is Map
          ? _text(connection['isp']) ?? _text(connection['org'])
          : null,
      latitude: _number(json['latitude']),
      longitude: _number(json['longitude']),
      details: _pick(json, const {
        'connection.asn': 'ASN',
        'connection.org': 'Organisation',
        'type': 'IP type',
      }),
    );
  }

  static IpDetails? fromIpInfo(Map<String, dynamic> json) {
    final ip = _text(json['ip']);
    final code = _text(json['country']);
    if (json['bogon'] == true || ip == null || code == null) return null;
    final org = _text(json['org']);
    final asn = org == null ? null : RegExp(r'^AS\d+').firstMatch(org)?[0];
    final isp = asn == null ? org : _text(org!.substring(asn.length));
    final loc = _text(json['loc'])?.split(',');
    return IpDetails(
      ip: ip,
      countryCode: code,
      city: _text(json['city']),
      region: _text(json['region']),
      isp: isp,
      latitude: loc?.length == 2 ? _number(loc!.first) : null,
      longitude: loc?.length == 2 ? _number(loc!.last) : null,
      details: {'ASN': ?asn},
    );
  }

  static IpDetails? fromIpApiCom(Map<String, dynamic> json) {
    final ip = _text(json['query']);
    final code = _text(json['countryCode']);
    if (json['status'] != 'success' || ip == null || code == null) return null;
    return IpDetails(
      ip: ip,
      countryCode: code,
      country: _text(json['country']),
      city: _text(json['city']),
      region: _text(json['regionName']),
      isp: _text(json['isp']) ?? _text(json['org']),
      latitude: _number(json['lat']),
      longitude: _number(json['lon']),
      details: _pick(json, const {'as': 'ASN', 'org': 'Organisation'}),
    );
  }
}

/// Free, keyless lookups with country, city and ISP. ip-api.com is plain
/// HTTP on its free plan; its HTTPS endpoint needs a paid key.
const ipDetailsSources = <(String, IpDetails? Function(Map<String, dynamic>))>[
  ('http://ip-api.com/json/?fields=66846719', IpDetails.fromIpApiCom),
  ('https://ipinfo.io/json', IpDetails.fromIpInfo),
  ('https://ipwho.is/', IpDetails.fromIpWhoIs),
];

/// Asks every source at once and takes the first usable answer, so one slow
/// or blocked service never holds the card up; throws the last failure when
/// none answers.
Future<IpDetails> lookupIpDetails(
  Future<Map<String, dynamic>> Function(String url) getJson,
) {
  final result = Completer<IpDetails>();
  var pending = ipDetailsSources.length;
  Object lastError = const FormatException('No IP lookup source');
  for (final (url, parse) in ipDetailsSources) {
    unawaited(() async {
      try {
        final details = parse(await getJson(url));
        if (details != null) {
          if (!result.isCompleted) result.complete(details);
          return;
        }
        lastError = FormatException('No location in the answer', url);
      } catch (error) {
        lastError = error;
      }
      if (--pending == 0 && !result.isCompleted) {
        result.completeError(lastError);
      }
    }());
  }
  return result.future;
}
