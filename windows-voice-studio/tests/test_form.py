from __future__ import annotations

import unittest
from pathlib import Path

from mkvoice_studio.form import StudioFormValues, StyleFields, build_request
from mkvoice_studio.model import ValidationError


class StudioFormTest(unittest.TestCase):
    def valid_values(self, **changes: object) -> StudioFormValues:
        values: dict[str, object] = {
            "voice_id": "com.example.voice.local",
            "display_name": "  本地音色  ",
            "languages": ("zh-CN",),
            "creator": "Local creator",
            "consent_confirmed": True,
            "consent_statement": "本人是录音者，并明确授权将此录音用于制作和使用 MKread 音色包。",
            "styles": {
                "neutral": StyleFields(True, "neutral.wav", "neutral.txt"),
                "joy": StyleFields(False, "ignored.wav", "ignored.txt"),
                "sadness": StyleFields(True, "sad.wav", "sad.txt"),
            },
            "output_path": "voice.mkvoice",
            "ffmpeg_path": "  ",
        }
        values.update(changes)
        return StudioFormValues(**values)

    def test_request_uses_enabled_styles_in_canonical_order(self) -> None:
        request = build_request(self.valid_values())

        self.assertEqual("本地音色", request.metadata.display_name)
        self.assertEqual(("neutral", "sadness"), tuple(item.emotion for item in request.styles))
        self.assertEqual(Path("neutral.wav"), request.styles[0].audio_path)
        self.assertEqual(Path("voice.mkvoice"), request.output_path)
        self.assertIsNone(request.ffmpeg_path)

    def test_enabled_style_requires_both_audio_and_transcript(self) -> None:
        values = self.valid_values(
            styles={"neutral": StyleFields(True, "neutral.wav", "  ")}
        )

        with self.assertRaisesRegex(ValidationError, "styles.neutral.transcript"):
            build_request(values)

    def test_output_path_is_required_before_build_starts(self) -> None:
        with self.assertRaisesRegex(ValidationError, "output"):
            build_request(self.valid_values(output_path="  "))


if __name__ == "__main__":
    unittest.main()
