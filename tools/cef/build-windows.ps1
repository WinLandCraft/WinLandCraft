$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$WorkRoot = if ($env:WLC_CEF_WORK_ROOT) { $env:WLC_CEF_WORK_ROOT } else { 'C:\wlc-cef151-codecs' }
$CefCommit = '89cd5813e47d84c68e56ced336c2c01b7dc77b8d'
$ChromiumVersion = '151.0.7922.34'
$Checkout = Join-Path $WorkRoot 'windows'
$Chromium = Join-Path $Checkout 'chromium\src'
$Python = 'C:\Users\Docker\AppData\Local\Programs\Python\Python312\python.exe'
$VsPath = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools'
$env:PATH = "$(Join-Path $WorkRoot 'depot_tools');C:\Program Files\Git\cmd;$env:PATH"

if ((& git -C (Join-Path $Checkout 'cef') rev-parse HEAD) -ne $CefCommit) {
    throw 'CEF checkout does not match the pinned commit'
}
$Version = @{}
Get-Content (Join-Path $Chromium 'chrome\VERSION') | ForEach-Object {
    $Key, $Value = $_ -split '=', 2
    $Version[$Key] = $Value
}
$ActualChromium = "$($Version.MAJOR).$($Version.MINOR).$($Version.BUILD).$($Version.PATCH)"
if ($ActualChromium -ne $ChromiumVersion) { throw "Unexpected Chromium version $ActualChromium" }

$env:CEF_ARCHIVE_FORMAT = 'tar.bz2'
$env:CEF_USE_GN = '1'
$env:DEPOT_TOOLS_WIN_TOOLCHAIN = '0'
$env:GYP_MSVS_OVERRIDE_PATH = $VsPath
$env:GYP_MSVS_VERSION = '2022'
$env:NINJA_CORE_MULTIPLIER = if ($env:NINJA_CORE_MULTIPLIER) { $env:NINJA_CORE_MULTIPLIER } else { '0.50' }
$env:GN_DEFINES = 'is_official_build=true proprietary_codecs=true ffmpeg_branding=Chrome chrome_pgo_phase=0 use_thin_lto=false is_cfi=false symbol_level=0 blink_symbol_level=0 v8_symbol_level=0 cef_api_version=15100'

# Siso prints informational messages such as "offline mode" on stderr. Windows
# PowerShell otherwise promotes those messages to NativeCommandError under Stop.
$PreviousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    & $Python (Join-Path $WorkRoot 'tools\automate-git.py') `
        "--download-dir=$Checkout" `
        "--depot-tools-dir=$(Join-Path $WorkRoot 'depot_tools')" `
        '--branch=7922' `
        "--checkout=$CefCommit" `
        '--no-update' `
        '--force-build' `
        '--force-distrib' `
        '--x64-build' `
        '--no-debug-build' `
        '--build-target=cefsimple' `
        '--no-distrib-docs' `
        '--no-distrib-symbols' 2>&1
    $ExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $PreviousErrorActionPreference
}
if ($ExitCode -ne 0) { throw "CEF build failed with exit code $ExitCode" }
