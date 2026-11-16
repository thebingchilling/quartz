import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/features/converter/converter.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Turns WireGuard and OpenVPN configs (pasted or from files) into one Clash
/// profile.
class ConverterView extends ConsumerStatefulWidget {
  const ConverterView({super.key});

  @override
  ConsumerState<ConverterView> createState() => _ConverterViewState();
}

class _ConverterViewState extends ConsumerState<ConverterView> {
  final _text = TextEditingController();
  final _name = TextEditingController();
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _profileName = TextEditingController(text: 'Converted');
  final List<ConvertedConfig> _converted = [];
  bool _needsCredentials = false;
  String? _error;

  @override
  void dispose() {
    _text.dispose();
    _name.dispose();
    _username.dispose();
    _password.dispose();
    _profileName.dispose();
    super.dispose();
  }

  Future<void> _pickFile() async {
    final file = await globalState.safeRun(picker.pickerFile);
    if (file == null) return;
    final text = utf8.decode(await file.readBytes(), allowMalformed: true);
    setState(() {
      _text.text = text;
      if (_name.text.isEmpty) {
        _name.text = file.name.replaceAll(RegExp(r'\.(conf|ovpn)$'), '');
      }
    });
    _check();
  }

  void _check() {
    final text = _text.text;
    if (detectConfigKind(text) != ConfigKind.openvpn) {
      setState(() => _needsCredentials = false);
      return;
    }
    try {
      final converted = convertOpenVpn(text, name: 'x');
      setState(() => _needsCredentials = converted.needsCredentials);
    } on ConvertException {
      setState(() => _needsCredentials = false);
    }
  }

  void _add() {
    final name = _name.text.trim().isEmpty
        ? 'Config ${_converted.length + 1}'
        : _name.text.trim();
    try {
      final converted = convertConfig(
        _text.text,
        name: name,
        username: _username.text.trim(),
        password: _password.text.trim(),
      );
      setState(() {
        _converted.add(converted);
        _text.clear();
        _name.clear();
        _error = null;
        _needsCredentials = false;
      });
    } on ConvertException catch (error) {
      setState(() => _error = error.message);
    }
  }

  Future<void> _create() async {
    final yaml = buildConvertedProfile(_converted);
    final label = _profileName.text.trim().isEmpty
        ? 'Converted'
        : _profileName.text.trim();
    final profile = await globalState.loadingRun(
      () => Profile.normal(label: label).saveFile(
        Uint8List.fromList(utf8.encode(yaml)),
        validate: (path, key) =>
            ref.read(coreHandlerProvider).validateProfile(path, key),
      ),
      title: context.appLocalizations.converter,
      tag: LoadingTag.profiles,
    );
    if (profile == null || !mounted) return;
    ref.read(profilesActionProvider.notifier).putProfile(profile);
    setState(_converted.clear);
    context.showNotifier(
      context.appLocalizations.converterDone(label),
      level: MessageLevel.success,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final isLoading = ref.watch(loadingProvider(LoadingTag.profiles));
    return CommonScaffold(
      title: l.converter,
      isLoading: isLoading,
      body: ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
        ).copyWith(top: context.contentTopPadding, bottom: 32),
        children: [
          Text(
            l.converterHint,
            style: context.textTheme.bodyMedium?.copyWith(
              color: context.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _text,
            minLines: 6,
            maxLines: 14,
            style: context.textTheme.bodySmall?.toJetBrainsMono,
            decoration: InputDecoration(
              labelText: l.converterConfig,
              alignLabelWithHint: true,
              errorText: _error,
            ),
            onChanged: (_) => _check(),
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: _pickFile,
              icon: const GlyphIcon(AppGlyphs.importFile),
              label: Text(l.converterFromFile),
            ),
          ),
          TextField(
            controller: _name,
            decoration: InputDecoration(labelText: l.converterName),
          ),
          if (_needsCredentials) ...[
            const SizedBox(height: 12),
            TextField(
              controller: _username,
              autocorrect: false,
              decoration: InputDecoration(labelText: l.windscribeUsername),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _password,
              obscureText: true,
              decoration: InputDecoration(labelText: l.windscribePassword),
            ),
          ],
          const SizedBox(height: 12),
          PageAction(
            child: FilledButton.tonalIcon(
              onPressed: _add,
              icon: const GlyphIcon(AppGlyphs.add, fill: 1),
              label: Text(l.converterAdd),
            ),
          ),
          if (_converted.isNotEmpty) ...[
            const SizedBox(height: 16),
            generateSectionV3(
              title: l.converterAdded,
              items: [
                for (final config in _converted)
                  ListItem(
                    leading: GlyphIcon(
                      config.kind == ConfigKind.wireguard
                          ? AppGlyphs.vpn
                          : AppGlyphs.lock,
                    ),
                    title: Text(config.names.join(', ')),
                    subtitle: Text(
                      config.kind == ConfigKind.wireguard
                          ? 'WireGuard'
                          : 'OpenVPN',
                    ),
                    trailing: IconButton(
                      tooltip: l.delete,
                      onPressed: () =>
                          setState(() => _converted.remove(config)),
                      icon: const GlyphIcon(AppGlyphs.delete),
                    ),
                  ),
              ],
            ),
            TextField(
              controller: _profileName,
              decoration: InputDecoration(labelText: l.converterProfileName),
            ),
            const SizedBox(height: 12),
            PageAction(
              child: FilledButton.icon(
                onPressed: isLoading ? null : _create,
                icon: const GlyphIcon(AppGlyphs.check, fill: 1),
                label: Text(l.converterCreate),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
