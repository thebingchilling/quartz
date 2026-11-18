<div align="center">

<img src="assets/images/icon.png" alt="Quartz" width="88">

# Quartz

A VPN client for Android and Windows built on the Clash engine (stock mihomo).

[Download the latest release](https://github.com/thebingchilling/quartz/releases/latest)

</div>

## What it does

- Runs any Clash profile, subscription link, proxy list or share-link list as a full VPN.
- Generates free Cloudflare **warp** profiles (WireGuard or AmneziaWG) and keeps them renewed.
- Turns **windscribe**'s servers into a profile you can switch by country.
- Converts WireGuard and OpenVPN config files into a profile.
- **Auto-tunnel**: rules that connect or disconnect by Wi-Fi, SIM and open apps, with a kill switch.
- Per-app bypass, IPv6, node summaries, and a network card that shows where you connect from.

Downloads are on the [releases page](https://github.com/thebingchilling/quartz/releases/latest):
APKs for arm64 and armv7 Android, and a Windows installer or a standalone EXE.

## Building

Quartz is built by `.github/workflows/quartz-clash.yml` on every push to `main`. For a local
build, see `AGENTS.md` and `.agents/commands.md`; `QUARTZ.md` describes what Quartz changes.

## License

Quartz is based on [FlClash](https://github.com/chen08209/FlClash) and uses
[mihomo](https://github.com/MetaCubeX/mihomo). It is released under the GPL-3.0, see [LICENSE](LICENSE).
