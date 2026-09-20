<p align="center">
  <img alt="Ratempus" title="Ratempus" src="mockup/svg/tempus-horizontal-banner.png" width="250">
</p>

---

<p align="center">
  <b>Ratempus — Audiophile Subsonic Music Client for Android</b>
</p>

<div align="center">

<a href="https://github.com/icadkren22/ratempus/releases">
    <img alt="Releases" src="https://img.shields.io/github/v/release/icadkren22/ratempus?color=4B95DE&style=flat">
</a>
<a href="https://www.gnu.org/licenses/gpl-3.0">
    <img src="https://img.shields.io/badge/license-GPL%20v3-2B6DBE.svg?style=flat">
</a>

</div>

**Ratempus** is an open-source, privacy-focused music client for Subsonic servers on Android. It is a fork of [Tempus](https://github.com/eddyizm/tempus) which enables **Direct HD audio output**, **USB Exclusive mode**, and **advanced built-in DSP equalizer support**.

[Changelog](CHANGELOG.md) | [Wiki](USAGE.md)

### Releases

Download release APKs from [GitHub Releases](https://github.com/icadkren22/ratempus/releases).
- 🟥 `app-tempus` — standard release with Android Auto & Chromecast support
- 🟩 `app-degoogled` — clean release without Google services

---

## Features

1. **All Tempus Upstream Features**: Gapless playback, ReplayGain, scrobbling (Last.fm & ListenBrainz), podcast and radio support, playlist management, instant mix, transcoding, and Android Auto / Chromecast integration.
2. **Direct HD Support**: Bypass Android HAL to produce high-resolution, bit-perfect audio output directly.
3. **USB Exclusive Mode (Direct USB DAC Access)**: Custom userspace driver that communicates directly with external USB DACs, bypassing the Android system mixer entirely to ensure raw, bit-perfect output with hardware volume control.
4. **Hi-Res FLAC Support for Older Devices via FFmpeg**: Custom FFmpeg decoding pipeline for 24-bit FLAC playback on older Android versions where system decoders downsample or mangle audio.
5. **Better Built-in Equalizer with Customizable Pre-Amp Settings**: Unified 5-band native C++ DSP engine across all audio sinks (AudioTrack, Direct HD, and USB Exclusive) featuring psychoacoustic auto pre-amp, soft-knee saturation cushion (`tanh`), dynamic/manual pre-amp toggling, per-band reset buttons, and customizable band weights & attenuation settings.

---

## Contributing

Please fork and open pull requests against the `development` branch. Ensure code builds and unit tests pass before submitting.

---

## Credits

- **[Tempus](https://github.com/eddyizm/tempus)** by [eddyizm](https://github.com/eddyizm): The upstream project that Ratempus is forked from.
- **[Tempo](https://github.com/CappielloAntonio/tempo)** by [CappielloAntonio](https://github.com/CappielloAntonio): The original foundation from which Tempus was created.
- **[SeattleGuy](https://github.com/SeattleGuy)**: Original logo design.

---

## License

Ratempus is released under the [GNU General Public License v3.0](LICENSE).
