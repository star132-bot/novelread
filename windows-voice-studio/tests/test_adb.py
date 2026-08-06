from __future__ import annotations

import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path

from mkvoice_studio.adb import (
    AdbClient,
    AdbError,
    SendState,
    discover_adb,
)


class AdbDiscoveryTest(unittest.TestCase):
    def test_explicit_adb_path_wins(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "adb.exe"
            executable.touch()

            found = discover_adb(executable, env={}, which=lambda _: None)

        self.assertEqual(executable, found)

    def test_android_sdk_environment_is_searched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "platform-tools" / "adb.exe"
            executable.parent.mkdir()
            executable.touch()

            found = discover_adb(None, env={"ANDROID_SDK_ROOT": str(root)}, which=lambda _: None)

        self.assertEqual(executable, found)


class AdbSendTest(unittest.TestCase):
    def make_package(self, root: Path) -> Path:
        package = root / "local-voice.mkvoice"
        package.write_bytes(b"package")
        return package

    def fake_runner(
        self,
        *,
        receiver: bool,
        installed: bool = True,
        provider: bool = False,
        provider_error: str | None = None,
    ):
        calls: list[tuple[list[str], dict[str, object]]] = []

        def runner(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
            calls.append((command, kwargs))
            joined = " ".join(command)
            if joined.endswith("devices -l"):
                return subprocess.CompletedProcess(
                    command,
                    0,
                    "List of devices attached\nemulator-5554 device product:sdk\n",
                    "",
                )
            if " push " in f" {joined} ":
                return subprocess.CompletedProcess(command, 0, "1 file pushed", "")
            if "pm path com.mkread.app" in joined:
                stdout = "package:/data/app/com.mkread.app/base.apk\n" if installed else ""
                return subprocess.CompletedProcess(command, 0 if installed else 1, stdout, "")
            if "content write --uri" in joined:
                provider_available = provider or provider_error is not None
                return subprocess.CompletedProcess(
                    command,
                    0 if provider_available else 1,
                    "" if provider_available else "No provider found",
                    "",
                )
            if "content call" in joined:
                if provider_error is not None:
                    return subprocess.CompletedProcess(
                        command,
                        0,
                        f"Bundle[{{status=error, errorCode={provider_error}}}]\n",
                        "",
                    )
                stdout = "Bundle[{status=ok, voiceId=com.local.test}]\n" if provider else ""
                return subprocess.CompletedProcess(command, 0 if provider else 1, stdout, "")
            if "query-activities" in joined:
                stdout = "com.mkread.app/.VoiceImportActivity\n" if receiver else "No activities found\n"
                return subprocess.CompletedProcess(command, 0, stdout, "")
            if "am start" in joined:
                return subprocess.CompletedProcess(command, 0, "Status: ok\n", "")
            raise AssertionError(f"unexpected command shape: {command!r}")

        return runner, calls

    def test_adb_provider_stream_confirms_import_without_uri_grant(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            runner, calls = self.fake_runner(receiver=False, provider=True)
            client = AdbClient(Path("C:/tools/adb.exe"), runner=runner)

            result = client.send_package(package)

        self.assertEqual(SendState.IMPORTED, result.state)
        self.assertTrue(result.pushed)
        self.assertTrue(result.import_confirmed)
        self.assertFalse(result.launch_requested)
        self.assertIn("确认导入", result.message)
        joined_calls = [" ".join(call[0]) for call in calls]
        self.assertTrue(any("content write --uri" in call for call in joined_calls))
        self.assertTrue(any("content call" in call for call in joined_calls))
        self.assertTrue(any("--method import" in call for call in joined_calls))
        self.assertFalse(any("--method import_replace" in call for call in joined_calls))
        self.assertFalse(any("query-activities" in call for call in joined_calls))
        self.assertFalse(any("am start" in call for call in joined_calls))
        self.assertTrue(all(call[1]["shell"] is False for call in calls))

    def test_explicit_replace_uses_separate_provider_method(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            runner, calls = self.fake_runner(receiver=False, provider=True)
            client = AdbClient(Path("C:/tools/adb.exe"), runner=runner)

            result = client.send_package(package, replace_existing=True)

        self.assertEqual(SendState.IMPORTED, result.state)
        provider_call = next(
            " ".join(call[0]) for call in calls if "content call" in " ".join(call[0])
        )
        self.assertIn("--method import_replace", provider_call)

    def test_existing_voice_requires_explicit_replace_without_launching_an_activity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            runner, calls = self.fake_runner(
                receiver=True,
                provider_error="destination_exists",
            )
            client = AdbClient(Path("C:/tools/adb.exe"), runner=runner)

            result = client.send_package(package)

        self.assertEqual(SendState.PUSHED_REPLACE_REQUIRED, result.state)
        self.assertFalse(result.import_confirmed)
        self.assertFalse(result.launch_requested)
        self.assertIn("--replace-existing", result.message)
        joined_calls = [" ".join(call[0]) for call in calls]
        self.assertFalse(any("query-activities" in call for call in joined_calls))
        self.assertFalse(any("am start" in call for call in joined_calls))

    def test_provider_validation_failure_is_reported_without_external_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            runner, calls = self.fake_runner(
                receiver=True,
                provider_error="checksum_mismatch",
            )

            result = AdbClient(Path("adb.exe"), runner=runner).send_package(package)

        self.assertEqual(SendState.PUSHED_IMPORT_REJECTED, result.state)
        self.assertIn("拒绝", result.message)
        joined_calls = [" ".join(call[0]) for call in calls]
        self.assertFalse(any("query-activities" in call for call in joined_calls))
        self.assertFalse(any("am start" in call for call in joined_calls))

    def test_missing_receiver_reports_pushed_but_not_imported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            runner, calls = self.fake_runner(receiver=False)
            client = AdbClient(Path("C:/tools/adb.exe"), runner=runner)

            result = client.send_package(package)

        self.assertEqual(SendState.PUSHED_NO_RECEIVER, result.state)
        self.assertTrue(result.pushed)
        self.assertFalse(result.launch_requested)
        self.assertFalse(result.import_confirmed)
        self.assertIn("尚未导入", result.message)
        self.assertIn("Download", result.message)
        self.assertFalse(any("am start" in " ".join(call[0]) for call in calls))
        self.assertTrue(all(call[1]["shell"] is False for call in calls))

    def test_receiver_launch_is_only_reported_as_requested(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            runner, calls = self.fake_runner(receiver=True)
            client = AdbClient(Path("C:/tools/adb.exe"), runner=runner)

            result = client.send_package(package)

        self.assertEqual(SendState.LAUNCH_REQUESTED, result.state)
        self.assertTrue(result.pushed)
        self.assertTrue(result.launch_requested)
        self.assertFalse(result.import_confirmed)
        self.assertIn("无法确认已导入", result.message)
        launch = next(call[0] for call in calls if "am start" in " ".join(call[0]))
        self.assertIn("android.intent.action.VIEW", launch)
        self.assertTrue(any(argument.startswith("content://") for argument in launch))
        self.assertIn("--grant-read-uri-permission", launch)
        self.assertIn("application/vnd.mkread.voice", launch)
        timeout_by_shape = {
            "devices": next(call[1]["timeout"] for call in calls if call[0][-2:] == ["devices", "-l"]),
            "push": next(call[1]["timeout"] for call in calls if "push" in call[0]),
            "package": next(call[1]["timeout"] for call in calls if "pm" in call[0]),
            "query": next(call[1]["timeout"] for call in calls if "query-activities" in call[0]),
            "launch": next(call[1]["timeout"] for call in calls if "am" in call[0]),
        }
        self.assertEqual(
            {"devices": 15, "push": 120, "package": 15, "query": 15, "launch": 30},
            timeout_by_shape,
        )

    def test_missing_apk_after_push_is_a_partial_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            runner, _ = self.fake_runner(receiver=False, installed=False)

            result = AdbClient(Path("adb.exe"), runner=runner).send_package(package)

        self.assertEqual(SendState.PUSHED_APP_NOT_INSTALLED, result.state)
        self.assertTrue(result.pushed)
        self.assertIn("com.mkread.app", result.message)

    def test_multiple_devices_fail_before_push(self) -> None:
        calls: list[list[str]] = []

        def runner(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
            calls.append(command)
            return subprocess.CompletedProcess(
                command,
                0,
                "List of devices attached\na device\nb device\n",
                "",
            )

        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            with self.assertRaisesRegex(AdbError, "multiple"):
                AdbClient(Path("adb.exe"), runner=runner).send_package(package)

        self.assertEqual(1, len(calls))

    def test_adb_timeout_is_sanitized(self) -> None:
        def runner(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
            raise subprocess.TimeoutExpired(command, kwargs["timeout"], output="private-output")

        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            with self.assertRaisesRegex(AdbError, "timed out") as raised:
                AdbClient(Path("adb.exe"), runner=runner).send_package(package)

        self.assertNotIn("private-output", str(raised.exception))

    def test_remote_filename_is_content_addressed_to_avoid_destructive_collision(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first_directory = root / "first"
            second_directory = root / "second"
            first_directory.mkdir()
            second_directory.mkdir()
            first = self.make_package(first_directory)
            second = self.make_package(second_directory)
            first.write_bytes(b"first package")
            second.write_bytes(b"second package")
            first_runner, _ = self.fake_runner(receiver=False, installed=False)
            second_runner, _ = self.fake_runner(receiver=False, installed=False)

            first_result = AdbClient(Path("adb.exe"), runner=first_runner).send_package(first)
            second_result = AdbClient(Path("adb.exe"), runner=second_runner).send_package(second)

        first_hash = hashlib.sha256(b"first package").hexdigest()
        second_hash = hashlib.sha256(b"second package").hexdigest()
        self.assertEqual(
            f"/sdcard/Download/MKread-{first_hash}.mkvoice",
            first_result.remote_path,
        )
        self.assertEqual(
            f"/sdcard/Download/MKread-{second_hash}.mkvoice",
            second_result.remote_path,
        )
        self.assertNotEqual(first_result.remote_path, second_result.remote_path)


if __name__ == "__main__":
    unittest.main()
