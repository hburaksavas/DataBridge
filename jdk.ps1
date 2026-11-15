function Initialize-DataBridgeJdk {
    $configFile = Join-Path $PSScriptRoot 'jdk-path.txt'
    $configuredPath = ''
    if (Test-Path -LiteralPath $configFile) {
        $configuredPath = ([string](Get-Content -LiteralPath $configFile -Raw)).Trim()
    }
    if ($configuredPath) {
        $jdkDirectory = $configuredPath
        if (-not [System.IO.Path]::IsPathRooted($jdkDirectory)) {
            $jdkDirectory = Join-Path $PSScriptRoot $jdkDirectory
        }
    } else {
        $compiler = Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $compiler) {
            throw 'JDK 21 bulunamadi. jdk-path.txt dosyasina JDK kok dizinini yazin veya JDK bin dizinini PATH ile belirtin.'
        }
        $jdkDirectory = Split-Path (Split-Path $compiler.Source)
    }
    foreach ($tool in @('java', 'javac', 'jpackage', 'jlink')) {
        if (-not (Test-Path -LiteralPath (Join-Path $jdkDirectory "bin\$tool.exe") -PathType Leaf)) {
            throw "Gecersiz JDK dizini: $jdkDirectory (bin\$tool.exe bulunamadi). jdk-path.txt ayarini kontrol edin."
        }
    }
    $jdkDirectory = (Resolve-Path -LiteralPath $jdkDirectory).Path
    $compilerVersion = & (Join-Path $jdkDirectory 'bin\javac.exe') -version 2>&1
    if ($LASTEXITCODE -ne 0 -or "$compilerVersion" -notmatch '^javac 21\.') {
        throw "JDK 21 gerekiyor. Secilen JDK: $jdkDirectory ($compilerVersion)"
    }
    $env:JAVA_HOME = $jdkDirectory
    $env:PATH = (Join-Path $jdkDirectory 'bin') + [System.IO.Path]::PathSeparator + $env:PATH
    Write-Host "JDK: $jdkDirectory"
}
