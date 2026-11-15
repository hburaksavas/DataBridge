$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'jdk.ps1')
Initialize-DataBridgeJdk
$jar = Join-Path $PSScriptRoot 'app\target\package\databridge-desktop-0.6.0.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Once .\build.ps1 -NoPackage ile uygulamayi derleyin.'
}
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -jar $jar
if ($LASTEXITCODE -ne 0) { throw "Uygulama hata ile kapandi: $LASTEXITCODE" }
