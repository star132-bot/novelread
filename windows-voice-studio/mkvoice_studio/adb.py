from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Callable, Mapping
from urllib.parse import quote

ANDROID_PACKAGE = "com.mkread.app"
DOWNLOAD_DIRECTORY = "/sdcard/Download"
VOICE_MIME_TYPES = (
    "application/vnd.mkread.voice",
    "application/octet-stream",
    "application/zip",
)
VOICE_PROVIDER_URI = f"content://{ANDROID_PACKAGE}.voice-import"
ADB_QUERY_TIMEOUT_SECONDS = 15
ADB_LAUNCH_TIMEOUT_SECONDS = 30
ADB_PUSH_TIMEOUT_SECONDS = 120


class AdbError(RuntimeError):
    """An ADB error that does not expose raw command output."""


class SendState(Enum):
    IMPORTED = "imported"
    PUSHED_REPLACE_REQUIRED = "pushed_replace_required"
    PUSHED_IMPORT_REJECTED = "pushed_import_rejected"
    PUSHED_APP_NOT_INSTALLED = "pushed_app_not_installed"
    PUSHED_NO_RECEIVER = "pushed_no_receiver"
    PUSHED_LAUNCH_FAILED = "pushed_launch_failed"
    LAUNCH_REQUESTED = "launch_requested"


class ProviderImportState(Enum):
    IMPORTED = "imported"
    REPLACE_REQUIRED = "replace_required"
    REJECTED = "rejected"
    UNAVAILABLE = "unavailable"


@dataclass(frozen=True)
class SendResult:
    state: SendState
    remote_path: str
    message: str
    pushed: bool = True
    launch_requested: bool = False
    import_confirmed: bool = False


def discover_adb(
    explicit: Path | None = None,
    *,
    env: Mapping[str, str] | None = None,
    which: Callable[[str], str | None] = shutil.which,
) -> Path | None:
    environment = os.environ if env is None else env
    if explicit is not None:
        candidate = Path(explicit).expanduser()
        if candidate.is_file():
            return candidate
        resolved = which(str(explicit))
        if resolved:
            return Path(resolved)
        raise AdbError("selected adb executable does not exist")

    path_result = which("adb")
    if path_result:
        return Path(path_result)

    candidates: list[Path] = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = environment.get(variable)
        if root:
            candidates.append(Path(root) / "platform-tools" / "adb.exe")
    local_app_data = environment.get("LOCALAPPDATA")
    if local_app_data:
        candidates.append(
            Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        )
    user_profile = environment.get("USERPROFILE")
    if user_profile:
        candidates.append(
            Path(user_profile)
            / "AppData"
            / "Local"
            / "Android"
            / "Sdk"
            / "platform-tools"
            / "adb.exe"
        )

    return next((candidate for candidate in candidates if candidate.is_file()), None)


class AdbClient:
    def __init__(
        self,
        adb_path: Path,
        *,
        serial: str | None = None,
        runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    ) -> None:
        self._adb_path = Path(adb_path)
        self._serial = serial.strip() if serial else None
        self._runner = runner

    def send_package(
        self,
        package_path: Path,
        *,
        replace_existing: bool = False,
    ) -> SendResult:
        package_path = Path(package_path)
        if not package_path.is_file() or package_path.suffix.lower() != ".mkvoice":
            raise AdbError("select an existing .mkvoice package")

        try:
            package_hash = _sha256_file(package_path)
        except OSError as error:
            raise AdbError("could not read the selected .mkvoice package") from error
        serial = self._select_device()
        remote_filename = f"MKread-{package_hash}.mkvoice"
        remote_path = f"{DOWNLOAD_DIRECTORY}/{remote_filename}"
        push = self._run(
            ["push", package_path.as_posix(), remote_path],
            serial=serial,
            timeout_seconds=ADB_PUSH_TIMEOUT_SECONDS,
        )
        if push.returncode != 0:
            raise AdbError("ADB could not push the package to Android Download")

        installed = self._run(
            ["shell", "pm", "path", ANDROID_PACKAGE],
            serial=serial,
            timeout_seconds=ADB_QUERY_TIMEOUT_SECONDS,
        )
        if installed.returncode != 0 or "package:" not in installed.stdout:
            return SendResult(
                state=SendState.PUSHED_APP_NOT_INSTALLED,
                remote_path=remote_path,
                message=(
                    "已推送到 Android Download，但未检测到 com.mkread.app；"
                    "安装 MKread 后再从 Download 导入。"
                ),
            )

        provider_result = self._import_through_provider(
            serial,
            remote_path,
            package_hash,
            replace_existing=replace_existing,
        )
        if provider_result is ProviderImportState.IMPORTED:
            return SendResult(
                state=SendState.IMPORTED,
                remote_path=remote_path,
                message="已推送并确认导入 MKread，音色已设为当前音色。",
                import_confirmed=True,
            )
        if provider_result is ProviderImportState.REPLACE_REQUIRED:
            return SendResult(
                state=SendState.PUSHED_REPLACE_REQUIRED,
                remote_path=remote_path,
                message=(
                    "已推送，但 Android 中已有相同 ID 的音色；默认未覆盖。"
                    "确认需要替换后，请勾选“允许替换”或使用 --replace-existing 重新发送。"
                ),
            )
        if provider_result is ProviderImportState.REJECTED:
            return SendResult(
                state=SendState.PUSHED_IMPORT_REJECTED,
                remote_path=remote_path,
                message="已推送，但 MKread 拒绝了无效或不兼容的音色包，未进行导入。",
            )

        content_uri = _download_content_uri(remote_filename)
        receiver_mime = self._find_receiver_mime(serial, content_uri)
        if receiver_mime is None:
            return SendResult(
                state=SendState.PUSHED_NO_RECEIVER,
                remote_path=remote_path,
                message=(
                    "已推送到 Android Download，但当前 APK 没有 .mkvoice ACTION_VIEW "
                    "接收器，因此尚未导入。"
                    "请更新 MKread，再从 Windows 工具重新发送该包。"
                ),
            )

        launched = self._run(
            [
                "shell",
                "am",
                "start",
                "-W",
                "-a",
                "android.intent.action.VIEW",
                "-c",
                "android.intent.category.DEFAULT",
                "-d",
                content_uri,
                "-t",
                receiver_mime,
                "--grant-read-uri-permission",
                "-p",
                ANDROID_PACKAGE,
            ],
            serial=serial,
            timeout_seconds=ADB_LAUNCH_TIMEOUT_SECONDS,
        )
        launch_output = f"{launched.stdout}\n{launched.stderr}"
        if launched.returncode != 0 or re.search(r"(?:Error|Exception):", launch_output):
            return SendResult(
                state=SendState.PUSHED_LAUNCH_FAILED,
                remote_path=remote_path,
                message=(
                    "已推送到 Android Download，也检测到导入接收器，但打开请求失败；"
                    "请在 MKread 中手动从 Download 选择该包。"
                ),
            )

        return SendResult(
            state=SendState.LAUNCH_REQUESTED,
            remote_path=remote_path,
            message=(
                "已推送并请求 MKread 打开该包；桌面工具无法确认已导入，"
                "请在 MKread 音色库中核对结果。"
            ),
            launch_requested=True,
        )

    def _import_through_provider(
        self,
        serial: str,
        remote_path: str,
        package_hash: str,
        *,
        replace_existing: bool,
    ) -> ProviderImportState:
        if not re.fullmatch(r"[0-9a-f]{64}", package_hash):
            return ProviderImportState.UNAVAILABLE
        expected_path = f"{DOWNLOAD_DIRECTORY}/MKread-{package_hash}.mkvoice"
        if remote_path != expected_path:
            return ProviderImportState.UNAVAILABLE
        staging_uri = f"{VOICE_PROVIDER_URI}/staging/{package_hash}"
        staged = self._run(
            [
                "shell",
                f"content write --uri {staging_uri} < {remote_path}",
            ],
            serial=serial,
            timeout_seconds=ADB_PUSH_TIMEOUT_SECONDS,
        )
        if staged.returncode != 0:
            return ProviderImportState.UNAVAILABLE
        imported = self._run(
            [
                "shell",
                "content",
                "call",
                "--uri",
                VOICE_PROVIDER_URI,
                "--method",
                "import_replace" if replace_existing else "import",
                "--arg",
                package_hash,
            ],
            serial=serial,
            timeout_seconds=ADB_PUSH_TIMEOUT_SECONDS,
        )
        if imported.returncode != 0:
            return ProviderImportState.UNAVAILABLE
        if re.search(
            r"(?:^|[,{\s])status=ok(?:[,}\]\s]|$)",
            imported.stdout,
        ) is not None:
            return ProviderImportState.IMPORTED
        if re.search(
            r"(?:^|[,{\s])errorCode=destination_exists(?:[,}\]\s]|$)",
            imported.stdout,
        ) is not None:
            return ProviderImportState.REPLACE_REQUIRED
        if re.search(
            r"(?:^|[,{\s])status=error(?:[,}\]\s]|$)",
            imported.stdout,
        ) is not None:
            return ProviderImportState.REJECTED
        return ProviderImportState.UNAVAILABLE

    def _select_device(self) -> str:
        completed = self._run(
            ["devices", "-l"],
            serial=None,
            timeout_seconds=ADB_QUERY_TIMEOUT_SECONDS,
        )
        if completed.returncode != 0:
            raise AdbError("ADB could not list connected devices")

        devices: dict[str, str] = {}
        for line in completed.stdout.splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 2:
                devices[fields[0]] = fields[1]

        if self._serial:
            state = devices.get(self._serial)
            if state != "device":
                raise AdbError("selected Android device is absent, offline, or unauthorized")
            return self._serial

        ready = [serial for serial, state in devices.items() if state == "device"]
        if not ready:
            raise AdbError("no authorized Android device is connected")
        if len(ready) > 1:
            raise AdbError("multiple Android devices are connected; specify a serial")
        return ready[0]

    def _find_receiver_mime(self, serial: str, content_uri: str) -> str | None:
        for mime_type in VOICE_MIME_TYPES:
            completed = self._run(
                [
                    "shell",
                    "cmd",
                    "package",
                    "query-activities",
                    "--brief",
                    "-a",
                    "android.intent.action.VIEW",
                    "-c",
                    "android.intent.category.DEFAULT",
                    "-d",
                    content_uri,
                    "-t",
                    mime_type,
                    ANDROID_PACKAGE,
                ],
                serial=serial,
                timeout_seconds=ADB_QUERY_TIMEOUT_SECONDS,
            )
            if completed.returncode == 0 and re.search(
                rf"(?:^|\s){re.escape(ANDROID_PACKAGE)}/", completed.stdout
            ):
                return mime_type
        return None

    def _run(
        self,
        arguments: list[str],
        *,
        serial: str | None,
        timeout_seconds: int,
    ) -> subprocess.CompletedProcess[str]:
        command = [self._adb_path.as_posix()]
        if serial:
            command.extend(["-s", serial])
        command.extend(arguments)
        try:
            return self._runner(
                command,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                shell=False,
                timeout=timeout_seconds,
            )
        except subprocess.TimeoutExpired as error:
            raise AdbError("ADB operation timed out") from error
        except OSError as error:
            raise AdbError("could not start the selected adb executable") from error


def _download_content_uri(filename: str) -> str:
    document_id = quote(f"primary:Download/{filename}", safe="")
    return f"content://com.android.externalstorage.documents/document/{document_id}"


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
