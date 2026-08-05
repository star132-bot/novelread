from __future__ import annotations

import subprocess
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

FFMPEG_TIMEOUT_SECONDS = 120


class AudioValidationError(ValueError):
    """A WAV validation error that never includes recording content or paths."""


class AudioConversionError(ValueError):
    """A sanitized failure to normalize a recording with ffmpeg."""


@dataclass(frozen=True)
class WavInfo:
    sample_rate: int
    channels: int
    bits_per_sample: int
    frame_count: int
    duration_seconds: float


def inspect_wav(path: Path) -> WavInfo:
    try:
        with wave.open(str(path), "rb") as source:
            channels = source.getnchannels()
            sample_width = source.getsampwidth()
            sample_rate = source.getframerate()
            frame_count = source.getnframes()
            compression = source.getcomptype()

            if compression != "NONE":
                raise AudioValidationError("audio must be uncompressed PCM WAV")
            if channels != 1:
                raise AudioValidationError("audio must be mono")
            if sample_width != 2:
                raise AudioValidationError("audio must use 16-bit PCM samples")
            if sample_rate != 24_000:
                raise AudioValidationError("audio sample rate must be 24000 Hz")
            if frame_count <= 0:
                raise AudioValidationError("audio must contain audio samples")
            if frame_count > sample_rate * 60:
                raise AudioValidationError("audio must not exceed 60 seconds")

            frame_bytes = source.readframes(frame_count)
            if len(frame_bytes) != frame_count * channels * sample_width:
                raise AudioValidationError("audio data is truncated")
    except AudioValidationError:
        raise
    except FileNotFoundError as error:
        raise AudioValidationError("selected audio file does not exist") from error
    except (EOFError, OSError, wave.Error) as error:
        raise AudioValidationError("selected audio is not a readable PCM WAV file") from error

    return WavInfo(
        sample_rate=sample_rate,
        channels=channels,
        bits_per_sample=sample_width * 8,
        frame_count=frame_count,
        duration_seconds=frame_count / sample_rate,
    )


def normalize_audio(
    source: Path,
    destination: Path,
    *,
    ffmpeg_path: Path | None,
    runner: Callable[..., subprocess.CompletedProcess[bytes]] = subprocess.run,
) -> WavInfo:
    try:
        info = inspect_wav(source)
    except AudioValidationError as validation_error:
        if ffmpeg_path is None:
            raise AudioConversionError(
                "audio needs conversion; select a local ffmpeg executable"
            ) from validation_error
    else:
        _rewrite_pcm_wav(source, destination, info)
        try:
            return inspect_wav(destination)
        except AudioValidationError as error:
            destination.unlink(missing_ok=True)
            raise AudioConversionError(
                "staged audio did not meet the MKread WAV contract"
            ) from error

    command = [
        ffmpeg_path.as_posix(),
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "error",
        "-protocol_whitelist",
        "file",
        "-y",
        "-i",
        source.as_posix(),
        "-map_metadata",
        "-1",
        "-vn",
        "-ac",
        "1",
        "-ar",
        "24000",
        "-c:a",
        "pcm_s16le",
        destination.as_posix(),
    ]
    try:
        completed = runner(
            command,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=False,
            timeout=FFMPEG_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as error:
        destination.unlink(missing_ok=True)
        raise AudioConversionError("ffmpeg conversion timed out") from error
    except OSError as error:
        raise AudioConversionError("could not start the selected ffmpeg executable") from error

    if completed.returncode != 0:
        destination.unlink(missing_ok=True)
        raise AudioConversionError("ffmpeg could not convert the selected audio")

    try:
        return inspect_wav(destination)
    except AudioValidationError as error:
        destination.unlink(missing_ok=True)
        raise AudioConversionError("ffmpeg output did not meet the MKread WAV contract") from error


def _rewrite_pcm_wav(source: Path, destination: Path, info: WavInfo) -> None:
    try:
        with wave.open(str(source), "rb") as input_wave:
            frame_bytes = input_wave.readframes(info.frame_count)
        expected_bytes = (
            info.frame_count * info.channels * (info.bits_per_sample // 8)
        )
        if len(frame_bytes) != expected_bytes:
            raise AudioConversionError("selected audio changed while it was being staged")
        with wave.open(str(destination), "wb") as output_wave:
            output_wave.setnchannels(info.channels)
            output_wave.setsampwidth(info.bits_per_sample // 8)
            output_wave.setframerate(info.sample_rate)
            output_wave.writeframes(frame_bytes)
    except (EOFError, OSError, wave.Error) as error:
        destination.unlink(missing_ok=True)
        raise AudioConversionError("could not stage the selected audio") from error
