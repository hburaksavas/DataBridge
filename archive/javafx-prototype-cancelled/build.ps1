$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$jdkBin = Split-Path (Get-Command javac.exe).Source
$version = & (Join-Path $jdkBin 'javac.exe') -version 2>&1
if ("$version" -notmatch '^javac 21\.') { throw 'Derleme için JDK 21 gerekiyor.' }
$tools = Join-Path $projectRoot 'tools'
$jmods = Join-Path $tools 'javafx-jmods-21.0.2'
if (-not (Test-Path -LiteralPath $jmods)) {
    New-Item -ItemType Directory -Force $tools | Out-Null
    $archive = Join-Path $tools 'javafx-jmods.zip'
    Invoke-WebRequest 'https://download2.gluonhq.com/openjfx/21.0.2/openjfx-21.0.2_windows-x64_bin-jmods.zip' -OutFile $archive
    Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
}
$build = Join-Path $projectRoot 'build'
$classes = Join-Path $build 'classes'
$inputDir = Join-Path $build 'input'
New-Item -ItemType Directory -Force $classes,$inputDir | Out-Null
$sources = @(Get-ChildItem (Join-Path $projectRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
& (Join-Path $jdkBin 'javac.exe') --release 21 -encoding UTF-8 --module-path $jmods -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java derlemesi başarısız.' }
Copy-Item (Join-Path $projectRoot 'src\databridge\theme.css') (Join-Path $classes 'databridge\theme.css')
& (Join-Path $jdkBin 'jar.exe') --create --file (Join-Path $inputDir 'databridge.jar') --main-class databridge.DataBridgeApp -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR oluşturulamadı.' }
$destination = Join-Path $projectRoot ('dist\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
& (Join-Path $jdkBin 'jpackage.exe') --type app-image --dest $destination --name DataBridge --app-version 0.2.0 --vendor DataBridge --module-path "$inputDir;$jmods" --module databridge.prototype/databridge.DataBridgeApp
if ($LASTEXITCODE -ne 0) { throw 'Windows paketi oluşturulamadı.' }
$appPath = Join-Path $destination 'DataBridge'
Write-Host "Hazır: $appPath\DataBridge.exe"
Set-Content -LiteralPath (Join-Path $projectRoot 'latest-build.txt') -Value $appPath -Encoding UTF8
