from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence, TextIO

from . import __version__
from .adb import AdbClient, AdbError, SendState, discover_adb
from .model import EMOTIONS, LANGUAGES, StyleSource, ValidationError, VoiceMetadata
from .package import PackageBuildError, build_package

EXIT_OK = 0
EXIT_ERROR = 2
EXIT_PARTIAL_DELIVERY = 3


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="mkvoice-studio",
        description="离线制作 MKread schema-version-1 .mkvoice 音色包。",
    )
    parser.add_argument("--version", action="version", version=__version__)
    commands = parser.add_subparsers(dest="command", required=True)

    build = commands.add_parser("build", help="从授权录音和逐字稿生成 .mkvoice")
    build.add_argument("--id", required=True, dest="voice_id", help="反向域名格式的音色 ID")
    build.add_argument("--name", required=True, dest="display_name", help="音色显示名称")
    build.add_argument(
        "--language",
        required=True,
        action="append",
        choices=LANGUAGES,
        dest="languages",
        help="可重复：zh-CN 或 en",
    )
    build.add_argument("--creator", required=True, help="音色制作者")
    build.add_argument(
        "--confirm-authorized-recording",
        action="store_true",
        help="确认录音来自本人或已获得明确授权",
    )
    build.add_argument(
        "--consent-statement",
        required=True,
        help="10-500 字的明确授权说明",
    )
    build.add_argument(
        "--style",
        action="append",
        nargs=3,
        required=True,
        metavar=("EMOTION", "AUDIO", "TRANSCRIPT"),
        help="可重复；情绪为 neutral/joy/sadness/anger/tension",
    )
    build.add_argument("--output", required=True, type=Path, help="输出 .mkvoice 文件")
    build.add_argument(
        "--ffmpeg",
        type=Path,
        help="本地 ffmpeg.exe；输入不是 24 kHz 单声道 PCM16 WAV 时需要",
    )
    build.add_argument("--send", action="store_true", help="生成后通过 ADB 请求 MKread 打开")
    _add_adb_arguments(build)

    send = commands.add_parser("send", help="通过 ADB 推送已有 .mkvoice 并请求 MKread 打开")
    send.add_argument("package", type=Path, help="已有 .mkvoice 文件")
    _add_adb_arguments(send)

    gui = commands.add_parser("gui", help="打开 Windows 图形界面")
    _add_adb_arguments(gui)
    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    stdout: TextIO = sys.stdout,
    stderr: TextIO = sys.stderr,
) -> int:
    args = create_parser().parse_args(argv)
    try:
        if args.command == "build":
            return _run_build(args, stdout=stdout, stderr=stderr)
        if args.command == "send":
            return _run_send(args, stdout=stdout, stderr=stderr)
        if args.command == "gui":
            from .gui import run_gui

            run_gui(
                adb_path=args.adb,
                serial=args.serial,
                replace_existing=args.replace_existing,
            )
            return EXIT_OK
    except KeyboardInterrupt:
        print("操作已取消。", file=stderr)
        return 130
    except (AdbError, PackageBuildError, ValidationError, ValueError) as error:
        print(f"错误: {error}", file=stderr)
        return EXIT_ERROR
    return EXIT_ERROR


def _add_adb_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--adb", type=Path, help="本地 adb.exe；留空时自动查找")
    parser.add_argument("--serial", help="ADB 设备序列号；连接多台设备时必须指定")
    parser.add_argument(
        "--replace-existing",
        action="store_true",
        help="显式允许替换 Android 中相同 ID 的已安装音色",
    )


def _run_build(args: argparse.Namespace, *, stdout: TextIO, stderr: TextIO) -> int:
    styles = tuple(
        StyleSource(emotion, Path(audio), Path(transcript))
        for emotion, audio, transcript in args.style
    )
    metadata = VoiceMetadata(
        voice_id=args.voice_id,
        display_name=args.display_name,
        languages=tuple(args.languages),
        creator=args.creator,
        consent_declared=args.confirm_authorized_recording,
        consent_statement=args.consent_statement,
    )
    result = build_package(
        metadata,
        styles,
        args.output,
        ffmpeg_path=args.ffmpeg,
    )
    print(f"已生成: {result.output_path}", file=stdout)
    print(f"SHA-256: {result.package_sha256}", file=stdout)
    print(f"情绪: {', '.join(result.emotions)}", file=stdout)

    if not args.send:
        return EXIT_OK
    try:
        return _send_package(
            result.output_path,
            adb_path=args.adb,
            serial=args.serial,
            replace_existing=args.replace_existing,
            stdout=stdout,
        )
    except AdbError as error:
        print(f"包已生成，但发送失败: {error}", file=stderr)
        return EXIT_PARTIAL_DELIVERY


def _run_send(args: argparse.Namespace, *, stdout: TextIO, stderr: TextIO) -> int:
    del stderr
    return _send_package(
        args.package,
        adb_path=args.adb,
        serial=args.serial,
        replace_existing=args.replace_existing,
        stdout=stdout,
    )


def _send_package(
    package_path: Path,
    *,
    adb_path: Path | None,
    serial: str | None,
    replace_existing: bool,
    stdout: TextIO,
) -> int:
    executable = discover_adb(adb_path)
    if executable is None:
        raise AdbError("未找到 adb.exe；请安装 Android SDK Platform-Tools 或使用 --adb 指定")

    result = AdbClient(executable, serial=serial).send_package(
        package_path,
        replace_existing=replace_existing,
    )
    print(result.message, file=stdout)
    print(f"Android 路径: {result.remote_path}", file=stdout)
    if result.state in (SendState.IMPORTED, SendState.LAUNCH_REQUESTED):
        return EXIT_OK
    return EXIT_PARTIAL_DELIVERY
