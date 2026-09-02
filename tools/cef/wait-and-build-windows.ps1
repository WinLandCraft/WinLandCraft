$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$WorkRoot = 'C:\wlc-cef151-codecs'
$Log = Join-Path $WorkRoot 'windows-build.log'
$SyncPid = 2544

while ($true) {
    $Process = Get-CimInstance Win32_Process -Filter "ProcessId=$SyncPid" -ErrorAction SilentlyContinue
    if (!$Process -or $Process.CommandLine -notlike '*automate-git.py*--no-build*') { break }
    Start-Sleep -Seconds 15
}

try {
    "[$(Get-Date -Format o)] Windows source sync exited; starting the pinned codec build." |
        Out-File -FilePath $Log -Encoding utf8 -Append
    & (Join-Path $WorkRoot 'tools\build-windows.ps1') *>&1 |
        Tee-Object -FilePath $Log -Append
    if ($LASTEXITCODE -ne 0) { throw "Build script returned $LASTEXITCODE" }
    "[$(Get-Date -Format o)] Windows codec build completed." |
        Out-File -FilePath $Log -Encoding utf8 -Append
} catch {
    "[$(Get-Date -Format o)] Windows codec build failed: $($_ | Out-String)" |
        Out-File -FilePath $Log -Encoding utf8 -Append
    throw
}
