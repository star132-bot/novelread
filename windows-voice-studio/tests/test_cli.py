from __future__ import annotations

import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from mkvoice_studio.adb import SendResult, SendState
from mkvoice_studio.cli import main

from tests.helpers import write_pcm_wav


class CliBuildTest(unittest.TestCase):
    def build_arguments(self, root: Path, *, send: bool = False) -> list[str]:
        audio = write_pcm_wav(root / "neutral reference.wav", frames=2_400)
        transcript = root / "neutral transcript.txt"
        transcript.write_text("你好，这里是离线音色参考。", encoding="utf-8")
        arguments = [
            "build",
            "--id",
            "com.example.voice.local",
            "--name",
            "本地音色",
            "--language",
            "zh-CN",
            "--creator",
            "Local creator",
            "--confirm-authorized-recording",
            "--consent-statement",
            "本人是录音者，并明确授权将此录音用于制作和使用 MKread 音色包。",
            "--style",
            "neutral",
            str(audio),
            str(transcript),
            "--output",
            str(root / "local voice.mkvoice"),
        ]
        if send:
            arguments.append("--send")
        return arguments

    def test_build_command_creates_package_and_reports_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stdout = io.StringIO()
            stderr = io.StringIO()

            exit_code = main(self.build_arguments(root), stdout=stdout, stderr=stderr)

            output = root / "local voice.mkvoice"
            self.assertEqual(0, exit_code)
            self.assertTrue(output.is_file())
            self.assertEqual("", stderr.getvalue())
            self.assertIn("SHA-256", stdout.getvalue())
            with zipfile.ZipFile(output) as archive:
                self.assertIn("manifest.json", archive.namelist())

    def test_build_without_explicit_consent_fails_without_creating_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            arguments = self.build_arguments(root)
            arguments.remove("--confirm-authorized-recording")
            stderr = io.StringIO()

            exit_code = main(arguments, stdout=io.StringIO(), stderr=stderr)

            self.assertEqual(2, exit_code)
            self.assertFalse((root / "local voice.mkvoice").exists())
            self.assertIn("consent.declared", stderr.getvalue())

    @patch("mkvoice_studio.cli.discover_adb", return_value=Path("C:/tools/adb.exe"))
    @patch("mkvoice_studio.cli.AdbClient")
    def test_build_and_send_reports_partial_delivery_as_nonzero(
        self,
        client_type: object,
        _discover: object,
    ) -> None:
        client_type.return_value.send_package.return_value = SendResult(
            state=SendState.PUSHED_NO_RECEIVER,
            remote_path="/sdcard/Download/local voice.mkvoice",
            message="已推送，但尚未导入。",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stdout = io.StringIO()

            exit_code = main(
                self.build_arguments(root, send=True),
                stdout=stdout,
                stderr=io.StringIO(),
            )

        self.assertEqual(3, exit_code)
        self.assertIn("尚未导入", stdout.getvalue())
        client_type.return_value.send_package.assert_called_once()


class CliSendTest(unittest.TestCase):
    @patch("mkvoice_studio.cli.discover_adb", return_value=Path("C:/tools/adb.exe"))
    @patch("mkvoice_studio.cli.AdbClient")
    def test_existing_voice_requires_explicit_replace_and_returns_partial_delivery(
        self,
        client_type: object,
        _discover: object,
    ) -> None:
        client_type.return_value.send_package.return_value = SendResult(
            state=SendState.PUSHED_REPLACE_REQUIRED,
            remote_path="/sdcard/Download/local.mkvoice",
            message="Use --replace-existing after confirmation.",
        )
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory) / "local.mkvoice"
            package.write_bytes(b"package")
            stdout = io.StringIO()

            exit_code = main(
                ["send", str(package)],
                stdout=stdout,
                stderr=io.StringIO(),
            )

        self.assertEqual(3, exit_code)
        self.assertIn("--replace-existing", stdout.getvalue())

    @patch("mkvoice_studio.cli.discover_adb", return_value=Path("C:/tools/adb.exe"))
    @patch("mkvoice_studio.cli.AdbClient")
    def test_confirmed_provider_import_is_successful(
        self,
        client_type: object,
        _discover: object,
    ) -> None:
        client_type.return_value.send_package.return_value = SendResult(
            state=SendState.IMPORTED,
            remote_path="/sdcard/Download/local.mkvoice",
            message="已确认导入。",
            import_confirmed=True,
        )
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory) / "local.mkvoice"
            package.write_bytes(b"package")
            stdout = io.StringIO()

            exit_code = main(
                ["send", str(package), "--serial", "emulator-5554"],
                stdout=stdout,
                stderr=io.StringIO(),
            )

        self.assertEqual(0, exit_code)
        self.assertIn("确认导入", stdout.getvalue())
        client_type.return_value.send_package.assert_called_once_with(
            package,
            replace_existing=False,
        )

    @patch("mkvoice_studio.cli.discover_adb", return_value=Path("C:/tools/adb.exe"))
    @patch("mkvoice_studio.cli.AdbClient")
    def test_send_requires_explicit_flag_to_replace_existing_voice(
        self,
        client_type: object,
        _discover: object,
    ) -> None:
        client_type.return_value.send_package.return_value = SendResult(
            state=SendState.IMPORTED,
            remote_path="/sdcard/Download/local.mkvoice",
            message="confirmed",
            import_confirmed=True,
        )
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory) / "local.mkvoice"
            package.write_bytes(b"package")

            exit_code = main(
                ["send", str(package), "--replace-existing"],
                stdout=io.StringIO(),
                stderr=io.StringIO(),
            )

        self.assertEqual(0, exit_code)
        client_type.return_value.send_package.assert_called_once_with(
            package,
            replace_existing=True,
        )

    @patch("mkvoice_studio.cli.discover_adb", return_value=Path("C:/tools/adb.exe"))
    @patch("mkvoice_studio.cli.AdbClient")
    def test_launch_request_is_successful_but_never_claims_import_confirmation(
        self,
        client_type: object,
        _discover: object,
    ) -> None:
        client_type.return_value.send_package.return_value = SendResult(
            state=SendState.LAUNCH_REQUESTED,
            remote_path="/sdcard/Download/local.mkvoice",
            message="已请求 MKread 打开；无法确认已导入。",
            launch_requested=True,
        )
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory) / "local.mkvoice"
            package.write_bytes(b"package")
            stdout = io.StringIO()

            exit_code = main(
                ["send", str(package), "--serial", "emulator-5554"],
                stdout=stdout,
                stderr=io.StringIO(),
            )

        self.assertEqual(0, exit_code)
        self.assertIn("无法确认已导入", stdout.getvalue())
        client_type.assert_called_once_with(
            Path("C:/tools/adb.exe"),
            serial="emulator-5554",
        )


if __name__ == "__main__":
    unittest.main()
