# MKread Android Toolchain

MKread uses a pinned Android build environment so local builds and emulator checks are reproducible.

## Fixed versions

| Component | Version |
|---|---|
| JDK | 17 |
| Android compile and target SDK | 35 |
| Android Build Tools | 35.0.0 |
| Gradle | 8.8 |
| Android Gradle Plugin | 8.6.0 |
| Kotlin and Compose plugin | 2.0.21 |
| Compose BOM | 2024.12.01 |

The current workstation installation resolved by the setup script is Microsoft OpenJDK `17.0.16.8`. The machine-wide default Java 8 installation is not valid for MKread, and Gradle must not be switched to Java 8 or Java 24.

## Local paths

| Resource | Value |
|---|---|
| Android SDK | `D:\spless\AffectLive\.android-sdk` |
| Android platform | `android-35` |
| AVD directory | `D:\spless\AffectLive\.android-avd` |
| AVD name | `AffectLive_API_35` |
| Emulator API and ABI | API 35, `x86_64` |

This workstation's effective Windows PowerShell execution policy is `Restricted`. Enable repository scripts only for the current PowerShell process before running them:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
```

This does not change the machine or user execution policy.

Run the environment script once in every PowerShell process used for MKread:

```powershell
.\scripts\android-env.ps1
java -version
```

The script searches for JDK 17 in this order:

1. `$env:MKREAD_JAVA_HOME`
2. `C:\Program Files\Microsoft\jdk-17*`
3. `C:\Program Files\Eclipse Adoptium\jdk-17*`
4. `D:\java\jdk17*`

`MKREAD_JAVA_HOME` is optional. When set, it must be a JDK home containing `bin\java.exe`; only Java major version 17 is accepted.

The script changes `JAVA_HOME`, `ANDROID_SDK_ROOT`, and `Path` only for its current PowerShell process. A script launched through a separate `powershell -File ...` child process cannot modify its caller. Re-run the script in each new terminal or CI process. To verify an already resolved JDK explicitly, run:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -version
```

## Emulator

Start or reuse the pinned emulator and verify its target:

```powershell
.\scripts\start-emulator.ps1
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
```

The launcher reuses an online emulator when present. Otherwise it starts `AffectLive_API_35` without saving a snapshot, waits up to 180 seconds for Android to finish booting, and writes startup logs under the ignored `captures\` directory. A failed launch stops only the emulator processes started by that invocation, and aborts early if combined startup logs exceed 8 MiB.

## Offline model assets

Speech models, the sherpa-onnx AAR, and other large runtime assets are downloaded by development tooling outside the Android app. Downloads stay in ignored local paths such as `.local-assets\` and `app\libs\*.aar`. The app does not download models and does not require Internet permission.
