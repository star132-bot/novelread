from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from mkvoice_studio.audio import AudioValidationError, inspect_wav
from mkvoice_studio.model import (
    StyleSource,
    ValidationError,
    VoiceMetadata,
    validate_metadata,
    validate_styles,
)

from tests.helpers import write_pcm_wav


class MetadataValidationTest(unittest.TestCase):
    def valid_metadata(self, **changes: object) -> VoiceMetadata:
        values: dict[str, object] = {
            "voice_id": "com.example.voice.local",
            "display_name": "My local voice",
            "languages": ("en", "zh-CN"),
            "creator": "Local creator",
            "consent_declared": True,
            "consent_statement": (
                "I am the speaker and explicitly authorize this recording "
                "for an MKread voice package."
            ),
        }
        values.update(changes)
        return VoiceMetadata(**values)

    def test_valid_metadata_is_trimmed_and_languages_are_canonical(self) -> None:
        metadata = self.valid_metadata(
            display_name="  My local voice  ",
            creator="  Local creator  ",
        )

        validated = validate_metadata(metadata)

        self.assertEqual("My local voice", validated.display_name)
        self.assertEqual("Local creator", validated.creator)
        self.assertEqual(("zh-CN", "en"), validated.languages)

    def test_invalid_id_is_rejected(self) -> None:
        invalid_ids = (
            "Example.Voice",
            "single",
            "1com.example.voice",
            "com.example._voice",
            "a." + "b" * 119,
        )
        for voice_id in invalid_ids:
            with self.subTest(voice_id=voice_id):
                with self.assertRaisesRegex(ValidationError, "id"):
                    validate_metadata(self.valid_metadata(voice_id=voice_id))

    def test_undeclared_consent_is_rejected_by_default(self) -> None:
        with self.assertRaisesRegex(ValidationError, "consent.declared"):
            validate_metadata(self.valid_metadata(consent_declared=False))

    def test_vague_consent_statement_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValidationError, "explicit authorization"):
            validate_metadata(
                self.valid_metadata(
                    consent_statement="This is a recording that I created myself."
                )
            )

    def test_indirectly_negated_authorization_is_rejected(self) -> None:
        statements = (
            "I do not have permission to use this recording for MKread.",
            "I never received explicit authorization to package this voice.",
            "Authorization for this recording has not been granted.",
            "本人没有获得授权将此录音用于制作 MKread 音色包。",
            "本人未取得许可将这段录音用于 MKread 音色制作。",
        )
        for statement in statements:
            with self.subTest(statement=statement):
                with self.assertRaisesRegex(ValidationError, "explicit authorization"):
                    validate_metadata(
                        self.valid_metadata(consent_statement=statement)
                    )

    def test_unrelated_right_word_is_not_an_authorization_statement(self) -> None:
        with self.assertRaisesRegex(ValidationError, "explicit authorization"):
            validate_metadata(
                self.valid_metadata(
                    consent_statement="The right speaker is selected for this recording."
                )
            )

    def test_authorized_non_speaker_declaration_is_accepted(self) -> None:
        statements = (
            "I am not the speaker, but I have explicit authorization "
            "to package this recording for MKread.",
            "I am not the speaker but I have explicit authorization "
            "to package this recording for MKread.",
        )
        for statement in statements:
            with self.subTest(statement=statement):
                validated = validate_metadata(
                    self.valid_metadata(consent_statement=statement)
                )

                self.assertTrue(validated.consent_declared)

    def test_chinese_explicit_authorization_is_accepted(self) -> None:
        validated = validate_metadata(
            self.valid_metadata(
                consent_statement="本人是录音者，并明确授权将此录音用于制作和使用 MKread 音色包。"
            )
        )

        self.assertTrue(validated.consent_declared)


class StyleValidationTest(unittest.TestCase):
    def test_neutral_is_required(self) -> None:
        with self.assertRaisesRegex(ValidationError, "neutral"):
            validate_styles(
                (
                    StyleSource("joy", Path("joy.wav"), Path("joy.txt")),
                )
            )

    def test_duplicate_or_unknown_emotions_are_rejected(self) -> None:
        for styles in (
            (
                StyleSource("neutral", Path("a.wav"), Path("a.txt")),
                StyleSource("neutral", Path("b.wav"), Path("b.txt")),
            ),
            (
                StyleSource("neutral", Path("a.wav"), Path("a.txt")),
                StyleSource("surprise", Path("b.wav"), Path("b.txt")),
            ),
        ):
            with self.subTest(styles=styles):
                with self.assertRaises(ValidationError):
                    validate_styles(styles)


class WaveValidationTest(unittest.TestCase):
    def test_compliant_wave_reports_duration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            wav = write_pcm_wav(Path(directory) / "neutral.wav", frames=12_000)

            info = inspect_wav(wav)

        self.assertEqual(24_000, info.sample_rate)
        self.assertEqual(1, info.channels)
        self.assertEqual(16, info.bits_per_sample)
        self.assertEqual(0.5, info.duration_seconds)

    def test_non_compliant_wave_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            wav = write_pcm_wav(Path(directory) / "stereo.wav", channels=2)

            with self.assertRaisesRegex(AudioValidationError, "mono"):
                inspect_wav(wav)

    def test_empty_and_over_sixty_second_wave_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            empty = write_pcm_wav(root / "empty.wav", frames=0)
            long = write_pcm_wav(root / "long.wav", frames=24_000 * 60 + 1)

            with self.assertRaisesRegex(AudioValidationError, "audio samples"):
                inspect_wav(empty)
            with self.assertRaisesRegex(AudioValidationError, "60 seconds"):
                inspect_wav(long)


if __name__ == "__main__":
    unittest.main()
