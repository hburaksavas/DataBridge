param([switch]$KeysOnly,[switch]$GraphOnly)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
Set-Location -LiteralPath $root
$suffix=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(4))
$sourceUser="DBS_$suffix"
$targetUser="DBX_$suffix"
$sourcePassword='Dbt_'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(18))+'a7'
$targetPassword='Dbt_'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(18))+'a7'
$perfSql=if($KeysOnly -or $GraphOnly){"insert into $sourceUser.PERF_ROWS select level,'AKTIF','ROW-'||level,level+0.25,to_date('2026-01-01 13:14:15','YYYY-MM-DD HH24:MI:SS') from dual connect by level<=2;"}else{"insert into $sourceUser.PERF_ROWS select level,'AKTIF','ROW-'||level,level+0.25,to_date('2026-01-01 13:14:15','YYYY-MM-DD HH24:MI:SS') from dual connect by level<=1000000;"}
$sql=@"
whenever sqlerror exit sql.sqlcode
set echo off verify off feedback off
alter session set container=DATABRIDGE_TEST;
create user $sourceUser identified by "$sourcePassword" default tablespace users quota 100M on users;
create user $targetUser identified by "$targetPassword" default tablespace users quota 100M on users;
grant create session,create table,create sequence to $sourceUser,$targetUser;
declare
 procedure fixtures(u varchar2,load_data boolean) is
 begin
 for t in (select table_name from all_tables where owner='DBRIDGE_SRC') loop
   execute immediate 'create table '||u||'.'||t.table_name||' as select * from DBRIDGE_SRC.'||t.table_name||' where 1=0';
   if load_data then execute immediate 'insert into '||u||'.'||t.table_name||' select * from DBRIDGE_SRC.'||t.table_name; end if;
 end loop;
 execute immediate 'alter table '||u||'.MUSTERI add primary key(MUSTERI_ID)';
 execute immediate 'alter table '||u||'.KREDI add primary key(KREDI_ID)';
 execute immediate 'alter table '||u||'.KREDI add constraint FK_KREDI_MUSTERI foreign key(MUSTERI_ID) references '||u||'.MUSTERI(MUSTERI_ID)';
 execute immediate 'alter table '||u||'.ODEME_PLANI add primary key(PLAN_ID)';
 execute immediate 'alter table '||u||'.ODEME_PLANI add constraint FK_PLAN_KREDI foreign key(KREDI_ID) references '||u||'.KREDI(KREDI_ID)';
 execute immediate 'alter table '||u||'.TAKSIT add primary key(PLAN_ID,TAKSIT_NO)';
 execute immediate 'alter table '||u||'.TAKSIT add constraint FK_TAKSIT_PLAN foreign key(PLAN_ID) references '||u||'.ODEME_PLANI(PLAN_ID)';
 execute immediate 'alter table '||u||'.TAKSIT_TAHSILAT add primary key(TAHSILAT_ID)';
 execute immediate 'alter table '||u||'.TAKSIT_TAHSILAT add constraint FK_TAHSILAT_TAKSIT foreign key(PLAN_ID,TAKSIT_NO) references '||u||'.TAKSIT(PLAN_ID,TAKSIT_NO)';
 execute immediate 'alter table '||u||'.TEMINAT add primary key(TEMINAT_ID)';
 execute immediate 'alter table '||u||'.TEMINAT add constraint FK_TEMINAT_KREDI foreign key(KREDI_ID) references '||u||'.KREDI(KREDI_ID)';
 execute immediate 'alter table '||u||'.KREDI_HAREKET add primary key(HAREKET_ID)';
 execute immediate 'create table '||u||'.PERF_ROWS(ID number primary key,STATE varchar2(12),VALUE varchar2(64),AMOUNT number(18,2),CREATED_AT date)';
 end;
begin fixtures('$sourceUser',true);fixtures('$targetUser',false);end;
/
$perfSql
commit;
exit
"@
$output=$sql|sqlplus.exe -s '/ as sysdba'
if($LASTEXITCODE -ne 0 -or ($output -join "`n") -match 'ORA-|SP2-'){
 Write-Host (($output -join "`n").Replace($sourcePassword,'[redacted]').Replace($targetPassword,'[redacted]'))
 throw 'İzole aktarım test şemaları hazırlanamadı.'
}
Write-Host "İzole canlı test hazır: $sourceUser → $targetUser"
Add-Type -AssemblyName System.Security
$fixtureBytes=[Text.Encoding]::UTF8.GetBytes((@{sourceUser=$sourceUser;targetUser=$targetUser;sourcePassword=$sourcePassword;targetPassword=$targetPassword}|ConvertTo-Json -Compress))
[IO.File]::WriteAllBytes((Join-Path $root ".local\transfer-fixture-$suffix.dpapi"),[Security.Cryptography.ProtectedData]::Protect($fixtureBytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))
[Array]::Clear($fixtureBytes,0,$fixtureBytes.Length)
$env:JAVA_HOME=Split-Path (Split-Path (Get-Command javac.exe).Source)
$env:DATABRIDGE_TRANSFER_LIVE='1'
$env:DATABRIDGE_TRANSFER_SOURCE=$sourceUser
$env:DATABRIDGE_TRANSFER_TARGET=$targetUser
$env:DATABRIDGE_TRANSFER_SOURCE_PASSWORD=$sourcePassword
$env:DATABRIDGE_TRANSFER_TARGET_PASSWORD=$targetPassword
try{
 $selection=if($GraphOnly){'-Dtest=GraphLiveTest'}elseif($KeysOnly){'-Dtest=KeyPolicyLiveTest,KeyPolicyTest'}else{'-Dtest=SelectionFilterTest,TransferLiveTest'}
 & '.\tools\apache-maven-3.9.9\bin\mvn.cmd' -q -f app\pom.xml $selection '-DargLine=-Xmx256m' test *> '.local\transfer-live-test.log'
 if($LASTEXITCODE -ne 0){throw 'Canlı aktarım testi başarısız. .local/transfer-live-test.log dosyasını inceleyin.'}
 if($GraphOnly){Write-Host '4 canlı diagram testi geçti: manuel ilişki, koşul, yeni ID eşleme ve geçersiz eşleme kontrolleri.'}elseif($KeysOnly){Write-Host '7 canlı PK politikası testi ve 3 anahtar/şifreli eşleme testi geçti.'}else{Write-Host 'Canlı aktarım, rollback, iptal, snapshot, tekrar çalıştırma ve bir milyon satır testi geçti.'}
}finally{
 Remove-Item Env:DATABRIDGE_TRANSFER_LIVE,Env:DATABRIDGE_TRANSFER_SOURCE,Env:DATABRIDGE_TRANSFER_TARGET,Env:DATABRIDGE_TRANSFER_SOURCE_PASSWORD,Env:DATABRIDGE_TRANSFER_TARGET_PASSWORD -ErrorAction SilentlyContinue
 $sourcePassword=$null;$targetPassword=$null
}
