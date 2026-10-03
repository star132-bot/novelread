[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$localAssetsRoot = Join-Path $repositoryRoot '.local-assets'
$downloadsRoot = Join-Path $localAssetsRoot 'downloads'
$extractRoot = Join-Path $localAssetsRoot 'extracted\zipvoice'
$debugAssetsRoot = Join-Path $localAssetsRoot 'debug-assets'
$stagingAssetsRoot = Join-Path $localAssetsRoot 'debug-assets-staging'
$backupAssetsRoot = Join-Path $localAssetsRoot 'debug-assets-previous'
$appLibrariesRoot = Join-Path $repositoryRoot 'app\libs'
$lockPath = Join-Path $repositoryRoot 'speech-assets.lock.json'
$maxAssetBytes = 2GB

$assets = @(
    [ordered]@{
        name = 'sherpa-onnx-1.13.4.aar'
        url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar'
    }
    [ordered]@{
        name = 'sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2'
        url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2'
    }
    [ordered]@{
        name = 'vocos_24khz.onnx'
        url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx'
    }
)

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Assert-AllowedSize {
    param([Parameter(Mandatory = $true)][IO.FileInfo]$File)

    if ($File.Length -gt $maxAssetBytes) {
        throw "Asset '$($File.FullName)' is larger than the 2 GiB safety limit."
    }
}

function Get-UniqueFile {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $matches = @(Get-ChildItem -File -Recurse -LiteralPath $Root -ErrorAction Stop |
        Where-Object Name -EQ $Name)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one '$Name' under '$Root'; found $($matches.Count)."
    }
    return $matches[0]
}

function Get-UniqueDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $matches = @(Get-ChildItem -Directory -Recurse -LiteralPath $Root -ErrorAction Stop |
        Where-Object Name -EQ $Name)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one '$Name' directory under '$Root'; found $($matches.Count)."
    }
    return $matches[0]
}

New-Item -ItemType Directory -Force -Path $downloadsRoot, $appLibrariesRoot | Out-Null

$existingLock = $null
if (Test-Path -LiteralPath $lockPath -PathType Leaf) {
    $existingLock = Get-Content -Raw -Encoding UTF8 -LiteralPath $lockPath | ConvertFrom-Json
    if ($existingLock.schemaVersion -ne 1) {
        throw "Unsupported speech asset lock schema '$($existingLock.schemaVersion)'."
    }
    if (@($existingLock.assets).Count -ne $assets.Count) {
        throw "speech-assets.lock.json must contain exactly $($assets.Count) asset records."
    }
}

$downloadRecords = @()
foreach ($asset in $assets) {
    $destination = Join-Path $downloadsRoot $asset.name
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        $partial = "$destination.partial"
        if (Test-Path -LiteralPath $partial) {
            Remove-Item -Force -LiteralPath $partial
        }

        Write-Host "Downloading $($asset.name)..."
        try {
            Invoke-WebRequest -Uri $asset.url -UseBasicParsing -OutFile $partial
            Move-Item -Force -LiteralPath $partial -Destination $destination
        }
        catch {
            if (Test-Path -LiteralPath $partial) {
                Remove-Item -Force -LiteralPath $partial
            }
            throw
        }
    }
    else {
        Write-Host "Reusing $destination"
    }

    $download = Get-Item -LiteralPath $destination
    Assert-AllowedSize -File $download
    $sha256 = Get-Sha256 -Path $download.FullName
    $record = [ordered]@{
        name = $asset.name
        url = $asset.url
        size = [long]$download.Length
        sha256 = $sha256
    }

    if ($null -ne $existingLock) {
        $lockedMatches = @($existingLock.assets | Where-Object {
            $_.name -eq $asset.name -and $_.url -eq $asset.url
        })
        if ($lockedMatches.Count -ne 1) {
            throw "The lock file does not contain exactly one record for '$($asset.name)'."
        }
        $locked = $lockedMatches[0]
        if ([long]$locked.size -ne $record.size -or $locked.sha256 -ne $record.sha256) {
            throw "Downloaded asset '$($asset.name)' does not match speech-assets.lock.json."
        }
    }

    $downloadRecords += $record
    Write-Host "$($asset.name) size=$($record.size) sha256=$($record.sha256)"
}

if (Test-Path -LiteralPath $extractRoot) {
    Remove-Item -Recurse -Force -LiteralPath $extractRoot
}
New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null

$archivePath = Join-Path $downloadsRoot 'sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2'
& tar.exe -xjf $archivePath -C $extractRoot
if ($LASTEXITCODE -ne 0) {
    throw "tar.exe failed to extract '$archivePath' with exit code $LASTEXITCODE."
}

$extractedFiles = @(Get-ChildItem -File -Recurse -LiteralPath $extractRoot)
foreach ($file in $extractedFiles) {
    Assert-AllowedSize -File $file
}

$encoder = Get-UniqueFile -Root $extractRoot -Name 'encoder.int8.onnx'
$decoder = Get-UniqueFile -Root $extractRoot -Name 'decoder.int8.onnx'
$tokens = Get-UniqueFile -Root $extractRoot -Name 'tokens.txt'
$lexicon = Get-UniqueFile -Root $extractRoot -Name 'lexicon.txt'
$prompt = Get-UniqueFile -Root $extractRoot -Name 'leijun-1.wav'
$espeakData = Get-UniqueDirectory -Root $extractRoot -Name 'espeak-ng-data'

foreach ($path in @($stagingAssetsRoot, $backupAssetsRoot)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -Recurse -Force -LiteralPath $path
    }
}

$zipVoiceDestination = Join-Path $stagingAssetsRoot 'models\zipvoice'
$promptDestination = Join-Path $stagingAssetsRoot 'voices\builtin-dev\prompts'
New-Item -ItemType Directory -Force -Path $zipVoiceDestination, $promptDestination | Out-Null

Copy-Item -Force -LiteralPath $encoder.FullName -Destination (Join-Path $zipVoiceDestination 'encoder.int8.onnx')
Copy-Item -Force -LiteralPath $decoder.FullName -Destination (Join-Path $zipVoiceDestination 'decoder.int8.onnx')
Copy-Item -Force -LiteralPath $tokens.FullName -Destination (Join-Path $zipVoiceDestination 'tokens.txt')
Copy-Item -Force -LiteralPath $lexicon.FullName -Destination (Join-Path $zipVoiceDestination 'lexicon.txt')
Copy-Item -Recurse -Force -LiteralPath $espeakData.FullName -Destination (Join-Path $zipVoiceDestination 'espeak-ng-data')
Copy-Item -Force -LiteralPath (Join-Path $downloadsRoot 'vocos_24khz.onnx') -Destination (Join-Path $zipVoiceDestination 'vocos_24khz.onnx')
Copy-Item -Force -LiteralPath $prompt.FullName -Destination (Join-Path $promptDestination 'neutral.wav')

$transcriptBase64 = '6YKj6L+Y5piv5LiJ5Y2B5YWt5bm05YmNLCDkuIDkuZ3lhavkuIPlubQuIOaIkeWRouiAg+S4iuS6huatpuaxieWkp+WtpueahOiuoeeul+acuuezuy4='
$transcript = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($transcriptBase64))
Write-Utf8NoBom -Path (Join-Path $promptDestination 'neutral.txt') -Content ($transcript + [Environment]::NewLine)

$manifestFiles = @(Get-ChildItem -File -Recurse -LiteralPath $stagingAssetsRoot | Sort-Object FullName)
$manifestRecords = @()
foreach ($file in $manifestFiles) {
    Assert-AllowedSize -File $file
    $relativePath = $file.FullName.Substring($stagingAssetsRoot.Length + 1).Replace('\', '/')
    $record = [ordered]@{
        path = $relativePath
        size = [long]$file.Length
        sha256 = Get-Sha256 -Path $file.FullName
    }
    $manifestRecords += $record
    Write-Host "$relativePath size=$($record.size) sha256=$($record.sha256)"
}

$manifest = [ordered]@{
    schemaVersion = 1
    files = @($manifestRecords)
}
$manifestPath = Join-Path $stagingAssetsRoot 'speech-assets.json'
Write-Utf8NoBom -Path $manifestPath -Content (($manifest | ConvertTo-Json -Depth 5) + [Environment]::NewLine)

if (Test-Path -LiteralPath $debugAssetsRoot) {
    Move-Item -LiteralPath $debugAssetsRoot -Destination $backupAssetsRoot
}
try {
    Move-Item -LiteralPath $stagingAssetsRoot -Destination $debugAssetsRoot
    if (Test-Path -LiteralPath $backupAssetsRoot) {
        Remove-Item -Recurse -Force -LiteralPath $backupAssetsRoot
    }
}
catch {
    if ((Test-Path -LiteralPath $backupAssetsRoot) -and -not (Test-Path -LiteralPath $debugAssetsRoot)) {
        Move-Item -LiteralPath $backupAssetsRoot -Destination $debugAssetsRoot
    }
    throw
}

$aarSource = Join-Path $downloadsRoot 'sherpa-onnx-1.13.4.aar'
$aarDestination = Join-Path $appLibrariesRoot 'sherpa-onnx-1.13.4.aar'
$aarPartial = "$aarDestination.partial"
Copy-Item -Force -LiteralPath $aarSource -Destination $aarPartial
Move-Item -Force -LiteralPath $aarPartial -Destination $aarDestination

if ($null -eq $existingLock) {
    $lock = [ordered]@{
        schemaVersion = 1
        assets = @($downloadRecords)
    }
    Write-Utf8NoBom -Path $lockPath -Content (($lock | ConvertTo-Json -Depth 5) + [Environment]::NewLine)
    Write-Host "Created trusted lock file $lockPath"
}

Write-Host "Speech assets ready under $debugAssetsRoot"
Write-Host "sherpa-onnx AAR ready at $aarDestination"
