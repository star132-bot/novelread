from __future__ import annotations

import re
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Iterable

ENGINE = "zipvoice-distill-int8-zh-en"
EMOTIONS = ("neutral", "joy", "sadness", "anger", "tension")
LANGUAGES = ("zh-CN", "en")

_ID_PATTERN = re.compile(r"[a-z][a-z0-9]*(?:\.[a-z][a-z0-9-]*)+", re.ASCII)
_ENGLISH_NEGATION = re.compile(
    r"(?:"
    r"\b(?:no|without|lack(?:s|ed|ing)?)\b[^\n,.!?;，。！？；]{0,30}"
    r"\b(?:authorization|permission|consent|licen[cs]e|rights?)\b"
    r"|\b(?:not|never|cannot|can't)\b[^\n,.!?;，。！？；]{0,20}"
    r"\b(?:have|hold|possess|receive|obtain|grant|give|authoriz)\w*\b"
    r"[^\n,.!?;，。！？；]{0,30}"
    r"(?:\b(?:authorization|permission|consent|licen[cs]e|rights?)\b)?"
    r"|\b(?:authorization|permission|consent|licen[cs]e|rights?)\b"
    r"[^\n,.!?;，。！？；]{0,40}\b(?:has|have|is|was|were)?\s*not\s+"
    r"(?:been\s+)?(?:granted|given|obtained|received|authorized)\b"
    r")",
    re.IGNORECASE,
)
_ENGLISH_AUTHORIZATION = re.compile(
    r"(?:"
    r"\b(?:I|we)\s+(?:am|are)\s+the\s+(?:speaker|recording owner|rights holder)\b"
    r"[^\n.!?;，。！？；]{0,80}\b(?:explicitly\s+)?authoriz(?:e|ed)\b"
    r"|\b(?:I|we)\s+(?:have|hold|possess|obtained|received)\s+"
    r"(?:the\s+)?(?:explicit\s+)?(?:authorization|permission|consent|right\s+to\s+use)\b"
    r"|\b(?:I|we)\s+(?:am|are)\s+(?:explicitly\s+)?authorized\b"
    r"|\b(?:the\s+)?(?:speaker|creator|recording owner|rights holder)\s+"
    r"(?:declares|grants|gave|has\s+given)\b[^\n.!?;，。！？；]{0,40}"
    r"\b(?:authorization|permission|consent)\b"
    r"|\b(?:authorization|permission|consent)\b[^\n.!?;，。！？；]{0,40}"
    r"\b(?:has\s+been|was)\s+(?:explicitly\s+)?granted\b"
    r")",
    re.IGNORECASE,
)
_ENGLISH_CLAUSE_SEPARATOR = re.compile(
    r"[,.;!?，。！？；]|\b(?:but|however|although)\b",
    re.IGNORECASE,
)
_CHINESE_NEGATION = re.compile(
    r"(?:"
    r"(?:未|未经|无|没有|不)[^\n，。！？；;]{0,12}(?:授权|权利|许可)"
    r"|(?:无权|没有权)"
    r"|(?:授权|许可|权利)[^\n，。！？；;]{0,8}(?:尚未|未曾|没有)"
    r"(?:获得|取得|授予|确认|生效)?"
    r")"
)
_CHINESE_AUTHORIZATION = re.compile(
    r"(?:"
    r"(?:本人|我)[^\n。！？；;]{0,24}(?:明确)?授权"
    r"|(?:本人|我)[^\n。！？；;]{0,8}(?:获得|取得|拥有|得到)"
    r"[^\n。！？；;]{0,8}(?:授权|许可)"
    r"|(?:录音者|权利人)[^\n。！？；;]{0,12}(?:授权|许可)"
    r"|(?:本人|我)[^\n。！？；;]{0,6}有权"
    r")"
)


class ValidationError(ValueError):
    """A safe validation error that names a field without echoing user content."""

    def __init__(self, field: str, message: str) -> None:
        self.field = field
        self.message = message
        super().__init__(f"{field}: {message}")


@dataclass(frozen=True)
class VoiceMetadata:
    voice_id: str
    display_name: str
    languages: tuple[str, ...]
    creator: str
    consent_declared: bool
    consent_statement: str


@dataclass(frozen=True)
class StyleSource:
    emotion: str
    audio_path: Path
    transcript_path: Path


def validate_metadata(metadata: VoiceMetadata) -> VoiceMetadata:
    voice_id = metadata.voice_id.strip()
    if len(voice_id) > 120 or _ID_PATTERN.fullmatch(voice_id) is None:
        raise ValidationError(
            "id",
            "must match ^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$ and be at most 120 characters",
        )

    display_name = metadata.display_name.strip()
    if not display_name or len(display_name) > 80:
        raise ValidationError("displayName", "must contain 1-80 Unicode code points")

    creator = metadata.creator.strip()
    if not creator or len(creator) > 200:
        raise ValidationError("creator", "must contain 1-200 Unicode code points")

    languages = tuple(language.strip() for language in metadata.languages)
    if not languages:
        raise ValidationError("languages", "must contain zh-CN, en, or both")
    if len(set(languages)) != len(languages) or any(
        language not in LANGUAGES for language in languages
    ):
        raise ValidationError("languages", "contains an unsupported or duplicate language")
    canonical_languages = tuple(language for language in LANGUAGES if language in languages)

    if metadata.consent_declared is not True:
        raise ValidationError(
            "consent.declared",
            "must be explicitly confirmed for the speaker or authorized voice",
        )

    statement = metadata.consent_statement.strip()
    if not 10 <= len(statement) <= 500:
        raise ValidationError("consent.statement", "must contain 10-500 Unicode code points")
    if not _states_explicit_authorization(statement):
        raise ValidationError(
            "consent.statement",
            "must contain an explicit authorization or permission statement",
        )

    return replace(
        metadata,
        voice_id=voice_id,
        display_name=display_name,
        languages=canonical_languages,
        creator=creator,
        consent_statement=statement,
    )


def validate_styles(styles: Iterable[StyleSource]) -> tuple[StyleSource, ...]:
    supplied = tuple(styles)
    if not supplied:
        raise ValidationError("styles", "a neutral style is required")

    seen: set[str] = set()
    for style in supplied:
        if style.emotion not in EMOTIONS:
            raise ValidationError("styles.emotion", "contains an unsupported emotion")
        if style.emotion in seen:
            raise ValidationError("styles.emotion", "contains a duplicate emotion")
        seen.add(style.emotion)

    if "neutral" not in seen:
        raise ValidationError("styles", "exactly one neutral style is required")

    return tuple(
        next(style for style in supplied if style.emotion == emotion)
        for emotion in EMOTIONS
        if emotion in seen
    )


def _states_explicit_authorization(statement: str) -> bool:
    english_clauses = _ENGLISH_CLAUSE_SEPARATOR.split(statement)
    if any(_ENGLISH_NEGATION.search(clause) for clause in english_clauses):
        return False
    if _CHINESE_NEGATION.search(statement):
        return False
    return bool(
        _ENGLISH_AUTHORIZATION.search(statement)
        or _CHINESE_AUTHORIZATION.search(statement)
    )
