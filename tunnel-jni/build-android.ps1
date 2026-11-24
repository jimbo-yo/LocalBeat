<#
.SYNOPSIS
  Cross-compiles liblocalbeat_tunnel.so for Android (arm64-v8a + x86_64) into
  app/src/connected/jniLibs/<abi>/liblocalbeat_tunnel.so.

.DESCRIPTION
  Requires Android NDK installed under $env:LOCALAPPDATA\Android\Sdk\ndk\
  (install via Android Studio -> SDK Manager -> SDK Tools -> NDK (Side by side))
  and Rust Android targets (`rustup target add aarch64-linux-android x86_64-linux-android`).
#>
$ErrorActionPreference = 'Stop'
$ndkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk'
if (-not (Test-Path $ndkRoot)) {
    throw "Android NDK not found under $ndkRoot. Install 'NDK (Side by side)' in Android Studio SDK Manager."
}
$ndk = (Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$bin = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$jniOut = Join-Path (Split-Path -Parent $PSScriptRoot) 'app\src\connected\jniLibs'

$targets = @(
    @{ Triple = 'aarch64-linux-android'; Abi = 'arm64-v8a'; Linker = 'aarch64-linux-android33-clang.cmd'; Env = 'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER' },
    @{ Triple = 'x86_64-linux-android';  Abi = 'x86_64';    Linker = 'x86_64-linux-android33-clang.cmd';  Env = 'CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER' }
)

Push-Location $PSScriptRoot
try {
    foreach ($t in $targets) {
        Write-Host "Building $($t.Abi) ($($t.Triple))..."
        Set-Item "env:$($t.Env)" (Join-Path $bin $t.Linker)
        cargo build --release --target $t.Triple
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $($t.Triple)" }
        $destDir = Join-Path $jniOut $t.Abi
        New-Item -ItemType Directory -Force $destDir | Out-Null
        Copy-Item "target\$($t.Triple)\release\liblocalbeat_tunnel.so" (Join-Path $destDir 'liblocalbeat_tunnel.so') -Force
    }
} finally {
    Pop-Location
}
Write-Host "Done! Native libraries written to $jniOut"
