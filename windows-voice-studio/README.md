# MKread Windows 音色制作工具

这是一个完全离线的 Windows 工具，用授权录音和对应逐字稿生成 MKread schema-version-1 `.mkvoice` 音色包。工具不会上传录音、逐字稿或小说，也不会下载模型。

这里的“音色复制”是 MKread 的零样本参考音色流程：Windows 工具负责整理参考录音、逐字稿、授权信息和校验值；MKread Android 端在朗读时使用共享的离线 ZipVoice 模型生成语音。Windows 工具本身不训练模型，也不会生成可在其他软件中通用的说话人模型。

## 使用条件

- Windows 10/11。
- Python 3.10 或更高版本；开发验证使用 Python 3.11。
- 录音必须来自本人，或已取得录音者对该用途的明确授权。
- 每个启用的情绪需要一份录音和与录音内容完全对应的 UTF-8 `.txt` 逐字稿。
- 已符合 `24 kHz / 单声道 / PCM 16-bit WAV` 的录音无需额外程序。
- MP3、M4A、FLAC 或其他 WAV 参数需要用户自行准备本地 `ffmpeg.exe`；工具不会联网下载它。
- “发送到 MKread”需要 Android SDK Platform-Tools 中的 `adb.exe`，并且手机已开启 USB 调试或模拟器已连接。

授权复选框和包内声明用于阻止明显误用，但工具无法验证身份、合同或权利归属，也不构成法律审查；制作者仍需对录音和生成音色的合法使用负责。

授权说明需要使用明确的肯定声明，例如“本人是录音者，并明确授权将此录音用于制作和使用 MKread 音色包”，或第三方制作者使用“I am not the speaker, but I have explicit authorization to package this recording for MKread.”。明显否定、含糊或与授权无关的文字会被拒绝。

建议每段录音使用安静环境下 5-20 秒的清晰自然语音，不要添加音乐、混响或降噪后残留的强烈金属声。`neutral`（中性）必需，`joy`、`sadness`、`anger`、`tension` 可选。只提供中性录音时，Android 端会按既定规则做有限的情绪回退，不会凭空得到完整的情绪表演。

## 图形界面

双击 [start-gui.bat](start-gui.bat)，或在本目录打开 PowerShell 后运行：

```powershell
python -m mkvoice_studio gui
```

启动器会优先使用可用的 Python 3.10+ `python`，再尝试 Windows `py -3` 启动器。可用 `start-gui.bat --check` 只检查 Python、Tkinter 和工具入口而不打开窗口。

窗口会按当前屏幕尺寸缩放；在 768p 或启用高 DPI 缩放时，可使用鼠标滚轮和右侧滚动条访问底部操作区。

填写音色 ID、显示名称、制作者、语言和授权说明，选择参考录音及逐字稿，再选择输出路径。音色 ID 使用反向域名格式，例如 `com.local.voice.yunlan`。

“生成并发送到 MKread”会依次：

1. 在本机生成并校验 `.mkvoice`。
2. 通过 ADB 推送到 Android 的 `Download` 目录。
3. 如果已安装的 MKread 声明了 `.mkvoice` 接收器，请求系统用 MKread 打开它。

推送文件使用 `MKread-<SHA-256>.mkvoice` 内容寻址名称；不同内容不会覆盖 `Download` 中原有的同名音色包，相同内容重复发送只会写入相同字节。

桌面工具无法越过 Android 确认应用内部事务是否最终成功，所以结果会严格区分：

- `已推送`：文件只到达 `Download`，尚未导入。
- `已请求打开`：Android 已接受启动请求，仍需在 MKread 音色库中核对。
- 工具不会显示虚假的“已确认导入”。

在 Android Phase 5 接收器尚未实现的 APK 上，工具会诚实报告“已推送，但尚未导入”，用户可在后续 MKread 音色库中从 `Download` 手动选择。

## 命令行

生成一个只有中性参考的音色包：

```powershell
python -m mkvoice_studio build `
  --id com.local.voice.yunlan `
  --name 云岚 `
  --language zh-CN `
  --creator "Local creator" `
  --confirm-authorized-recording `
  --consent-statement "本人是录音者，并明确授权将此录音用于制作和使用 MKread 音色包。" `
  --style neutral D:\voices\neutral.wav D:\voices\neutral.txt `
  --output D:\voices\yunlan.mkvoice
```

可重复 `--style` 加入其他情绪，也可重复 `--language` 同时声明 `zh-CN` 和 `en`。输入需要转码时增加 `--ffmpeg D:\tools\ffmpeg.exe`。

生成后立即通过 ADB 发送：

```powershell
python -m mkvoice_studio build <其余参数> --send --adb D:\Android\platform-tools\adb.exe
```

发送已有包；连接多台设备时用 `--serial` 指定：

```powershell
python -m mkvoice_studio send D:\voices\yunlan.mkvoice `
  --adb D:\Android\platform-tools\adb.exe `
  --serial emulator-5554
```

退出码为 `0` 表示生成成功，或 ADB 已成功请求 MKread 打开；`2` 表示生成/发送失败；`3` 表示包已生成或已推送，但一键接入未完成。任何情况下都需要在 MKread 音色库中核对最终导入结果。

## `.mkvoice` 输出

生成结果是确定性 ZIP 容器，至少包含：

```text
manifest.json
checksums.json
prompts/neutral.wav
prompts/neutral.txt
```

每个音频被验证或转换为 `24 kHz / 单声道 / PCM 16-bit WAV`，最长 60 秒。即使输入 WAV 已符合参数，工具仍会只重写已声明的 PCM 帧，移除附加块、尾随数据和潜在隐藏元数据；因此五种情绪的规范音频总量远低于 Android 的 250 MB 导入上限。逐字稿使用严格 UTF-8，最长 64 KiB 和 10,000 个 Unicode 字符。包内音频和逐字稿都写入 SHA-256 校验值，不包含脚本、模型或可执行代码。输出文件已存在时工具拒绝覆盖。

调用 ffmpeg 时仅允许读取本地 `file` 协议，播放列表或容器不能跳转到 HTTP 等外部协议。ffmpeg 转换最长等待 120 秒；ADB 查询、启动和推送也有独立超时，超时会恢复 GUI 操作并给出不包含录音内容的错误。

## 测试

本项目只使用 Python 标准库。运行完整测试：

```powershell
python -m unittest discover -s tests -v
```

进行入口和语法检查：

```powershell
python -m mkvoice_studio --help
python -m compileall -q mkvoice_studio tests
```
