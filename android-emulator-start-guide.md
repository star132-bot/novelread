# Android Emulator 启动指南

本文档说明如何在本项目中启动 Android 模拟器，并可选地安装和启动 AffectLive。

## 术语

日常说的“安卓虚拟机”在 Android 开发里通常叫：

- **Android Emulator**：Android 官方模拟器程序。
- **AVD**：Android Virtual Device，安卓虚拟设备。它是模拟器里运行的那台“虚拟手机”。
- **adb**：Android Debug Bridge，用来连接模拟器、安装 APK、启动 App 和查看日志。

本文后面统一使用 **Android Emulator** 和 **AVD**。

## 当前项目环境

本项目已经在仓库目录内准备了本机 Android SDK 和 AVD：

```text
项目目录: D:\spless\AffectLive
Android SDK: D:\spless\AffectLive\.android-sdk
AVD 目录: D:\spless\AffectLive\.android-avd
AVD 名称: AffectLive_API_35
App Activity: com.affectlive/.MainActivity
```

`.android-sdk/`、`.android-avd/` 和 `local.properties` 都是本机环境文件，已经被 `.gitignore` 忽略，不应该提交到仓库。

## 1. 打开 PowerShell

打开 PowerShell，进入项目根目录：

```powershell
cd D:\spless\AffectLive
```

设置当前 PowerShell 会话使用项目内的 SDK 和 AVD 目录：

```powershell
$env:ANDROID_SDK_ROOT='D:\spless\AffectLive\.android-sdk'
$env:ANDROID_AVD_HOME='D:\spless\AffectLive\.android-avd'
```

这些环境变量只对当前 PowerShell 窗口有效。关闭窗口后，下次需要重新设置。

## 2. 确认 AVD 存在

执行：

```powershell
.\.android-sdk\emulator\emulator.exe -list-avds
```

正常情况下应该看到：

```text
AffectLive_API_35
```

如果没有看到这个名称，说明当前机器上的 AVD 没有准备好，需要重新创建或恢复 `.android-avd/`。

## 3. 启动 Android Emulator

执行：

```powershell
.\.android-sdk\emulator\emulator.exe -avd AffectLive_API_35 -gpu swiftshader_indirect -no-snapshot-load
```

这条命令会启动 Android Emulator，并打开名为 `AffectLive_API_35` 的 AVD。

参数说明：

- `-avd AffectLive_API_35`：指定要启动的虚拟设备。
- `-gpu swiftshader_indirect`：使用软件 GPU，兼容性更稳。
- `-no-snapshot-load`：不从旧快照恢复，避免上次异常状态影响本次启动。

启动模拟器的 PowerShell 窗口会被占用，不要关闭它。后续命令建议打开第二个 PowerShell 窗口执行。

首次启动可能需要几十秒到几分钟。看到 Android 桌面后，说明模拟器已经启动。

## 4. 确认 adb 已连接

打开第二个 PowerShell 窗口，重新进入项目目录并设置环境变量：

```powershell
cd D:\spless\AffectLive
$env:ANDROID_SDK_ROOT='D:\spless\AffectLive\.android-sdk'
$env:ANDROID_AVD_HOME='D:\spless\AffectLive\.android-avd'
```

查看设备连接状态：

```powershell
.\.android-sdk\platform-tools\adb.exe devices -l
```

正常情况下会看到类似：

```text
List of devices attached
emulator-5554 device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
```

关键是这一行里出现 `device`。如果显示 `offline` 或没有设备，等模拟器完全启动后再执行一次。

## 5. 可选：构建 Debug APK

如果只是启动 Android Emulator，到第 4 步就够了。

如果要把当前项目安装到模拟器里，先构建 Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

构建成功后 APK 位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 6. 可选：安装 App

安装或覆盖安装 Debug APK：

```powershell
.\.android-sdk\platform-tools\adb.exe install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`-r` 表示如果模拟器里已经装过 AffectLive，就覆盖安装。

## 7. 可选：启动 AffectLive

执行：

```powershell
.\.android-sdk\platform-tools\adb.exe shell am start -n com.affectlive/.MainActivity
```

正常情况下，模拟器会打开 AffectLive 首页。

## 8. 可选：查看 Live2D 舞台日志

查看 `StageRuntime` 日志：

```powershell
.\.android-sdk\platform-tools\adb.exe logcat -s StageRuntime
```

如果想清空旧日志后重新观察：

```powershell
.\.android-sdk\platform-tools\adb.exe logcat -c
.\.android-sdk\platform-tools\adb.exe logcat -s StageRuntime
```

V3 cue 正常进入 Web 舞台时，日志里应该能看到类似：

```text
[AffectLiveStage] cue
```

## 9. 可选：截图

在模拟器里截图到设备临时目录：

```powershell
.\.android-sdk\platform-tools\adb.exe shell screencap -p /sdcard/affectlive-current.png
```

拉回项目目录：

```powershell
.\.android-sdk\platform-tools\adb.exe pull /sdcard/affectlive-current.png captures\affectlive-current.png
```

`captures/` 已被 `.gitignore` 忽略，只用于本机验收截图。

## 常见问题

### `adb devices -l` 没有设备

先确认模拟器窗口已经打开，并且 Android 桌面已经显示。然后重试：

```powershell
.\.android-sdk\platform-tools\adb.exe devices -l
```

如果仍然没有设备，可以重启 adb server：

```powershell
.\.android-sdk\platform-tools\adb.exe kill-server
.\.android-sdk\platform-tools\adb.exe start-server
.\.android-sdk\platform-tools\adb.exe devices -l
```

### 模拟器启动很慢

第一次启动较慢是正常的。等到 Android 桌面出现后，再执行 adb 或安装 APK。

如果长时间黑屏或卡住，可以关闭模拟器窗口，重新执行启动命令。

### 安装 APK 失败

先确认 APK 存在：

```powershell
Get-ChildItem .\app\build\outputs\apk\debug\
```

如果没有 `app-debug.apk`，先重新构建：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

然后重新安装：

```powershell
.\.android-sdk\platform-tools\adb.exe install -r .\app\build\outputs\apk\debug\app-debug.apk
```

### App 没有自动打开

手动启动 Activity：

```powershell
.\.android-sdk\platform-tools\adb.exe shell am start -n com.affectlive/.MainActivity
```

### 关闭 Android Emulator

可以直接关闭模拟器窗口，也可以执行：

```powershell
.\.android-sdk\platform-tools\adb.exe emu kill
```

## 快速命令

第一个 PowerShell：启动 Android Emulator。

```powershell
cd D:\spless\AffectLive
$env:ANDROID_SDK_ROOT='D:\spless\AffectLive\.android-sdk'
$env:ANDROID_AVD_HOME='D:\spless\AffectLive\.android-avd'
.\.android-sdk\emulator\emulator.exe -avd AffectLive_API_35 -gpu swiftshader_indirect -no-snapshot-load
```

第二个 PowerShell：确认连接，并可选地构建、安装、启动 App。

```powershell
cd D:\spless\AffectLive
$env:ANDROID_SDK_ROOT='D:\spless\AffectLive\.android-sdk'
$env:ANDROID_AVD_HOME='D:\spless\AffectLive\.android-avd'
.\.android-sdk\platform-tools\adb.exe devices -l
.\gradlew.bat :app:assembleDebug --no-daemon
.\.android-sdk\platform-tools\adb.exe install -r .\app\build\outputs\apk\debug\app-debug.apk
.\.android-sdk\platform-tools\adb.exe shell am start -n com.affectlive/.MainActivity
```
