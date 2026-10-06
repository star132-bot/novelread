[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$androidSdkRoot = 'D:\spless\AffectLive\.android-sdk'
$platformTools = Join-Path $androidSdkRoot 'platform-tools'
$candidatePatterns = @(
    'C:\Program Files\Microsoft\jdk-17*'
    'C:\Program Files\Eclipse Adoptium\jdk-17*'
    'D:\java\jdk17*'
)

$candidates = [System.Collections.Generic.List[string]]::new()
if (-not [string]::IsNullOrWhiteSpace($env:MKREAD_JAVA_HOME)) {
    [void]$candidates.Add($env:MKREAD_JAVA_HOME)
}

foreach ($pattern in $candidatePatterns) {
    foreach ($directory in @(Get-ChildItem -Path $pattern -Directory -ErrorAction SilentlyContinue)) {
        [void]$candidates.Add($directory.FullName)
    }
}

$resolvedJavaHome = $null
$resolvedJavaVersion = $null
$rejections = [System.Collections.Generic.List[string]]::new()
$seenCandidates = @{}

foreach ($candidate in $candidates) {
    $candidatePath = $candidate.Trim().TrimEnd('\')
    $candidateKey = $candidatePath.ToLowerInvariant()
    if ($seenCandidates.ContainsKey($candidateKey)) {
        continue
    }
    $seenCandidates[$candidateKey] = $true

    $javaExecutable = Join-Path $candidatePath 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        [void]$rejections.Add("$candidatePath (missing bin\java.exe)")
        continue
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $versionOutput = (& $javaExecutable -version 2>&1 | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        $versionExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($versionExitCode -ne 0) {
        [void]$rejections.Add("$candidatePath (java -version exited $versionExitCode)")
        continue
    }

    $versionMatch = [regex]::Match($versionOutput, 'version\s+"(?<version>[^"]+)"')
    if (-not $versionMatch.Success) {
        [void]$rejections.Add("$candidatePath (unrecognized java -version output)")
        continue
    }

    $version = $versionMatch.Groups['version'].Value
    if ($version -match '^1\.(?<major>\d+)') {
        $majorVersion = [int]$Matches['major']
    }
    elseif ($version -match '^(?<major>\d+)') {
        $majorVersion = [int]$Matches['major']
    }
    else {
        [void]$rejections.Add("$candidatePath (unrecognized Java version '$version')")
        continue
    }

    if ($majorVersion -ne 17) {
        [void]$rejections.Add("$candidatePath (Java $majorVersion is not supported)")
        continue
    }

    $resolvedJavaHome = $candidatePath
    $resolvedJavaVersion = $version
    break
}

if ($null -eq $resolvedJavaHome) {
    $checked = if ($rejections.Count -gt 0) {
        $rejections -join '; '
    }
    else {
        'no candidate directories matched'
    }
    throw "JDK 17 was not found. Set MKREAD_JAVA_HOME to a JDK 17 installation. Checked: $checked"
}

if (-not (Test-Path -LiteralPath $platformTools -PathType Container)) {
    throw "Android SDK platform-tools were not found at '$platformTools'."
}

$javaBin = Join-Path $resolvedJavaHome 'bin'
$env:JAVA_HOME = $resolvedJavaHome
$env:ANDROID_SDK_ROOT = $androidSdkRoot
$updatedPath = "$javaBin;$platformTools;$env:Path"
[Environment]::SetEnvironmentVariable('PATH', $null, [EnvironmentVariableTarget]::Process)
[Environment]::SetEnvironmentVariable('Path', $updatedPath, [EnvironmentVariableTarget]::Process)

Write-Host "JAVA_HOME=$env:JAVA_HOME (Java $resolvedJavaVersion)"
Write-Host "ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"
Write-Host "PATH prepended with $javaBin and $platformTools"
