from __future__ import annotations

import wave
from pathlib import Path


def write_pcm_wav(
    path: Path,
    *,
    channels: int = 1,
    sample_width: int = 2,
    sample_rate: int = 24_000,
    frames: int = 240,
) -> Path:
    sample = b"\x00" * sample_width * channels
    with wave.open(str(path), "wb") as output:
        output.setnchannels(channels)
        output.setsampwidth(sample_width)
        output.setframerate(sample_rate)
        output.writeframes(sample * frames)
    return path
