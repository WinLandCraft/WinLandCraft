# Usage: .\dev.ps1 build   or   .\dev.ps1 runClient
$ErrorActionPreference = 'Stop'
$jdkCandidates = @($env:JAVA_HOME)
$jdkCandidates += @(Get-ChildItem 'C:\Program Files\Eclipse Adoptium\jdk-21*' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
$jdk = $jdkCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe')) } | Select-Object -First 1
if (-not $jdk) { throw 'JDK 21 not found. Set JAVA_HOME to your JDK 21 directory.' }
$env:JAVA_HOME = $jdk
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.gradle-user-home'
Push-Location $PSScriptRoot
try {
    if ($args.Count -eq 0) { & .\gradlew.bat build } else { & .\gradlew.bat @args }
    $buildExit = $LASTEXITCODE
} finally { Pop-Location }
exit $buildExit
