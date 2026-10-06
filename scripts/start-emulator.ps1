[CmdletBinding()]
param(
    [ValidateRange(1, 180)]
    [int]$BootTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$androidSdkRoot = 'D:\spless\AffectLive\.android-sdk'
$androidAvdHome = 'D:\spless\AffectLive\.android-avd'
$avdName = 'AffectLive_API_35'
$adbExecutable = Join-Path $androidSdkRoot 'platform-tools\adb.exe'
$emulatorExecutable = Join-Path $androidSdkRoot 'emulator\emulator.exe'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repositoryRoot 'captures'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$emulatorLogPath = Join-Path $logDirectory "emulator-$timestamp.out.log"
$emulatorErrorLogPath = Join-Path $logDirectory "emulator-$timestamp.err.log"
$maxCombinedLogBytes = 8MB

if (-not (Test-Path -LiteralPath $adbExecutable -PathType Leaf)) {
    throw "adb was not found at '$adbExecutable'."
}
if (-not (Test-Path -LiteralPath $emulatorExecutable -PathType Leaf)) {
    throw "Android Emulator was not found at '$emulatorExecutable'."
}
if (-not (Test-Path -LiteralPath $androidAvdHome -PathType Container)) {
    throw "Android AVD directory was not found at '$androidAvdHome'."
}

$env:ANDROID_SDK_ROOT = $androidSdkRoot
$env:ANDROID_AVD_HOME = $androidAvdHome
$updatedPath = "$(Split-Path -Parent $adbExecutable);$env:Path"
[Environment]::SetEnvironmentVariable('PATH', $null, [EnvironmentVariableTarget]::Process)
[Environment]::SetEnvironmentVariable('Path', $updatedPath, [EnvironmentVariableTarget]::Process)

function Get-OnlineEmulatorSerial {
    $deviceOutput = & $adbExecutable devices 2>&1
    if ($LASTEXITCODE -ne 0) {
        return $null
    }

    foreach ($line in $deviceOutput) {
        $match = [regex]::Match($line.ToString(), '^(?<serial>emulator-\d+)\s+device(?:\s|$)')
        if ($match.Success) {
            return $match.Groups['serial'].Value
        }
    }

    return $null
}

function Get-BootCompletion {
    param([Parameter(Mandatory = $true)][string]$Serial)

    $bootOutput = & $adbExecutable -s $Serial shell getprop sys.boot_completed 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $bootOutput) {
        return ''
    }

    return (($bootOutput | Select-Object -First 1).ToString()).Trim()
}

function Stop-StartedEmulator {
    if ($null -eq $emulatorStartedAt) {
        return
    }

    $startedProcesses = Get-Process -Name 'emulator', 'qemu-system-x86_64' -ErrorAction SilentlyContinue |
        Where-Object {
            $baselineEmulatorProcessIds -notcontains $_.Id -and
            $_.StartTime -ge $emulatorStartedAt
        }
    foreach ($process in $startedProcesses) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Get-CombinedLogSize {
    $total = 0L
    foreach ($path in @($emulatorLogPath, $emulatorErrorLogPath)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $total += (Get-Item -LiteralPath $path).Length
        }
    }
    return $total
}

$emulatorProcess = $null
$emulatorStartedAt = $null
$baselineEmulatorProcessIds = @(Get-Process -Name 'emulator', 'qemu-system-x86_64' -ErrorAction SilentlyContinue |
    ForEach-Object { $_.Id })
$serial = Get-OnlineEmulatorSerial
if ($null -eq $serial) {
    Write-Host "Starting Android AVD '$avdName'..."
    try {
        New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
        New-Item -ItemType File -Path $emulatorLogPath -Force | Out-Null
        New-Item -ItemType File -Path $emulatorErrorLogPath -Force | Out-Null
        $emulatorStartedAt = Get-Date
        $emulatorProcess = Start-Process `
            -FilePath $emulatorExecutable `
            -ArgumentList @('-avd', $avdName, '-no-snapshot-save') `
            -WindowStyle Hidden `
            -RedirectStandardOutput $emulatorLogPath `
            -RedirectStandardError $emulatorErrorLogPath `
            -PassThru
    }
    catch {
        throw "Failed to start Android AVD '$avdName'. Emulator log: $emulatorLogPath. $($_.Exception.Message)"
    }
}
else {
    Write-Host "Using online Android emulator '$serial'."
}

$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $serial = Get-OnlineEmulatorSerial
    if ($null -ne $serial -and (Get-BootCompletion -Serial $serial) -eq '1') {
        Write-Host "Android emulator '$serial' is online and booted."
        & $adbExecutable devices -l
        exit $LASTEXITCODE
    }

    if ($null -ne $emulatorProcess) {
        $emulatorProcess.Refresh()
        if ($emulatorProcess.HasExited) {
            throw "Android AVD '$avdName' exited with code $($emulatorProcess.ExitCode) before boot completed. Emulator logs: $emulatorLogPath; $emulatorErrorLogPath"
        }

        if ((Get-CombinedLogSize) -gt $maxCombinedLogBytes) {
            Stop-StartedEmulator
            throw "Android AVD '$avdName' exceeded the 8 MiB startup log limit. Emulator logs: $emulatorLogPath; $emulatorErrorLogPath"
        }
    }

    $remainingMilliseconds = [int][Math]::Max(1, (($deadline - (Get-Date)).TotalMilliseconds))
    Start-Sleep -Milliseconds ([Math]::Min(2000, $remainingMilliseconds))
}

Stop-StartedEmulator
throw "Android AVD '$avdName' did not report sys.boot_completed=1 within $BootTimeoutSeconds seconds. Emulator logs: $emulatorLogPath; $emulatorErrorLogPath"
