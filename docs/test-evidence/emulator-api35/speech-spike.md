# Emulator Speech Spike Evidence

- Observed: 2026-08-03T15:26:03+08:00
- Device: `AffectLive_API_35`
- Android API: 35
- ABI: `x86_64`
- Base commit: `4e216b7`
- Quality: `FLUENT` (4 ZipVoice steps)
- Text: `窗外下着小雨，她轻声说：“我们回家吧。”`

## Result

The debug UI completed private asset installation, native model initialization, generation, WAV validation, and `MediaPlayer.start()` without an exception. The Ready state exposed an enabled replay control.

- Output: `cache/spike/ui-preview.wav`
- WAV size: 167,980 bytes
- Audio duration: 3,498 ms
- Generation time: 2,093 ms
- Emulator real-time factor: 0.598

The real-time factor is diagnostic only. Emulator x86_64 performance is not the ARM64 device performance gate.

## Automated Checks

- `SpikeScreenTest`: passed the real install/generate flow and observed `正在生成`, `生成完成`, and enabled replay within the 120-second limit.
- `AppLaunchTest`: passed with the debug speech screen visible.
- `assembleRelease`: passed; release resources expose only `app_name` from MKread, and the release DEX contains the empty `SpikeScreen` entry without `SpikeViewModel` or `SpikeState`.
- Idle and Ready screenshots were inspected at 1080 x 2400 with no clipped or overlapping UI.

Automation confirms native output and playback startup, but it cannot make a human judgment about voice intelligibility or the selected audio device's audible volume. That listening check remains a manual acceptance item.
