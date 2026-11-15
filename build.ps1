param([switch]$NoPackage)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command javac.exe).Source)
$javaVersion = & (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1
if ("$javaVersion" -notmatch '^javac 21\.') { throw 'JDK 21 gerekiyor.' }
$maven = Join-Path $PSScriptRoot 'tools\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) {
    New-Item -ItemType Directory -Force 'tools' | Out-Null
    $url='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
    Invoke-WebRequest -Uri $url -OutFile 'tools\maven.zip'
    $expected=(Invoke-WebRequest -Uri ($url+'.sha512')).Content.Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath 'tools\maven.zip' -Algorithm SHA512).Hash -ne $expected) { throw 'Maven SHA512 doğrulaması başarısız.' }
    Expand-Archive -LiteralPath 'tools\maven.zip' -DestinationPath 'tools' -Force
}
Push-Location frontend
try { & npm.cmd ci --no-audit --no-fund; if($LASTEXITCODE -ne 0){throw 'npm ci başarısız.'}; & npm.cmd run build; if($LASTEXITCODE -ne 0){throw 'React derlemesi başarısız.'} } finally { Pop-Location }
& $maven -f app\pom.xml verify
if($LASTEXITCODE -ne 0){throw 'Java doğrulaması başarısız.'}
Copy-Item -LiteralPath 'app\target\databridge-desktop-0.6.0.jar' -Destination 'app\target\package\databridge-desktop-0.6.0.jar'
Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'app\target\package') -Filter 'databridge-desktop-*.jar' | Where-Object { $_.Name -ne 'databridge-desktop-0.6.0.jar' } | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
if($NoPackage){exit}
$destination=Join-Path $PSScriptRoot ('dist\'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
& (Join-Path $env:JAVA_HOME 'bin\jpackage.exe') --type app-image --name DataBridge --app-version 0.6.0 --vendor DataBridge --icon branding/databridge.ico --dest $destination --input 'app\target\package' --main-jar 'databridge-desktop-0.6.0.jar' --main-class com.databridge.DesktopLauncher --add-modules 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.management,jdk.zipfs' --java-options '-Dfile.encoding=UTF-8' --java-options '-Xmx512m'
if($LASTEXITCODE -ne 0){throw 'Windows paketi oluşturulamadı.'}
$application=Join-Path $destination 'DataBridge'
Set-Content -LiteralPath 'latest-build.txt' -Value $application -Encoding UTF8
Write-Host "Hazır: $application\DataBridge.exe"
