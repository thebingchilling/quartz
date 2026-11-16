import 'dart:async';

import 'package:fl_clash/common/ip_details.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('reads ipwho.is', () {
    final details = IpDetails.fromIpWhoIs({
      'success': true,
      'ip': '1.2.3.4',
      'country': 'Japan',
      'country_code': 'JP',
      'region': 'Tokyo',
      'city': 'Tokyo',
      'connection': {'isp': 'Example ISP'},
    })!;
    expect(details.ip, '1.2.3.4');
    expect(details.country, 'Japan');
    expect(details.place, 'Tokyo');
    expect(details.isp, 'Example ISP');
  });

  test('reads ipinfo.io, splitting the ASN off the ISP', () {
    final details = IpDetails.fromIpInfo({
      'ip': '1.1.1.1',
      'city': 'Auckland',
      'region': 'Auckland',
      'country': 'NZ',
      'org': 'AS13335 Cloudflare, Inc.',
    })!;
    expect(details.countryCode, 'NZ');
    expect(details.isp, 'Cloudflare, Inc.');
    expect(details.details, {'ASN': 'AS13335'});
  });

  test(
    'the first source to answer wins, without waiting on the rest',
    () async {
      final slow = Completer<Map<String, dynamic>>();
      final details = await lookupIpDetails((url) async {
        if (url.contains('ipwho.is')) return slow.future;
        if (url.contains('ipinfo.io')) throw const FormatException('down');
        return {
          'status': 'success',
          'query': '5.6.7.8',
          'countryCode': 'DE',
          'country': 'Germany',
          'city': 'Berlin',
          'regionName': 'Berlin',
          'isp': 'Example',
        };
      });
      expect(details.ip, '5.6.7.8');
      expect(details.place, 'Berlin');
    },
  );

  test('throws when no source answers', () {
    expect(
      lookupIpDetails((_) async => throw const FormatException('down')),
      throwsFormatException,
    );
  });
}
