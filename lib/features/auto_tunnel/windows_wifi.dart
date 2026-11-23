import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:isolate';

import 'package:ffi/ffi.dart';
import 'package:win32/win32.dart';

/// Saved Wi-Fi profiles, then those in range (hidden until location is on),
/// from the WLAN API rather than netsh, which antivirus heuristics flag.
Future<List<String>> windowsWifiNetworks() async =>
    Platform.isWindows ? Isolate.run(_wifiNetworks) : const [];

/// Item [index], [size] bytes each, of a WLAN list: two DWORDs, then items.
Pointer<T> _entry<T extends Struct>(Pointer list, int index, int size) =>
    Pointer<T>.fromAddress(list.address + 8 + index * size);

List<String> _wifiNetworks() {
  final version = calloc<Uint32>();
  final handle = calloc<Pointer>();
  final interfaces = calloc<Pointer<WLAN_INTERFACE_INFO_LIST>>();
  final profiles = calloc<Pointer<WLAN_PROFILE_INFO_LIST>>();
  final networks = calloc<Pointer<WLAN_AVAILABLE_NETWORK_LIST>>();
  final saved = <String>{};
  final nearby = <String>{};
  try {
    if (WlanOpenHandle(2, version, handle) != ERROR_SUCCESS) return const [];
    final client = HANDLE(handle.value);
    try {
      if (WlanEnumInterfaces(client, interfaces) != ERROR_SUCCESS) {
        return const [];
      }
      final list = interfaces.value;
      for (var i = 0; i < list.ref.dwNumberOfItems; i++) {
        final guid = _entry<WLAN_INTERFACE_INFO>(
          list,
          i,
          sizeOf<WLAN_INTERFACE_INFO>(),
        ).cast<GUID>();
        if (WlanGetProfileList(client, guid, profiles) == ERROR_SUCCESS) {
          final found = profiles.value;
          for (var j = 0; j < found.ref.dwNumberOfItems; j++) {
            saved.add(
              _entry<WLAN_PROFILE_INFO>(
                found,
                j,
                sizeOf<WLAN_PROFILE_INFO>(),
              ).ref.strProfileName,
            );
          }
          WlanFreeMemory(profiles.value);
        }
        if (WlanGetAvailableNetworkList(client, guid, 0, networks) ==
            ERROR_SUCCESS) {
          final found = networks.value;
          for (var j = 0; j < found.ref.dwNumberOfItems; j++) {
            final ssid = _entry<WLAN_AVAILABLE_NETWORK>(
              found,
              j,
              sizeOf<WLAN_AVAILABLE_NETWORK>(),
            ).ref.dot11Ssid;
            final name = utf8.decode([
              for (var k = 0; k < ssid.uSSIDLength && k < 32; k++)
                ssid.ucSSID[k],
            ], allowMalformed: true);
            if (name.isNotEmpty) nearby.add(name);
          }
          WlanFreeMemory(networks.value);
        }
      }
      WlanFreeMemory(list);
    } finally {
      WlanCloseHandle(client);
    }
  } finally {
    calloc
      ..free(version)
      ..free(handle)
      ..free(interfaces)
      ..free(profiles)
      ..free(networks);
  }
  return {...saved, ...nearby}.toList();
}
