$ErrorActionPreference = 'Stop'
$root=Split-Path $PSScriptRoot
Set-Location -LiteralPath $root
Add-Type -AssemblyName System.Security
$secretFile=Join-Path $root '.local\oracle-test.dpapi'
if(-not(Test-Path -LiteralPath $secretFile)){throw 'Önce scripts/provision-oracle-test.ps1 çalıştırın.'}
$clear=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($secretFile),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
$credentials=[Text.Encoding]::UTF8.GetString($clear)|ConvertFrom-Json
[Array]::Clear($clear,0,$clear.Length)
$env:JAVA_HOME=Split-Path (Split-Path (Get-Command javac.exe).Source)
$env:DATABRIDGE_LIVE_ORACLE='1'
$env:DATABRIDGE_ORACLE_TEST_PASSWORD=$credentials.sourcePassword
$env:DATABRIDGE_ORACLE_TARGET_PASSWORD=$credentials.targetPassword
try {
    & '.\tools\apache-maven-3.9.9\bin\mvn.cmd' -q -f app\pom.xml '-Dtest=OracleLiveTest' test *> '.local\oracle-jdbc-test.log'
    if($LASTEXITCODE -ne 0){throw 'Gerçek JDBC testi başarısız. .local/oracle-jdbc-test.log dosyasını inceleyin.'}
}finally{Remove-Item Env:DATABRIDGE_LIVE_ORACLE,Env:DATABRIDGE_ORACLE_TEST_PASSWORD,Env:DATABRIDGE_ORACLE_TARGET_PASSWORD -ErrorAction SilentlyContinue}
$token=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$stdout=Join-Path $root '.local\oracle-api-run.log'
$stderr=Join-Path $root '.local\oracle-api-error.log'
$java=Join-Path $env:JAVA_HOME 'bin\java.exe'
$jar=Join-Path $root 'app\target\package\databridge-desktop-0.6.0.jar'
$process=Start-Process -FilePath $java -ArgumentList @("-Ddatabridge.token=$token",'-Dfile.encoding=UTF-8','-jar',$jar,'--headless') -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
$checks=[Collections.Generic.List[object]]::new()
function Assert-Live([bool]$ok,[string]$name,[object]$detail){
    if(-not $ok){throw "Canlı test başarısız: $name"}
    $checks.Add(@{name=$name;passed=$true;detail=$detail});Write-Host "PASS: $name"
}
try {
    $deadline=[DateTime]::UtcNow.AddSeconds(35)
    $port=$null
    while([DateTime]::UtcNow -lt $deadline){
        if($process.HasExited){throw 'Test backend başlatılamadı.'}
        if(Test-Path -LiteralPath $stdout){$log=Get-Content -LiteralPath $stdout -Raw;if($log -match 'Tomcat started on port (\d+)'){$port=$Matches[1];break}}
        Start-Sleep -Milliseconds 200
    }
    if(-not $port){throw 'Backend açılış zaman aşımı.'}
    $base="http://127.0.0.1:$port/api"
    $headers=@{Authorization="Bearer $token"}
    function Api([string]$path,[object]$body=$null){
        if($null -eq $body){$result=Invoke-RestMethod -Uri ($base+$path) -Headers $headers;return $result}
        $result=Invoke-RestMethod -Uri ($base+$path) -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Depth 30 -Compress)))
        return $result
    }
    $existing=@(Api '/connections')
    function Profile([string]$name,[string]$username,[bool]$readOnly){
        $id=($existing|Where-Object {$_.name -eq $name -and $_.service -eq 'databridge_test'}|Select-Object -First 1).id
        return @{id=$id;name=$name;folder='Yerel Oracle 26ai';environment=$(if($readOnly){'DEV'}else{'TEST'});username=$username;mode='BASIC';host='localhost';port=1521;service='databridge_test';database='';descriptor='';role='NORMAL';color=$(if($readOnly){'#386ce0'}else{'#42a88c'});readOnly=$readOnly;passwordSaved=$false;origin='LOCAL_TEST'}
    }
    $source=Api '/connections' @{profile=(Profile 'Oracle 26ai · Kaynak' 'DBRIDGE_SRC' $true);password=$credentials.sourcePassword;rememberPassword=$true;clearPassword=$false}
    $target=Api '/connections' @{profile=(Profile 'Oracle 26ai · Hedef' 'DBRIDGE_TGT' $false);password=$credentials.targetPassword;rememberPassword=$true;clearPassword=$false}
    $sourceResult=Api '/connections/test' @{profile=$source;password=''}
    Assert-Live ($sourceResult.ok -and $sourceResult.user -eq 'DBRIDGE_SRC') 'Kaynak bağlantısı / kayıtlı DPAPI parolası' $sourceResult
    $targetResult=Api '/connections/test' @{profile=$target;password=''}
    Assert-Live ($targetResult.ok -and $targetResult.user -eq 'DBRIDGE_TGT') 'Hedef bağlantısı' $targetResult
    $tables=@(Api "/connections/$($source.id)/tables" @{schema='DBRIDGE_SRC';password=''})
    Assert-Live ($tables.Count -eq 7) 'Kaynak tabloları listeleniyor' ($tables.TABLE_NAME)
    $targetTables=@(Api "/connections/$($target.id)/tables" @{schema='DBRIDGE_TGT';password=''})
    Assert-Live ($targetTables.Count -eq 7) 'Hedef tabloları listeleniyor' ($targetTables.TABLE_NAME)
    $discovery=Api "/connections/$($source.id)/discover" @{schema='DBRIDGE_SRC';table='KREDI';password=''}
    Assert-Live (@($discovery.relations).Count -eq 3) 'KREDI doğrudan FK keşfi' $discovery.relations
    Assert-Live (@($discovery.relations|Where-Object {$_.DIRECTION -eq 'PARENT' -and $_.PARENT_TABLE -eq 'MUSTERI'}).Count -eq 1) 'Üst kayıt yönü: MUSTERI' 'PARENT'
    Assert-Live (@($discovery.relations|Where-Object {$_.DIRECTION -eq 'CHILD'}).Count -eq 2) 'Alt kayıt yönleri: ODEME_PLANI / TEMINAT' 'CHILD'
    Assert-Live (@($discovery.relations|Where-Object {$_.CHILD_TABLE -eq 'KREDI_HAREKET'}).Count -eq 0) 'FK olmayan ilişki uydurulmuyor' 'KREDI_HAREKET kapsam dışında'
    $composite=Api "/connections/$($source.id)/discover" @{schema='DBRIDGE_SRC';table='TAKSIT';password=''}
    $pairs=@(($composite.relations|Where-Object {$_.CONSTRAINT_NAME -eq 'FK_TAHSILAT_TAKSIT'}).COLUMN_PAIRS)
    Assert-Live ($pairs.Count -eq 2 -and $pairs[0].parent -eq 'PLAN_ID' -and $pairs[1].parent -eq 'TAKSIT_NO') 'Bileşik FK kolon sırası' $pairs
    $tns='DBRIDGE_LOCAL=(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=localhost)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=databridge_test)))'
    $client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
    $multipart=[Net.Http.MultipartFormDataContent]::new();$multipart.Add([Net.Http.StringContent]::new($tns),'file','tnsnames.ora')
    try{$response=$client.PostAsync($base+'/imports/tns',$multipart).GetAwaiter().GetResult();$response.EnsureSuccessStatusCode()|Out-Null;$preview=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json}finally{$multipart.Dispose();$client.Dispose()}
    Assert-Live ($preview.aliases[0].alias -eq 'DBRIDGE_LOCAL') 'TNS HTTP dosya içe aktarma' $preview.aliases[0].alias
    $tnsProfile=Profile 'Oracle 26ai · TNS testi' 'DBRIDGE_SRC' $true
    $tnsProfile.mode='TNS';$tnsProfile.database='DBRIDGE_LOCAL';$tnsProfile.descriptor=$preview.aliases[0].descriptor
    $tnsResult=Api '/connections/test' @{profile=$tnsProfile;password=$credentials.sourcePassword}
    Assert-Live ($tnsResult.ok) 'TNS descriptor ile gerçek Oracle bağlantısı' $tnsResult
    $oldScenario=@(Api '/scenarios')|Where-Object {$_.name -eq 'Yerel Oracle 26ai · Kredi ilişkileri'}|Select-Object -First 1
    $scenario=Api '/scenarios' @{id=$oldScenario.id;name='Yerel Oracle 26ai · Kredi ilişkileri';owner='DBRIDGE_SRC';table='KREDI';condition="DURUM = 'AKTIF'";limit=100;relations=@($discovery.relations);sourceId=$source.id}
    Assert-Live (@($scenario.relations).Count -eq 3) 'Gerçek FK keşfinden senaryo kaydı' $scenario.id
    try{Api '/connections/test' @{profile=$source;password='Invalid_Test_Password_Only'}|Out-Null;throw 'Yanlış parola kabul edildi.'}catch{if($_.Exception.Response.StatusCode.value__ -ne 422){throw}}
    Assert-Live $true 'Yanlış parola kullanıcıya Oracle hatası olarak dönüyor' 'HTTP 422 / ORA-01017'
    Assert-Live $true 'Salt okunur transaction yazmayı reddediyor (JDBC canlı testi)' 'ORA-01456'
    $allRelations=@($discovery.relations)
    foreach($child in @('ODEME_PLANI','TAKSIT')) {
        $nested=Api "/connections/$($source.id)/discover" @{schema='DBRIDGE_SRC';table=$child;password=''}
        $allRelations+=@($nested.relations|Where-Object {$_.DIRECTION -eq 'CHILD'})
    }
    $allRelations=@($allRelations|Sort-Object CONSTRAINT_NAME -Unique)
    $request=@{sourceId=$source.id;targetId=$target.id;sourcePassword='';targetPassword='';owner='DBRIDGE_SRC';table='KREDI';condition="DURUM='AKTIF'";limit=100;relations=$allRelations}
    $request.keyPolicies=@{}
    foreach($name in @('KREDI')+@($allRelations.CHILD_TABLE)+@($allRelations.PARENT_TABLE)){$request.keyPolicies[$name]=@{mode='KEEP';onConflict='SKIP'}}
    $transferPreview=Api '/transfers/preview' $request
    Assert-Live ($transferPreview.total -eq 33) 'Aktarım önizlemesi: 2 aktif kredi ve ilişkili 33 kayıt' $transferPreview.tables
    $run=Api '/transfers' @{token=$transferPreview.token}
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    do { Start-Sleep -Milliseconds 100; $run=Api "/transfers/$($run.id)" } while($run.status -in @('RUNNING','QUEUED') -and [DateTime]::UtcNow -lt $deadline)
    Assert-Live ($run.status -eq 'COMPLETED' -and ($run.inserted+$run.skipped) -eq 33) 'Uygulama API ile gerçek Oracle aktarımı ve commit' $run
    $repeatPreview=Api '/transfers/preview' $request
    $repeat=Api '/transfers' @{token=$repeatPreview.token}
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    do { Start-Sleep -Milliseconds 100; $repeat=Api "/transfers/$($repeat.id)" } while($repeat.status -in @('RUNNING','QUEUED') -and [DateTime]::UtcNow -lt $deadline)
    Assert-Live ($repeat.status -eq 'COMPLETED' -and $repeat.inserted -eq 0 -and $repeat.skipped -eq 33) 'Tekrarlı aktarımda 33 mevcut kayıt atlandı' $repeat
    Api '/scenarios' @{id=$scenario.id;name='Yerel Oracle 26ai · Kredi ilişkileri';owner='DBRIDGE_SRC';table='KREDI';condition="DURUM = 'AKTIF'";limit=100;relations=$allRelations;sourceId=$source.id;keyPolicies=$request.keyPolicies}|Out-Null
    $newKeys=@{}
    foreach($pair in @(@('MUSTERI','MUSTERI_ID','SEQ_MUSTERI'),@('KREDI','KREDI_ID','SEQ_KREDI'),@('ODEME_PLANI','PLAN_ID','SEQ_ODEME_PLANI'),@('TEMINAT','TEMINAT_ID','SEQ_TEMINAT'),@('TAKSIT_TAHSILAT','TAHSILAT_ID','SEQ_TAHSILAT'))) {
        $newKeys[$pair[0]]=@{mode='SEQUENCE';column=$pair[1];sequence=$pair[2];onConflict='ERROR'}
    }
    $newKeys['TAKSIT']=@{mode='KEEP';onConflict='ERROR'}
    $keyScenarioOld=@(Api '/scenarios')|Where-Object {$_.name -eq 'Yerel Oracle 26ai · Yeni PK ile kredi'}|Select-Object -First 1
    $keyScenario=Api '/scenarios' @{id=$keyScenarioOld.id;name='Yerel Oracle 26ai · Yeni PK ile kredi';owner='DBRIDGE_SRC';table='KREDI';condition="DURUM = 'AKTIF'";limit=100;relations=$allRelations;sourceId=$source.id;keyPolicies=$newKeys}
    Assert-Live ($keyScenario.version -eq 3 -and $keyScenario.keyPolicies.KREDI.sequence -eq 'SEQ_KREDI') 'v3 PK politikası senaryo API kaydı' $keyScenario.id

    $manual=@{CHILD_OWNER='DBRIDGE_SRC';CHILD_TABLE='KREDI_HAREKET';PARENT_OWNER='DBRIDGE_SRC';PARENT_TABLE='KREDI';CONSTRAINT_NAME='M_KREDI_HAREKET';DIRECTION='CHILD';SOURCE='MANUAL';COLUMN_PAIRS=@(@{child='REFERANS_NO';parent='KREDI_NO'})}
    $graphRelations=@($allRelations)+@($manual)
    $graphTables=@('MUSTERI','KREDI','ODEME_PLANI','TAKSIT','TAKSIT_TAHSILAT','TEMINAT','KREDI_HAREKET')
    $positions=@{MUSTERI=@{x=25;y=140};KREDI=@{x=275;y=140};ODEME_PLANI=@{x=525;y=30};TAKSIT=@{x=775;y=30};TAKSIT_TAHSILAT=@{x=1025;y=30};TEMINAT=@{x=525;y=180};KREDI_HAREKET=@{x=525;y=330}}
    $graphOld=@(Api '/scenarios')|Where-Object {$_.name -eq 'Kredi diagramı · FK ve manuel ilişki'}|Select-Object -First 1
    $graphPolicies=@{};foreach($name in $graphTables){$graphPolicies[$name]=@{mode='KEEP';onConflict='SKIP'}}
    $graphScenario=Api '/scenarios' @{id=$graphOld.id;name='Kredi diagramı · FK ve manuel ilişki';owner='DBRIDGE_SRC';table='KREDI';condition="DURUM='AKTIF'";limit=100;relations=$graphRelations;sourceId=$source.id;keyPolicies=$graphPolicies;graph=@{tables=$graphTables;positions=$positions};tableFilters=@{KREDI_HAREKET="REFERANS_TIPI='KREDI'"}}
    Assert-Live ($graphScenario.graph.tables.Count -eq 7 -and $graphScenario.relations[-1].SOURCE -eq 'MANUAL') 'Diagram yerleşimi ve manuel ilişki kalıcılığı' $graphScenario.id
    $graphPreview=Api '/transfers/preview' @{sourceId=$source.id;targetId=$target.id;owner='DBRIDGE_SRC';table='KREDI';condition="DURUM='AKTIF'";limit=100;relations=$graphRelations;keyPolicies=$graphPolicies;tableFilters=@{KREDI_HAREKET="REFERANS_TIPI='KREDI'"}}
    Assert-Live ($graphPreview.total -eq 35) 'Manuel KREDI_NO ilişkisi ile 35 kayıt önizlemesi' $graphPreview.tables
    Invoke-RestMethod -Uri ($base+'/transfers/preview/'+$graphPreview.token) -Method Delete -Headers $headers|Out-Null
    $options=Api '/keys/options' @{connectionId=$target.id;password='';tables=@('KREDI','MUSTERI','ODEME_PLANI')}
    Assert-Live (@($options.tables.KREDI.suggestions|Where-Object {$_.sequence -eq 'SEQ_KREDI'}).Count -ge 1) 'Gerçek hedef metadata ile SEQ_KREDI isim önerisi' $options.tables.KREDI.suggestions
    $report=@{timestamp=(Get-Date -Format o);oracle='Oracle AI Database 26ai Free 23.26.3.0.0';service='localhost:1521/databridge_test';sourceSchema='DBRIDGE_SRC';targetSchema='DBRIDGE_TGT';checks=$checks;scope='Gerçek bağlantı, metadata, TNS, FK keşfi, kalıcılık ve Oracle → Oracle ilişkili veri aktarımı.'}
    $report|ConvertTo-Json -Depth 30|Set-Content -LiteralPath 'oracle-live-test-results.json' -Encoding utf8
    Write-Host "Tamamlandı: $($checks.Count) API/canlı kontrol ve 2 JDBC testi geçti."
}finally{
    if(-not $process.HasExited){Stop-Process -Id $process.Id}
    $credentials=$null;$token=$null
}
