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
$emulatorLogPath = Join-Path $logDirectory "emulator-$timestamp.log"
$emulatorOutputPath = Join-Path $logDirectory "emulator-$timestamp.out.log"

if (-not (Test-Path -LiteralPath $adbExecutable -PathType Leaf)) {
    throw "adb was not found at '$adbExecutable'."
}
if (-not (Test-Path -LiteralPath $emulatorExecutable -PathType Leaf)) {
    throw "Android Emulator was not found at '$emulatorExecutable'."
}
if (-not (Test-Path -LiteralPath $androidAvdHome -PathType Container)) {
    throw "Android AVD directory was not found at '$androidAvdHome'."
}

New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
New-Item -ItemType File -Path $emulatorLogPath -Force | Out-Null

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

$emulatorProcess = $null
$serial = Get-OnlineEmulatorSerial
if ($null -eq $serial) {
    Write-Host "Starting Android AVD '$avdName'..."
    try {
        $emulatorProcess = Start-Process `
            -FilePath $emulatorExecutable `
            -ArgumentList @('-avd', $avdName, '-no-snapshot-save') `
            -WindowStyle Hidden `
            -RedirectStandardOutput $emulatorOutputPath `
            -RedirectStandardError $emulatorLogPath `
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
            throw "Android AVD '$avdName' exited with code $($emulatorProcess.ExitCode) before boot completed. Emulator log: $emulatorLogPath"
        }
    }

    $remainingMilliseconds = [int][Math]::Max(1, (($deadline - (Get-Date)).TotalMilliseconds))
    Start-Sleep -Milliseconds ([Math]::Min(2000, $remainingMilliseconds))
}

throw "Android AVD '$avdName' did not report sys.boot_completed=1 within $BootTimeoutSeconds seconds. Emulator log: $emulatorLogPath"
