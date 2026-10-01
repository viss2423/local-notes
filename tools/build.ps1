param([switch]$Setup, [string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $projectRoot '.tools'
$jdkDirectory = Get-ChildItem -LiteralPath $toolRoot -Directory -Filter 'jdk-*' | Select-Object -First 1
if (-not $jdkDirectory) { throw 'Run python tools/bootstrap.py first.' }
$env:JAVA_HOME = $jdkDirectory.FullName
$env:ANDROID_HOME = Join-Path $toolRoot 'sdk'
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-home'
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-home'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
if ($Setup) {
    $sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat'
    (1..100 | ForEach-Object { 'y' }) | & $sdkManager --licenses
    if ($LASTEXITCODE -ne 0) { throw 'SDK license setup failed.' }
    & $sdkManager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed.' }
}
Push-Location $projectRoot
try {
    $ErrorActionPreference = 'Continue' # Native compiler warnings on stderr are not PowerShell failures.
    & (Join-Path $toolRoot 'gradle-8.9/bin/gradle.bat') --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed. See preceding errors.' }
} finally { Pop-Location }
