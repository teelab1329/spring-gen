<#
Installs spring-gen on Windows (PowerShell 5.1 and PowerShell 7).

  irm https://raw.githubusercontent.com/teelab1329/spring-gen/main/scripts/install.ps1 | iex

With options:

  & ([scriptblock]::Create((irm https://raw.githubusercontent.com/teelab1329/spring-gen/main/scripts/install.ps1))) -Version v1.0.0

Parameters:
  -Version     release tag to install (default: latest)
  -InstallDir  install directory (default: %LOCALAPPDATA%\Programs\spring-gen)

Environment:
  SPRING_GEN_VERSION, SPRING_GEN_INSTALL_DIR, SPRING_GEN_BASE_URL
#>
[CmdletBinding()]
param(
    [string]$Version = $env:SPRING_GEN_VERSION,
    [string]$InstallDir = $env:SPRING_GEN_INSTALL_DIR
)

$ErrorActionPreference = 'Stop'
# Write-Information is off by default in some hosts; keep our progress visible.
$InformationPreference = 'Continue'

# Windows PowerShell 5.1 defaults to TLS 1.0, which GitHub rejects.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$repo = 'teelab1329/spring-gen'
$asset = $null
$temp = Join-Path ([IO.Path]::GetTempPath()) ("spring-gen-" + [guid]::NewGuid().ToString())

try {
    if ($env:SPRING_GEN_BASE_URL) {
        $baseUrl = $env:SPRING_GEN_BASE_URL
    }
    elseif ($Version) {
        $baseUrl = "https://github.com/$repo/releases/download/$Version"
    }
    else {
        $baseUrl = "https://github.com/$repo/releases/latest/download"
    }

    switch ($env:PROCESSOR_ARCHITECTURE) {
        'AMD64' { $arch = 'amd64' }
        'ARM64' { $arch = 'arm64' }
        default {
            throw "install: unsupported processor architecture '$env:PROCESSOR_ARCHITECTURE' (need AMD64 or ARM64)"
        }
    }

    $asset = "spring-gen_windows_$arch.zip"
    New-Item -ItemType Directory -Path $temp -Force | Out-Null

    Write-Information "install: downloading $asset from $baseUrl"
    try {
        Invoke-WebRequest -Uri "$baseUrl/$asset" -OutFile (Join-Path $temp $asset) -UseBasicParsing
        Invoke-WebRequest -Uri "$baseUrl/checksums.txt" -OutFile (Join-Path $temp 'checksums.txt') -UseBasicParsing
    }
    catch {
        throw "install: download failed. If the repository is private, use 'gh release download' or make it public. $($_.Exception.Message)"
    }

    $checksums = Get-Content (Join-Path $temp 'checksums.txt')
    $line = $checksums | Where-Object { $_ -match "\s+\Q$asset\E$" } | Select-Object -First 1
    if (-not $line) {
        throw "install: checksums.txt has no entry for $asset"
    }
    $expected = ($line -split '\s+')[0]
    $actual = (Get-FileHash (Join-Path $temp $asset) -Algorithm SHA256).Hash
    if ($actual -ne $expected) {
        throw "install: checksum mismatch for $asset (expected $expected, actual $actual)"
    }

    Expand-Archive -Path (Join-Path $temp $asset) -DestinationPath $temp -Force

    if (-not $InstallDir) {
        $InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\spring-gen'
    }
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

    $target = Join-Path $InstallDir 'spring-gen.exe'
    Copy-Item (Join-Path $temp 'spring-gen.exe') $target -Force

    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $userPath) { $userPath = '' }
    $pathEntries = $userPath -split ';' | Where-Object { $_ }
    if ($pathEntries -notcontains $InstallDir) {
        $newPath = if ($userPath) { "$userPath;$InstallDir" } else { $InstallDir }
        [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        Write-Information "install: added $InstallDir to your user PATH (open a new terminal to use it)"
    }
    if (($env:Path -split ';') -notcontains $InstallDir) {
        $env:Path = "$env:Path;$InstallDir"
    }

    Write-Information "install: installed $target"
    & $target version
}
finally {
    if (Test-Path $temp) {
        Remove-Item -Recurse -Force $temp
    }
}
