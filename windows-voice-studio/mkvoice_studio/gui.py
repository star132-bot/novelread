from __future__ import annotations

import queue
import threading
import tkinter as tk
from dataclasses import dataclass
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import Any

from .adb import AdbClient, AdbError, SendResult, SendState, discover_adb
from .form import StudioBuildRequest, StudioFormValues, StyleFields, build_request
from .model import EMOTIONS
from .package import BuildResult, build_package

EMOTION_LABELS = {
    "neutral": "中性",
    "joy": "喜悦",
    "sadness": "悲伤",
    "anger": "愤怒",
    "tension": "紧张",
}
MIN_WINDOW_WIDTH = 640
MIN_WINDOW_HEIGHT = 480
MAX_WINDOW_WIDTH = 1_040
MAX_WINDOW_HEIGHT = 760


def initial_window_size(screen_width: int, screen_height: int) -> tuple[int, int]:
    width = max(MIN_WINDOW_WIDTH, min(MAX_WINDOW_WIDTH, screen_width - 80))
    height = max(MIN_WINDOW_HEIGHT, min(MAX_WINDOW_HEIGHT, screen_height - 100))
    return width, height


@dataclass
class StyleWidgets:
    enabled: tk.BooleanVar
    audio: tk.StringVar
    transcript: tk.StringVar
    controls: tuple[ttk.Widget, ...]


@dataclass(frozen=True)
class WorkerSuccess:
    build: BuildResult
    send: SendResult | None


@dataclass(frozen=True)
class WorkerFailure:
    error: Exception
    build: BuildResult | None = None


class StudioWindow:
    def __init__(
        self,
        root: tk.Tk,
        *,
        adb_path: Path | None = None,
        serial: str | None = None,
        replace_existing: bool = False,
    ) -> None:
        self.root = root
        self.root.title("MKread 音色制作工具")
        self.root.minsize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
        width, height = initial_window_size(
            self.root.winfo_screenwidth(),
            self.root.winfo_screenheight(),
        )
        self.root.geometry(f"{width}x{height}")

        self.voice_id = tk.StringVar(value="com.local.voice.myvoice")
        self.display_name = tk.StringVar()
        self.creator = tk.StringVar()
        self.language_zh = tk.BooleanVar(value=True)
        self.language_en = tk.BooleanVar(value=False)
        self.consent_confirmed = tk.BooleanVar(value=False)
        self.consent_statement = tk.StringVar()
        self.output_path = tk.StringVar()
        self.ffmpeg_path = tk.StringVar()
        self.adb_path = tk.StringVar(value=str(adb_path) if adb_path else "")
        self.serial = tk.StringVar(value=serial or "")
        self.replace_existing = tk.BooleanVar(value=replace_existing)
        self.status = tk.StringVar(value="就绪")
        self.styles: dict[str, StyleWidgets] = {}
        self._results: queue.Queue[WorkerSuccess | WorkerFailure] = queue.Queue()
        self._action_buttons: list[ttk.Button] = []

        self._build_layout()

    def _build_layout(self) -> None:
        viewport = ttk.Frame(self.root)
        viewport.grid(row=0, column=0, sticky="nsew")
        self.root.rowconfigure(0, weight=1)
        self.root.columnconfigure(0, weight=1)
        viewport.rowconfigure(0, weight=1)
        viewport.columnconfigure(0, weight=1)

        frame_background = ttk.Style().lookup("TFrame", "background")
        self._canvas = tk.Canvas(
            viewport,
            borderwidth=0,
            highlightthickness=0,
            background=frame_background,
        )
        scrollbar = ttk.Scrollbar(viewport, orient="vertical", command=self._canvas.yview)
        self._canvas.configure(yscrollcommand=scrollbar.set)
        self._canvas.grid(row=0, column=0, sticky="nsew")
        scrollbar.grid(row=0, column=1, sticky="ns")

        outer = ttk.Frame(self._canvas, padding=16)
        self._canvas_window = self._canvas.create_window((0, 0), window=outer, anchor="nw")
        outer.bind("<Configure>", self._update_scroll_region)
        self._canvas.bind("<Configure>", self._resize_scroll_content)
        self.root.bind_all("<MouseWheel>", self._scroll_mouse_wheel, add="+")
        outer.columnconfigure(0, weight=1)

        ttk.Label(
            outer,
            text="MKread 音色制作工具",
            font=("Microsoft YaHei UI", 16, "bold"),
        ).grid(row=0, column=0, sticky="w")
        ttk.Label(
            outer,
            text="仅处理本人录音或已获明确授权的录音；录音、逐字稿和音色包只在本机处理。",
            foreground="#9b2c2c",
        ).grid(row=1, column=0, sticky="ew", pady=(4, 12))

        metadata = ttk.LabelFrame(outer, text="音色信息", padding=10)
        metadata.grid(row=2, column=0, sticky="ew")
        metadata.columnconfigure(1, weight=1)
        metadata.columnconfigure(3, weight=1)
        self._entry_row(metadata, 0, "音色 ID", self.voice_id, 0)
        self._entry_row(metadata, 0, "显示名称", self.display_name, 2)
        self._entry_row(metadata, 1, "制作者", self.creator, 0)
        ttk.Label(metadata, text="语言").grid(row=1, column=2, sticky="w", padx=(12, 6), pady=4)
        language = ttk.Frame(metadata)
        language.grid(row=1, column=3, sticky="w")
        ttk.Checkbutton(language, text="中文", variable=self.language_zh).pack(side="left")
        ttk.Checkbutton(language, text="English", variable=self.language_en).pack(side="left", padx=8)
        ttk.Label(metadata, text="授权说明").grid(row=2, column=0, sticky="w", pady=4)
        ttk.Entry(metadata, textvariable=self.consent_statement).grid(
            row=2, column=1, columnspan=3, sticky="ew", pady=4
        )
        ttk.Checkbutton(
            metadata,
            text="我确认录音来自本人，或已取得录音者明确授权",
            variable=self.consent_confirmed,
        ).grid(row=3, column=1, columnspan=3, sticky="w", pady=(4, 0))

        prompts = ttk.LabelFrame(outer, text="情绪参考录音与逐字稿", padding=10)
        prompts.grid(row=3, column=0, sticky="ew", pady=(12, 0))
        prompts.columnconfigure(2, weight=1)
        prompts.columnconfigure(4, weight=1)
        headings = ((0, "启用"), (1, "情绪"), (2, "WAV/音频"), (4, "UTF-8 逐字稿"))
        for column, text in headings:
            ttk.Label(prompts, text=text).grid(row=0, column=column, sticky="w", padx=4)
        for index, emotion in enumerate(EMOTIONS, start=1):
            self._style_row(prompts, index, emotion)

        files = ttk.LabelFrame(outer, text="输出与本地工具", padding=10)
        files.grid(row=4, column=0, sticky="ew", pady=(12, 0))
        files.columnconfigure(1, weight=1)
        self._path_row(
            files,
            0,
            "输出包",
            self.output_path,
            lambda: self._choose_output(self.output_path),
        )
        self._path_row(
            files,
            1,
            "ffmpeg（可选）",
            self.ffmpeg_path,
            lambda: self._choose_executable(self.ffmpeg_path, "选择 ffmpeg.exe"),
        )
        self._path_row(
            files,
            2,
            "adb（自动查找）",
            self.adb_path,
            lambda: self._choose_executable(self.adb_path, "选择 adb.exe"),
        )
        ttk.Label(files, text="设备序列号").grid(row=3, column=0, sticky="w", pady=4)
        ttk.Entry(files, textvariable=self.serial).grid(row=3, column=1, sticky="ew", pady=4)
        ttk.Checkbutton(
            files,
            text="允许替换 Android 中相同 ID 的已有音色",
            variable=self.replace_existing,
        ).grid(row=4, column=1, sticky="w", pady=(4, 0))

        actions = ttk.Frame(outer)
        actions.grid(row=5, column=0, sticky="ew", pady=(14, 0))
        actions.columnconfigure(0, weight=1)
        ttk.Label(actions, textvariable=self.status).grid(row=0, column=0, sticky="w")
        build_button = ttk.Button(actions, text="生成 .mkvoice", command=lambda: self._start(False))
        build_button.grid(row=0, column=1, padx=(8, 0))
        send_button = ttk.Button(actions, text="生成并发送到 MKread", command=lambda: self._start(True))
        send_button.grid(row=0, column=2, padx=(8, 0))
        self._action_buttons.extend((build_button, send_button))

    def _update_scroll_region(self, _event: tk.Event[Any]) -> None:
        self._canvas.configure(scrollregion=self._canvas.bbox("all"))

    def _resize_scroll_content(self, event: tk.Event[Any]) -> None:
        self._canvas.itemconfigure(self._canvas_window, width=event.width)

    def _scroll_mouse_wheel(self, event: tk.Event[Any]) -> None:
        if self._canvas.yview() == (0.0, 1.0) or event.delta == 0:
            return
        units = -3 if event.delta > 0 else 3
        self._canvas.yview_scroll(units, "units")

    @staticmethod
    def _entry_row(
        parent: ttk.Widget,
        row: int,
        label: str,
        variable: tk.StringVar,
        column: int,
    ) -> None:
        ttk.Label(parent, text=label).grid(row=row, column=column, sticky="w", pady=4)
        ttk.Entry(parent, textvariable=variable).grid(
            row=row, column=column + 1, sticky="ew", padx=(6, 0), pady=4
        )

    def _style_row(self, parent: ttk.Widget, row: int, emotion: str) -> None:
        enabled = tk.BooleanVar(value=emotion == "neutral")
        audio = tk.StringVar()
        transcript = tk.StringVar()
        enabled_button = ttk.Checkbutton(parent, variable=enabled)
        enabled_button.grid(row=row, column=0, padx=4, pady=3)
        if emotion == "neutral":
            enabled_button.state(["disabled"])
        ttk.Label(parent, text=EMOTION_LABELS[emotion]).grid(
            row=row, column=1, sticky="w", padx=4, pady=3
        )
        audio_entry = ttk.Entry(parent, textvariable=audio)
        audio_entry.grid(row=row, column=2, sticky="ew", padx=4, pady=3)
        audio_button = ttk.Button(
            parent,
            text="选择",
            command=lambda value=audio: self._choose_audio(value),
        )
        audio_button.grid(row=row, column=3, padx=4, pady=3)
        transcript_entry = ttk.Entry(parent, textvariable=transcript)
        transcript_entry.grid(row=row, column=4, sticky="ew", padx=4, pady=3)
        transcript_button = ttk.Button(
            parent,
            text="选择",
            command=lambda value=transcript: self._choose_transcript(value),
        )
        transcript_button.grid(row=row, column=5, padx=4, pady=3)
        controls: tuple[ttk.Widget, ...] = (
            audio_entry,
            audio_button,
            transcript_entry,
            transcript_button,
        )
        self.styles[emotion] = StyleWidgets(enabled, audio, transcript, controls)
        if emotion != "neutral":
            enabled.trace_add("write", lambda *_args, key=emotion: self._sync_style(key))
            self._sync_style(emotion)

    @staticmethod
    def _path_row(
        parent: ttk.Widget,
        row: int,
        label: str,
        variable: tk.StringVar,
        command: Any,
    ) -> None:
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", pady=4)
        ttk.Entry(parent, textvariable=variable).grid(
            row=row, column=1, sticky="ew", padx=(6, 6), pady=4
        )
        ttk.Button(parent, text="选择", command=command).grid(row=row, column=2, pady=4)

    def _sync_style(self, emotion: str) -> None:
        widgets = self.styles[emotion]
        state = "!disabled" if widgets.enabled.get() else "disabled"
        for control in widgets.controls:
            control.state([state])

    @staticmethod
    def _choose_audio(variable: tk.StringVar) -> None:
        selected = filedialog.askopenfilename(
            title="选择参考录音",
            filetypes=(("音频文件", "*.wav *.mp3 *.m4a *.flac *.ogg"), ("所有文件", "*.*")),
        )
        if selected:
            variable.set(selected)

    @staticmethod
    def _choose_transcript(variable: tk.StringVar) -> None:
        selected = filedialog.askopenfilename(
            title="选择 UTF-8 逐字稿",
            filetypes=(("文本文件", "*.txt"), ("所有文件", "*.*")),
        )
        if selected:
            variable.set(selected)

    @staticmethod
    def _choose_output(variable: tk.StringVar) -> None:
        selected = filedialog.asksaveasfilename(
            title="保存 MKread 音色包",
            defaultextension=".mkvoice",
            filetypes=(("MKread 音色包", "*.mkvoice"),),
        )
        if selected:
            variable.set(selected)

    @staticmethod
    def _choose_executable(variable: tk.StringVar, title: str) -> None:
        selected = filedialog.askopenfilename(
            title=title,
            filetypes=(("Windows 可执行文件", "*.exe"), ("所有文件", "*.*")),
        )
        if selected:
            variable.set(selected)

    def _form_values(self) -> StudioFormValues:
        languages = tuple(
            language
            for enabled, language in (
                (self.language_zh.get(), "zh-CN"),
                (self.language_en.get(), "en"),
            )
            if enabled
        )
        style_values = {
            emotion: StyleFields(
                enabled=widgets.enabled.get(),
                audio_path=widgets.audio.get(),
                transcript_path=widgets.transcript.get(),
            )
            for emotion, widgets in self.styles.items()
        }
        return StudioFormValues(
            voice_id=self.voice_id.get(),
            display_name=self.display_name.get(),
            languages=languages,
            creator=self.creator.get(),
            consent_confirmed=self.consent_confirmed.get(),
            consent_statement=self.consent_statement.get(),
            styles=style_values,
            output_path=self.output_path.get(),
            ffmpeg_path=self.ffmpeg_path.get(),
        )

    def _start(self, send_to_android: bool) -> None:
        try:
            request = build_request(self._form_values())
        except ValueError as error:
            messagebox.showerror("无法开始", str(error), parent=self.root)
            return

        self._set_busy(True)
        self.status.set("正在生成音色包……")
        adb_text = self.adb_path.get().strip()
        selected_adb = Path(adb_text) if adb_text else None
        selected_serial = self.serial.get().strip() or None
        replace_existing = self.replace_existing.get()
        threading.Thread(
            target=self._worker,
            args=(request, send_to_android, replace_existing, selected_adb, selected_serial),
            daemon=True,
        ).start()
        self.root.after(100, self._poll_worker)

    def _worker(
        self,
        request: StudioBuildRequest,
        send_to_android: bool,
        replace_existing: bool,
        adb_path: Path | None,
        serial: str | None,
    ) -> None:
        build: BuildResult | None = None
        try:
            build = build_package(
                request.metadata,
                request.styles,
                request.output_path,
                ffmpeg_path=request.ffmpeg_path,
            )
            send: SendResult | None = None
            if send_to_android:
                executable = discover_adb(adb_path)
                if executable is None:
                    raise AdbError("未找到 adb.exe；音色包已生成，但尚未发送")
                send = AdbClient(
                    executable,
                    serial=serial,
                ).send_package(
                    build.output_path,
                    replace_existing=replace_existing,
                )
            self._results.put(WorkerSuccess(build, send))
        except Exception as error:
            self._results.put(WorkerFailure(error, build))

    def _poll_worker(self) -> None:
        try:
            result = self._results.get_nowait()
        except queue.Empty:
            self.root.after(100, self._poll_worker)
            return

        self._set_busy(False)
        if isinstance(result, WorkerFailure):
            if result.build is None:
                self.status.set("生成失败")
                messagebox.showerror("生成失败", str(result.error), parent=self.root)
            else:
                self.status.set("已生成，发送未完成")
                messagebox.showwarning(
                    "发送未完成",
                    f"音色包已生成：\n{result.build.output_path}\n\n发送失败：{result.error}",
                    parent=self.root,
                )
            return

        sha = result.build.package_sha256
        if result.send is None:
            self.status.set("音色包已生成")
            messagebox.showinfo(
                "生成完成",
                f"音色包：\n{result.build.output_path}\n\nSHA-256：{sha}",
                parent=self.root,
            )
            return

        if result.send.state is SendState.IMPORTED:
            self.status.set("已导入 MKread")
            messagebox.showinfo(
                "导入完成",
                f"{result.send.message}\n\nAndroid 路径：{result.send.remote_path}",
                parent=self.root,
            )
        elif result.send.state is SendState.LAUNCH_REQUESTED:
            self.status.set("已请求 MKread 打开；请在手机中核对")
            messagebox.showinfo(
                "已请求打开",
                f"{result.send.message}\n\nAndroid 路径：{result.send.remote_path}",
                parent=self.root,
            )
        else:
            self.status.set("已推送，但尚未完成导入")
            messagebox.showwarning(
                "需要在手机中继续",
                f"{result.send.message}\n\nAndroid 路径：{result.send.remote_path}",
                parent=self.root,
            )

    def _set_busy(self, busy: bool) -> None:
        state = "disabled" if busy else "!disabled"
        for button in self._action_buttons:
            button.state([state])


def run_gui(
    *,
    adb_path: Path | None = None,
    serial: str | None = None,
    replace_existing: bool = False,
) -> None:
    try:
        root = tk.Tk()
    except tk.TclError as error:
        raise ValueError("无法启动图形界面；请确认当前 Windows 会话可显示桌面窗口") from error
    StudioWindow(
        root,
        adb_path=adb_path,
        serial=serial,
        replace_existing=replace_existing,
    )
    root.mainloop()
