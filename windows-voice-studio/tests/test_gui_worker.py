from __future__ import annotations

import queue
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from mkvoice_studio.adb import SendResult, SendState
from mkvoice_studio.gui import StudioWindow, WorkerSuccess
from mkvoice_studio.package import BuildResult


class GuiWorkerTest(unittest.TestCase):
    @patch("mkvoice_studio.gui.AdbClient")
    @patch("mkvoice_studio.gui.discover_adb", return_value=Path("C:/tools/adb.exe"))
    @patch("mkvoice_studio.gui.build_package")
    def test_worker_uses_adb_values_captured_before_background_thread(
        self,
        package_builder: object,
        adb_discovery: object,
        client_type: object,
    ) -> None:
        package_builder.return_value = BuildResult(
            output_path=Path("voice.mkvoice"),
            package_sha256="a" * 64,
            emotions=("neutral",),
        )
        client_type.return_value.send_package.return_value = SendResult(
            state=SendState.LAUNCH_REQUESTED,
            remote_path="/sdcard/Download/voice.mkvoice",
            message="requested",
            launch_requested=True,
        )
        window = StudioWindow.__new__(StudioWindow)
        window._results = queue.Queue()
        request = SimpleNamespace(
            metadata=object(),
            styles=(),
            output_path=Path("voice.mkvoice"),
            ffmpeg_path=None,
        )

        window._worker(
            request,
            True,
            Path("C:/selected/adb.exe"),
            "emulator-5554",
        )

        result = window._results.get_nowait()
        self.assertIsInstance(result, WorkerSuccess)
        adb_discovery.assert_called_once_with(Path("C:/selected/adb.exe"))
        client_type.assert_called_once_with(
            Path("C:/tools/adb.exe"),
            serial="emulator-5554",
        )


if __name__ == "__main__":
    unittest.main()
