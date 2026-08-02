[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$apkDirectory = Join-Path $repositoryRoot 'app\build\outputs\apk\debug'
$androidSdkRoot = if ([string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    'D:\spless\AffectLive\.android-sdk'
}
else {
    $env:ANDROID_SDK_ROOT
}
$apkAnalyzer = Join-Path $androidSdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'

if (-not (Test-Path -LiteralPath $apkAnalyzer -PathType Leaf)) {
    throw "apkanalyzer was not found at '$apkAnalyzer'."
}

$apk = Get-ChildItem -File -Recurse -LiteralPath $apkDirectory -Filter '*-debug.apk' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $apk) {
    throw "No debug APK was found under '$apkDirectory'. Run assembleDebug first."
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $permissionOutput = (& $apkAnalyzer manifest permissions $apk.FullName 2>&1 |
        ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    $analyzerExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

if ($analyzerExitCode -ne 0) {
    throw "apkanalyzer failed for '$($apk.FullName)' with exit code $analyzerExitCode. $permissionOutput"
}

$forbiddenPermissions = @(
    'android.permission.INTERNET'
    'android.permission.ACCESS_NETWORK_STATE'
)
foreach ($permission in $forbiddenPermissions) {
    if ($permissionOutput -match [regex]::Escape($permission)) {
        throw "$permission permission is forbidden in '$($apk.FullName)'."
    }
}

Write-Host "Offline manifest contract passed: $($apk.FullName)"
