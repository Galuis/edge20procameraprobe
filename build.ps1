$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# One-click Windows builder for the probe APK.
# It installs only a local JDK/Android command-line SDK/Gradle into %LOCALAPPDATA%.
# No Android Studio is required.

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Tools = Join-Path $env:LOCALAPPDATA 'Edge20Pro-Camera2-Probe\tools'
$Sdk = Join-Path $Tools 'android-sdk'
$GradleVersion = '8.9'
$GradleDir = Join-Path $Tools "gradle-$GradleVersion"
$JavaHome = $env:JAVA_HOME

function Download-File([string]$Url, [string]$Path) {
  Write-Host "Downloading $Url"
  Invoke-WebRequest -Uri $Url -OutFile $Path -UseBasicParsing
}

function Find-Java {
  if ($JavaHome -and (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) { return $JavaHome }
  $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($cmd) {
    $candidate = Split-Path (Split-Path $cmd.Source -Parent) -Parent
    if (Test-Path (Join-Path $candidate 'bin\java.exe')) { return $candidate }
  }
  return $null
}

$JavaHome = Find-Java
if (-not $JavaHome) {
  $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
  if (-not $winget) {
    throw 'Java 17+ is required, and winget.exe was not found. Install a JDK 17+ or use the GitHub Actions build path in the README.'
  }
  Write-Host 'Java not found. Installing Temurin JDK 17 with winget...'
  & $winget.Source install --id EclipseAdoptium.Temurin.17.JDK -e --accept-source-agreements --accept-package-agreements
  $JavaHome = Find-Java
  if (-not $JavaHome) { throw 'JDK installation finished, but java.exe was not found. Open a new PowerShell window and rerun this script.' }
}
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"

New-Item -ItemType Directory -Force -Path $Tools | Out-Null
New-Item -ItemType Directory -Force -Path $Sdk | Out-Null

# Current official Android Command-line Tools package (Windows x64).
$CmdlineZip = Join-Path $Tools 'commandlinetools-win-latest.zip'
$CmdlineUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$Cmdline = Join-Path $Sdk 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $Cmdline 'bin\sdkmanager.bat'))) {
  if (-not (Test-Path $CmdlineZip)) { Download-File $CmdlineUrl $CmdlineZip }
  $Stage = Join-Path $Tools 'cmdline-stage'
  if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $Stage | Out-Null
  Expand-Archive -Path $CmdlineZip -DestinationPath $Stage -Force
  New-Item -ItemType Directory -Force -Path (Join-Path $Sdk 'cmdline-tools') | Out-Null
  if (Test-Path $Cmdline) { Remove-Item $Cmdline -Recurse -Force }
  Move-Item (Join-Path $Stage 'cmdline-tools') $Cmdline
  Remove-Item $Stage -Recurse -Force
}

$env:ANDROID_SDK_ROOT = $Sdk
$SdkManager = Join-Path $Cmdline 'bin\sdkmanager.bat'
$env:Path = "$Sdk\platform-tools;$Sdk\cmdline-tools\latest\bin;$env:Path"

Write-Host 'Installing Android SDK Platform 35 and Build Tools...'
& $SdkManager '--sdk_root=' + $Sdk 'platform-tools' 'platforms;android-35' 'build-tools;35.0.1'
if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }

$GradleZip = Join-Path $Tools "gradle-$GradleVersion-bin.zip"
if (-not (Test-Path (Join-Path $GradleDir 'bin\gradle.bat'))) {
  if (-not (Test-Path $GradleZip)) {
    Download-File "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" $GradleZip
  }
  $GradleStage = Join-Path $Tools 'gradle-stage'
  if (Test-Path $GradleStage) { Remove-Item $GradleStage -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $GradleStage | Out-Null
  Expand-Archive -Path $GradleZip -DestinationPath $GradleStage -Force
  if (Test-Path $GradleDir) { Remove-Item $GradleDir -Recurse -Force }
  Move-Item (Join-Path $GradleStage "gradle-$GradleVersion") $GradleDir
  Remove-Item $GradleStage -Recurse -Force
}

$Gradle = Join-Path $GradleDir 'bin\gradle.bat'
Push-Location $Root
try {
  Write-Host 'Building debug APK...'
  & $Gradle 'assembleDebug' '--no-daemon' '--stacktrace'
  if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
  $Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
  if (-not (Test-Path $Apk)) { throw "Build succeeded but APK was not found at $Apk" }
  Write-Host ''
  Write-Host 'APK created:' -ForegroundColor Green
  Write-Host $Apk -ForegroundColor Green
  Write-Host ''
  Write-Host 'You can now install this APK on the Edge 20 Pro.'
}
finally {
  Pop-Location
}
