from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from mkvoice_studio.audio import AudioConversionError, normalize_audio
from mkvoice_studio.model import StyleSource, VoiceMetadata
from mkvoice_studio.package import (
    OutputExistsError,
    TranscriptValidationError,
    build_package,
    canonical_json_bytes,
)

from tests.helpers import write_pcm_wav


class AudioNormalizationTest(unittest.TestCase):
    def test_compliant_wave_is_copied_without_running_ffmpeg(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = write_pcm_wav(root / "source.wav")
            destination = root / "normalized.wav"

            def unexpected_runner(*args: object, **kwargs: object) -> object:
                self.fail("ffmpeg must not run for an already compliant WAV")

            normalize_audio(source, destination, ffmpeg_path=None, runner=unexpected_runner)

            self.assertEqual(source.read_bytes(), destination.read_bytes())

    def test_non_compliant_wave_uses_argument_array_without_shell(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = write_pcm_wav(root / "source.wav", sample_rate=16_000)
            destination = root / "normalized.wav"
            calls: list[tuple[list[str], dict[str, object]]] = []

            def fake_runner(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[bytes]:
                calls.append((command, kwargs))
                write_pcm_wav(destination)
                return subprocess.CompletedProcess(command, 0, b"", b"")

            normalize_audio(
                source,
                destination,
                ffmpeg_path=Path("C:/tools/ffmpeg.exe"),
                runner=fake_runner,
            )

        self.assertEqual("C:/tools/ffmpeg.exe", calls[0][0][0])
        self.assertIn("pcm_s16le", calls[0][0])
        protocol_index = calls[0][0].index("-protocol_whitelist")
        self.assertEqual("file", calls[0][0][protocol_index + 1])
        self.assertLess(protocol_index, calls[0][0].index("-i"))
        self.assertIs(calls[0][1]["shell"], False)
        self.assertEqual(120, calls[0][1]["timeout"])
        self.assertIsInstance(calls[0][0], list)

    def test_conversion_needed_without_ffmpeg_has_safe_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = write_pcm_wav(root / "private-recording.wav", sample_rate=16_000)

            with self.assertRaises(AudioConversionError) as raised:
                normalize_audio(source, root / "output.wav", ffmpeg_path=None)

        self.assertNotIn("private-recording", str(raised.exception))

    def test_ffmpeg_timeout_has_safe_error_and_removes_partial_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = write_pcm_wav(root / "private-recording.wav", sample_rate=16_000)
            destination = root / "normalized.wav"

            def timed_out(command: list[str], **kwargs: object) -> object:
                destination.write_bytes(b"partial")
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])

            with self.assertRaisesRegex(AudioConversionError, "timed out") as raised:
                normalize_audio(
                    source,
                    destination,
                    ffmpeg_path=Path("C:/tools/ffmpeg.exe"),
                    runner=timed_out,
                )

            self.assertFalse(destination.exists())
            self.assertNotIn("private-recording", str(raised.exception))

    def test_compliant_wave_is_rewritten_without_trailing_hidden_data(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = write_pcm_wav(root / "source.wav", frames=2_400)
            marker = b"private-trailing-payload" * 1_024
            with source.open("ab") as output:
                output.write(marker)
            destination = root / "normalized.wav"

            normalize_audio(source, destination, ffmpeg_path=None)

            self.assertLess(destination.stat().st_size, source.stat().st_size)
            self.assertNotIn(b"private-trailing-payload", destination.read_bytes())

    def test_rewritten_wave_is_revalidated_before_packaging(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = write_pcm_wav(root / "source.wav", frames=2_400)
            destination = root / "normalized.wav"

            def write_invalid_output(*_args: object) -> None:
                destination.write_bytes(b"not a wave")

            with patch(
                "mkvoice_studio.audio._rewrite_pcm_wav",
                side_effect=write_invalid_output,
            ):
                with self.assertRaisesRegex(AudioConversionError, "staged audio"):
                    normalize_audio(source, destination, ffmpeg_path=None)

            self.assertFalse(destination.exists())


class PackageBuildTest(unittest.TestCase):
    def valid_metadata(self) -> VoiceMetadata:
        return VoiceMetadata(
            voice_id="com.example.voice.local",
            display_name="Local Voice",
            languages=("zh-CN", "en"),
            creator="Local creator",
            consent_declared=True,
            consent_statement=(
                "I am the speaker and explicitly authorize this recording "
                "for an MKread voice package."
            ),
        )

    def make_style(self, root: Path, emotion: str = "neutral") -> StyleSource:
        audio = write_pcm_wav(root / f"{emotion}.wav", frames=2_400)
        transcript = root / f"{emotion}.txt"
        transcript.write_bytes("  Hello\r\nworld  \r\n".encode("utf-8"))
        return StyleSource(emotion, audio, transcript)

    def test_build_is_deterministic_and_contains_canonical_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            styles = (self.make_style(root), self.make_style(root, "joy"))
            first = root / "first.mkvoice"
            second = root / "second.mkvoice"

            result = build_package(self.valid_metadata(), styles, first)
            build_package(self.valid_metadata(), styles, second)

            self.assertEqual(first, result.output_path)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(
                    [
                        "checksums.json",
                        "manifest.json",
                        "prompts/joy.txt",
                        "prompts/joy.wav",
                        "prompts/neutral.txt",
                        "prompts/neutral.wav",
                    ],
                    archive.namelist(),
                )
                manifest_bytes = archive.read("manifest.json")
                manifest = json.loads(manifest_bytes)
                checksums_bytes = archive.read("checksums.json")
                checksums = json.loads(checksums_bytes)

                self.assertEqual(canonical_json_bytes(manifest), manifest_bytes)
                self.assertEqual(canonical_json_bytes(checksums), checksums_bytes)
                self.assertEqual(1, manifest["schemaVersion"])
                self.assertEqual("zipvoice-distill-int8-zh-en", manifest["engine"])
                self.assertEqual("checksums.json", manifest["checksums"])
                self.assertEqual(["neutral", "joy"], [item["emotion"] for item in manifest["styles"]])
                self.assertEqual(b"Hello\nworld", archive.read("prompts/neutral.txt"))
                for payload_path, checksum in checksums.items():
                    self.assertRegex(checksum, r"^[0-9a-f]{64}$")
                    self.assertEqual(
                        hashlib.sha256(archive.read(payload_path)).hexdigest(),
                        checksum,
                    )

    def test_existing_output_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "voice.mkvoice"
            output.write_bytes(b"keep me")

            with self.assertRaises(OutputExistsError):
                build_package(self.valid_metadata(), (self.make_style(root),), output)

            self.assertEqual(b"keep me", output.read_bytes())

    def test_blank_or_invalid_utf8_transcript_is_rejected_without_echoing_content(self) -> None:
        for payload in (b" \r\n\t", b"private words \xff"):
            with self.subTest(payload=payload):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    style = self.make_style(root)
                    style.transcript_path.write_bytes(payload)

                    with self.assertRaises(TranscriptValidationError) as raised:
                        build_package(self.valid_metadata(), (style,), root / "voice.mkvoice")

                    self.assertNotIn("private words", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
