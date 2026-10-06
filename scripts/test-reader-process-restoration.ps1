[CmdletBinding()]
param(
    [string]$Serial = '',
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$androidSdkRoot = 'D:\spless\AffectLive\.android-sdk'
$adbExecutable = Join-Path $androidSdkRoot 'platform-tools\adb.exe'
$appApk = Join-Path $repositoryRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $repositoryRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
$testClass = 'com.mkread.app.feature.reader.ReaderProcessRestorationTest'
$stageArgument = 'mkread.processRestorationStage'
$runner = 'com.mkread.app.test/androidx.test.runner.AndroidJUnitRunner'
$targetPackage = 'com.mkread.app'
$testPackage = 'com.mkread.app.test'

if (-not (Test-Path -LiteralPath $adbExecutable -PathType Leaf)) {
    throw "adb was not found at '$adbExecutable'."
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $online = @(& $adbExecutable devices 2>&1 | ForEach-Object {
        $match = [regex]::Match($_.ToString(), '^(?<serial>emulator-\d+)\s+device(?:\s|$)')
        if ($match.Success) { $match.Groups['serial'].Value }
    })
    if ($online.Count -ne 1) {
        throw "Expected exactly one online Android emulator, found $($online.Count)."
    }
    $Serial = $online[0]
}

function Invoke-CheckedAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & $adbExecutable -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb -s $Serial $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Set-FontScale {
    param([Parameter(Mandatory = $true)][string]$Value)

    Invoke-CheckedAdb -Arguments @('shell', 'settings', 'put', 'system', 'font_scale', $Value) | Out-Null
    Start-Sleep -Milliseconds 750
}

function Stop-TestProcesses {
    Invoke-CheckedAdb -Arguments @('shell', 'am', 'force-stop', $targetPackage) | Out-Null
    Invoke-CheckedAdb -Arguments @('shell', 'am', 'force-stop', $testPackage) | Out-Null
}

function Invoke-RestorationCleanup {
    param([Parameter(Mandatory = $true)][string]$OriginalFontScale)

    $failures = [System.Collections.Generic.List[string]]::new()
    try {
        Set-FontScale -Value $OriginalFontScale
    }
    catch {
        $failures.Add("font scale restore failed: $($_.Exception.Message)")
    }
    try {
        Stop-TestProcesses
    }
    catch {
        $failures.Add("test process stop failed: $($_.Exception.Message)")
    }
    if ($failures.Count -gt 0) {
        throw "Reader process-restoration cleanup failed: $($failures -join '; ')"
    }
}

function Assert-RestorationStagePassed {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][int]$ExitCode,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Output
    )

    $joined = $Output -join "`n"
    $wasSkipped = $joined -match 'INSTRUMENTATION_STATUS_CODE:\s*-(?:3|4)\b' -or
        $joined -match '(?i)AssumptionViolated|\bSKIPPED\b|\bIGNORED\b'
    $completedSuccessfully = $joined -match 'INSTRUMENTATION_STATUS_CODE:\s*0\b'
    if (
        $ExitCode -ne 0 -or
        $joined -notmatch 'OK \(1 test\)' -or
        $joined -match 'FAILURES!!!' -or
        $wasSkipped -or
        -not $completedSuccessfully
    ) {
        throw "Instrumentation stage '$Method' did not execute and pass exactly one test " +
            "(adb exit code $ExitCode, skipped=$wasSkipped, successStatus=$completedSuccessfully)."
    }
}

function Invoke-RestorationStage {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][ValidateSet('seed', 'verify')][string]$Stage
    )

    $selector = "$testClass#$Method"
    Write-Host "Running $selector ($stageArgument=$Stage) on $Serial..."
    $output = & $adbExecutable -s $Serial shell am instrument -w -r `
        -e class $selector `
        -e $stageArgument $Stage `
        $runner 2>&1
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    Assert-RestorationStagePassed -Method $Method -ExitCode $exitCode -Output $output
}

Push-Location $repositoryRoot
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Android test APK build failed.' }
    }
    if (-not (Test-Path -LiteralPath $appApk -PathType Leaf)) {
        throw "App APK does not exist at '$appApk'."
    }
    if (-not (Test-Path -LiteralPath $testApk -PathType Leaf)) {
        throw "Test APK does not exist at '$testApk'."
    }
    if (-not $SkipInstall) {
        Invoke-CheckedAdb -Arguments @('install', '-r', '-t', $appApk) | Out-Host
        Invoke-CheckedAdb -Arguments @('install', '-r', '-t', $testApk) | Out-Host
    }

    $originalFontScale = (
        Invoke-CheckedAdb -Arguments @('shell', 'settings', 'get', 'system', 'font_scale') |
            Select-Object -First 1
    ).ToString().Trim()
    if ([string]::IsNullOrWhiteSpace($originalFontScale) -or $originalFontScale -eq 'null') {
        $originalFontScale = '1.0'
    }

    $gateFailure = $null
    $cleanupFailure = $null
    try {
        Stop-TestProcesses
        Set-FontScale -Value '1.0'
        Invoke-RestorationStage `
            -Method 'seedSemanticPositionForHostRestart' `
            -Stage 'seed'

        Stop-TestProcesses
        Set-FontScale -Value '1.35'
        Stop-TestProcesses
        Invoke-RestorationStage `
            -Method 'verifySemanticPositionAfterHostRestart' `
            -Stage 'verify'

        Write-Host 'Reader process-restoration gate passed.'
        Invoke-CheckedAdb -Arguments @('logcat', '-d', '-s', 'MKreadReaderGate:I', '*:S') |
            ForEach-Object { Write-Host $_ }
    }
    catch {
        $gateFailure = $_
    }
    finally {
        try {
            Invoke-RestorationCleanup -OriginalFontScale $originalFontScale
        }
        catch {
            $cleanupFailure = $_
        }
    }
    if ($null -ne $gateFailure) {
        if ($null -ne $cleanupFailure) {
            Write-Warning $cleanupFailure.Exception.Message
        }
        throw $gateFailure
    }
    if ($null -ne $cleanupFailure) {
        throw $cleanupFailure
    }
}
finally {
    Pop-Location
}
