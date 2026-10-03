from __future__ import annotations

import hashlib
import json
import os
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from .audio import normalize_audio
from .model import ENGINE, StyleSource, VoiceMetadata, validate_metadata, validate_styles

MAX_TRANSCRIPT_BYTES = 64 * 1024
MAX_TRANSCRIPT_CODEPOINTS = 10_000


class PackageBuildError(ValueError):
    """A sanitized package build error."""


class OutputExistsError(PackageBuildError):
    pass


class TranscriptValidationError(PackageBuildError):
    pass


@dataclass(frozen=True)
class BuildResult:
    output_path: Path
    package_sha256: str
    emotions: tuple[str, ...]


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def build_package(
    metadata: VoiceMetadata,
    styles: tuple[StyleSource, ...],
    output_path: Path,
    *,
    ffmpeg_path: Path | None = None,
    runner: Callable[..., Any] | None = None,
) -> BuildResult:
    metadata = validate_metadata(metadata)
    styles = validate_styles(styles)
    output_path = Path(output_path)
    if output_path.suffix.lower() != ".mkvoice":
        raise PackageBuildError("output: filename must end in .mkvoice")
    if output_path.exists():
        raise OutputExistsError("output: selected .mkvoice already exists; choose a new filename")

    output_parent = output_path.parent
    try:
        output_parent.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise PackageBuildError("output: destination directory could not be created") from error

    temp_zip: Path | None = None
    try:
        with tempfile.TemporaryDirectory(prefix=".mkvoice-stage-", dir=output_parent) as stage_name:
            stage = Path(stage_name)
            payloads: dict[str, bytes] = {}
            manifest_styles: list[dict[str, object]] = []

            for style in styles:
                audio_source = Path(style.audio_path)
                transcript_source = Path(style.transcript_path)
                if not audio_source.is_file():
                    raise PackageBuildError("audio: a selected WAV file does not exist")
                if not transcript_source.is_file():
                    raise TranscriptValidationError(
                        "transcript: a selected UTF-8 text file does not exist"
                    )

                audio_archive_path = f"prompts/{style.emotion}.wav"
                transcript_archive_path = f"prompts/{style.emotion}.txt"
                normalized_audio = stage / f"{style.emotion}.wav"
                normalize_kwargs: dict[str, object] = {"ffmpeg_path": ffmpeg_path}
                if runner is not None:
                    normalize_kwargs["runner"] = runner
                normalize_audio(audio_source, normalized_audio, **normalize_kwargs)
                try:
                    payloads[audio_archive_path] = normalized_audio.read_bytes()
                except OSError as error:
                    raise PackageBuildError("audio: normalized data could not be read") from error
                payloads[transcript_archive_path] = _read_transcript(transcript_source)

                manifest_styles.append(
                    {
                        "emotion": style.emotion,
                        "audio": audio_archive_path,
                        "transcript": transcript_archive_path,
                        "sampleRate": 24_000,
                    }
                )

            checksums = {
                path: hashlib.sha256(data).hexdigest()
                for path, data in sorted(payloads.items())
            }
            manifest = {
                "schemaVersion": 1,
                "id": metadata.voice_id,
                "displayName": metadata.display_name,
                "engine": ENGINE,
                "languages": list(metadata.languages),
                "creator": metadata.creator,
                "consent": {
                    "declared": metadata.consent_declared,
                    "statement": metadata.consent_statement,
                },
                "styles": manifest_styles,
                "checksums": "checksums.json",
            }
            entries = dict(payloads)
            entries["checksums.json"] = canonical_json_bytes(checksums)
            entries["manifest.json"] = canonical_json_bytes(manifest)

            descriptor, temp_name = tempfile.mkstemp(
                prefix=f".{output_path.name}.", suffix=".tmp", dir=output_parent
            )
            os.close(descriptor)
            temp_zip = Path(temp_name)
            _write_deterministic_zip(temp_zip, entries)
            package_sha256 = _sha256_file(temp_zip)
            _publish_without_replacing(temp_zip, output_path)
            temp_zip = None

        return BuildResult(
            output_path=output_path,
            package_sha256=package_sha256,
            emotions=tuple(style.emotion for style in styles),
        )
    except OutputExistsError:
        raise
    except PackageBuildError:
        raise
    except OSError as error:
        raise PackageBuildError("package could not be written safely") from error
    finally:
        if temp_zip is not None:
            temp_zip.unlink(missing_ok=True)


def _read_transcript(path: Path) -> bytes:
    try:
        payload = path.read_bytes()
    except OSError as error:
        raise TranscriptValidationError("transcript: selected text could not be read") from error
    if len(payload) > MAX_TRANSCRIPT_BYTES:
        raise TranscriptValidationError("transcript: UTF-8 text must not exceed 64 KiB")
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError as error:
        raise TranscriptValidationError("transcript: text must be strict UTF-8") from error

    normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip()
    if not normalized:
        raise TranscriptValidationError("transcript: UTF-8 text must not be blank")
    if "\x00" in normalized:
        raise TranscriptValidationError("transcript: NUL characters are not allowed")
    if len(normalized) > MAX_TRANSCRIPT_CODEPOINTS:
        raise TranscriptValidationError(
            "transcript: text must not exceed 10000 Unicode code points"
        )
    return normalized.encode("utf-8")


def _write_deterministic_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for archive_path in sorted(entries):
            info = zipfile.ZipInfo(archive_path, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, entries[archive_path])

    with path.open("r+b") as package:
        os.fsync(package.fileno())


def _publish_without_replacing(source: Path, destination: Path) -> None:
    try:
        if os.name == "nt":
            os.rename(source, destination)
        else:
            os.link(source, destination)
            source.unlink()
    except FileExistsError as error:
        raise OutputExistsError(
            "output: selected .mkvoice already exists; choose a new filename"
        ) from error
    except OSError as error:
        if getattr(error, "winerror", None) in {80, 183}:
            raise OutputExistsError(
                "output: selected .mkvoice already exists; choose a new filename"
            ) from error
        raise


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
