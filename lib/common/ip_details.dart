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
    this.details = const {},
  });

  final String ip;
  final String countryCode;
  final String? country;
  final String? city;
  final String? region;
  final String? isp;

  /// Everything else the service told, by label, for the details popup.
  final Map<String, String> details;

  /// "City, Region" without repeats or blanks.
  String get place => {
    ?city,
    ?region,
  }.map((part) => part.trim()).where((part) => part.isNotEmpty).join(', ');

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
      details: _pick(json, const {
        'type': 'IP type',
        'continent': 'Continent',
        'postal': 'Postal code',
        'latitude': 'Latitude',
        'longitude': 'Longitude',
        'connection.asn': 'ASN',
        'connection.org': 'Organisation',
        'connection.domain': 'Domain',
        'timezone.id': 'Time zone',
        'timezone.utc': 'UTC offset',
        'calling_code': 'Calling code',
        'capital': 'Capital',
      }),
    );
  }

  static IpDetails? fromIpApiCo(Map<String, dynamic> json) {
    final ip = _text(json['ip']);
    final code = _text(json['country_code']);
    if (json['error'] == true || ip == null || code == null) return null;
    return IpDetails(
      ip: ip,
      countryCode: code,
      country: _text(json['country_name']),
      city: _text(json['city']),
      region: _text(json['region']),
      isp: _text(json['org']),
      details: _pick(json, const {
        'version': 'IP type',
        'continent_code': 'Continent',
        'postal': 'Postal code',
        'latitude': 'Latitude',
        'longitude': 'Longitude',
        'asn': 'ASN',
        'timezone': 'Time zone',
        'utc_offset': 'UTC offset',
        'country_calling_code': 'Calling code',
        'country_capital': 'Capital',
        'currency': 'Currency',
      }),
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
      details: _pick(json, const {
        'zip': 'Postal code',
        'lat': 'Latitude',
        'lon': 'Longitude',
        'as': 'ASN',
        'org': 'Organisation',
        'timezone': 'Time zone',
      }),
    );
  }
}

/// Lookup services in order of preference: all free and keyless, each with
/// country, city and ISP. ip-api.com is plain HTTP on its free plan.
const ipDetailsSources = <(String, IpDetails? Function(Map<String, dynamic>))>[
  ('https://ipwho.is/', IpDetails.fromIpWhoIs),
  ('https://ipapi.co/json/', IpDetails.fromIpApiCo),
  ('http://ip-api.com/json/?fields=66846719', IpDetails.fromIpApiCom),
];

/// The first source that answers; throws the last failure when none does.
Future<IpDetails> lookupIpDetails(
  Future<Map<String, dynamic>> Function(String url) getJson,
) async {
  Exception lastError = const FormatException('No IP lookup source');
  for (final (url, parse) in ipDetailsSources) {
    try {
      final details = parse(await getJson(url));
      if (details != null) return details;
      lastError = FormatException('No location in the answer', url);
    } on Exception catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}
