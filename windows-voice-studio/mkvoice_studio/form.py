from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from .model import (
    StyleSource,
    ValidationError,
    VoiceMetadata,
    validate_metadata,
    validate_styles,
)


@dataclass(frozen=True)
class StyleFields:
    enabled: bool
    audio_path: str
    transcript_path: str


@dataclass(frozen=True)
class StudioFormValues:
    voice_id: str
    display_name: str
    languages: tuple[str, ...]
    creator: str
    consent_confirmed: bool
    consent_statement: str
    styles: Mapping[str, StyleFields]
    output_path: str
    ffmpeg_path: str


@dataclass(frozen=True)
class StudioBuildRequest:
    metadata: VoiceMetadata
    styles: tuple[StyleSource, ...]
    output_path: Path
    ffmpeg_path: Path | None


def build_request(values: StudioFormValues) -> StudioBuildRequest:
    metadata = validate_metadata(
        VoiceMetadata(
            voice_id=values.voice_id,
            display_name=values.display_name,
            languages=values.languages,
            creator=values.creator,
            consent_declared=values.consent_confirmed,
            consent_statement=values.consent_statement,
        )
    )

    sources: list[StyleSource] = []
    for emotion, fields in values.styles.items():
        if not fields.enabled:
            continue
        audio_path = fields.audio_path.strip()
        transcript_path = fields.transcript_path.strip()
        if not audio_path:
            raise ValidationError(
                f"styles.{emotion}.audio",
                "select an audio recording for each enabled style",
            )
        if not transcript_path:
            raise ValidationError(
                f"styles.{emotion}.transcript",
                "select a transcript for each enabled style",
            )
        sources.append(StyleSource(emotion, Path(audio_path), Path(transcript_path)))
    styles = validate_styles(sources)

    output_path = values.output_path.strip()
    if not output_path:
        raise ValidationError("output", "select a destination .mkvoice file")

    ffmpeg_path = values.ffmpeg_path.strip()
    return StudioBuildRequest(
        metadata=metadata,
        styles=styles,
        output_path=Path(output_path),
        ffmpeg_path=Path(ffmpeg_path) if ffmpeg_path else None,
    )
