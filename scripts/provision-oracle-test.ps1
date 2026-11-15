$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$local = Join-Path $root '.local'
New-Item -ItemType Directory -Force $local | Out-Null
$credentialsFile = Join-Path $local 'oracle-test.dpapi'
Add-Type -AssemblyName System.Security
if (Test-Path -LiteralPath $credentialsFile) {
    $clear = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($credentialsFile),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    $saved = [Text.Encoding]::UTF8.GetString($clear) | ConvertFrom-Json
    [Array]::Clear($clear,0,$clear.Length)
}
function New-TestPassword {
    return 'Dbt_' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(18)) + 'a7'
}
$adminPassword = New-TestPassword
$sourcePassword = New-TestPassword
$targetPassword = New-TestPassword
if ($saved) { $adminPassword=$saved.adminPassword; $sourcePassword=$saved.sourcePassword; $targetPassword=$saved.targetPassword }
$probe = @'
set heading off feedback off pages 0
select count(*) from v$pdbs where name='DATABRIDGE_TEST';
exit
'@ | & sqlplus.exe -s '/ as sysdba'
if ($LASTEXITCODE -ne 0 -or ($probe -join '') -match 'ORA-|SP2-') { throw 'Oracle yönetici bağlantısı başarısız.' }
if (($probe -join '').Trim() -ne '0' -and -not $saved) { throw 'DATABRIDGE_TEST mevcut fakat bu çalışma alanının kimlik bilgisi yok. Otomatik değişiklik yapılmadı.' }
$dataDirectory = 'D:\ORACLEFREE26AI\ORADATA\FREE\DATABRIDGE_TEST'
New-Item -ItemType Directory -Force $dataDirectory | Out-Null
$credentials = @{ service='databridge_test'; host='localhost'; port=1521; sourceUser='DBRIDGE_SRC'; sourcePassword=$sourcePassword; targetUser='DBRIDGE_TGT'; targetPassword=$targetPassword; adminPassword=$adminPassword }
$bytes = [Text.Encoding]::UTF8.GetBytes(($credentials | ConvertTo-Json -Compress))
$protected = [Security.Cryptography.ProtectedData]::Protect($bytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
[IO.File]::WriteAllBytes($credentialsFile,$protected)
[Array]::Clear($bytes,0,$bytes.Length)
$createPdb = @"
create pluggable database DATABRIDGE_TEST admin user DBRIDGE_ADMIN identified by "$adminPassword"
 file_name_convert=('D:\ORACLEFREE26AI\ORADATA\FREE\PDBSEED\','$dataDirectory\');
alter pluggable database DATABRIDGE_TEST open;
alter pluggable database DATABRIDGE_TEST save state;
"@
if (($probe -join '').Trim() -ne '0') {
    $createPdb = ''
    $existing = @'
whenever sqlerror exit sql.sqlcode
set heading off feedback off pages 0
alter session set container=DATABRIDGE_TEST;
select count(*) from dba_tables where owner in ('DBRIDGE_SRC','DBRIDGE_TGT');
exit
'@ | & sqlplus.exe -s '/ as sysdba'
    if($LASTEXITCODE -ne 0){throw 'Mevcut test PDB okunamadı.'}
    $count = ($existing -join '').Trim()
    if($count -eq '14'){ Write-Host 'Mevcut DATABRIDGE_TEST hazır; veriler değiştirilmedi.'; exit 0 }
    if($count -ne '0'){throw 'Kısmen kurulmuş tablolar bulundu. Otomatik silme yapılmadı.'}
}
$sql = @"
whenever sqlerror exit sql.sqlcode
set echo off verify off feedback on
$createPdb
alter session set container=DATABRIDGE_TEST;
declare n number; begin
 select count(*) into n from dba_tablespaces where tablespace_name='USERS';
 if n=0 then execute immediate 'create tablespace users datafile ''$dataDirectory\USERS01.DBF'' size 100M autoextend on next 10M maxsize 2G'; end if;
end;
/
create user DBRIDGE_SRC identified by "$sourcePassword" default tablespace users quota 100M on users;
create user DBRIDGE_TGT identified by "$targetPassword" default tablespace users quota 100M on users;
grant create session, create table to DBRIDGE_SRC, DBRIDGE_TGT;
create table DBRIDGE_SRC.MUSTERI (MUSTERI_ID number primary key, AD varchar2(80), TCKN varchar2(11));
create table DBRIDGE_SRC.KREDI (KREDI_ID number primary key, MUSTERI_ID number not null,
 KREDI_NO varchar2(30) unique, DURUM varchar2(12), TUTAR number(18,2),
 constraint FK_KREDI_MUSTERI foreign key(MUSTERI_ID) references DBRIDGE_SRC.MUSTERI(MUSTERI_ID));
create table DBRIDGE_SRC.ODEME_PLANI (PLAN_ID number primary key, KREDI_ID number not null,
 constraint FK_PLAN_KREDI foreign key(KREDI_ID) references DBRIDGE_SRC.KREDI(KREDI_ID));
create table DBRIDGE_SRC.TAKSIT (PLAN_ID number, TAKSIT_NO number, TUTAR number(18,2),
 constraint PK_TAKSIT primary key(PLAN_ID,TAKSIT_NO),
 constraint FK_TAKSIT_PLAN foreign key(PLAN_ID) references DBRIDGE_SRC.ODEME_PLANI(PLAN_ID));
create table DBRIDGE_SRC.TAKSIT_TAHSILAT (TAHSILAT_ID number primary key, PLAN_ID number, TAKSIT_NO number,
 constraint FK_TAHSILAT_TAKSIT foreign key(PLAN_ID,TAKSIT_NO) references DBRIDGE_SRC.TAKSIT(PLAN_ID,TAKSIT_NO));
create table DBRIDGE_SRC.TEMINAT (TEMINAT_ID number primary key, KREDI_ID number,
 constraint FK_TEMINAT_KREDI foreign key(KREDI_ID) references DBRIDGE_SRC.KREDI(KREDI_ID));
create table DBRIDGE_SRC.KREDI_HAREKET (HAREKET_ID number primary key, REFERANS_NO varchar2(30), REFERANS_TIPI varchar2(12), TUTAR number(18,2));
insert into DBRIDGE_SRC.MUSTERI values(1,'Sentetik Musteri A','00000000001');
insert into DBRIDGE_SRC.MUSTERI values(2,'Sentetik Musteri B','00000000002');
insert into DBRIDGE_SRC.KREDI values(101,1,'KRD-101','AKTIF',125000.50);
insert into DBRIDGE_SRC.KREDI values(102,1,'KRD-102','KAPALI',30000);
insert into DBRIDGE_SRC.KREDI values(103,2,'KRD-103','AKTIF',85000);
insert into DBRIDGE_SRC.ODEME_PLANI values(1001,101);
insert into DBRIDGE_SRC.ODEME_PLANI values(1002,102);
insert into DBRIDGE_SRC.ODEME_PLANI values(1003,103);
insert into DBRIDGE_SRC.TAKSIT select 1001,level,10416.71 from dual connect by level<=12;
insert into DBRIDGE_SRC.TAKSIT select 1002,level,5000 from dual connect by level<=6;
insert into DBRIDGE_SRC.TAKSIT select 1003,level,7083.33 from dual connect by level<=12;
insert into DBRIDGE_SRC.TAKSIT_TAHSILAT values(1,1001,1);
insert into DBRIDGE_SRC.TEMINAT values(1,101);
insert into DBRIDGE_SRC.TEMINAT values(2,103);
insert into DBRIDGE_SRC.KREDI_HAREKET values(1,'KRD-101','KREDI',1000);
insert into DBRIDGE_SRC.KREDI_HAREKET values(2,'KRD-103','KREDI',2000);
commit;
begin dbms_stats.gather_schema_stats('DBRIDGE_SRC'); end;
/
create table DBRIDGE_TGT.MUSTERI as select * from DBRIDGE_SRC.MUSTERI where 1=0;
create table DBRIDGE_TGT.KREDI as select * from DBRIDGE_SRC.KREDI where 1=0;
create table DBRIDGE_TGT.ODEME_PLANI as select * from DBRIDGE_SRC.ODEME_PLANI where 1=0;
create table DBRIDGE_TGT.TAKSIT as select * from DBRIDGE_SRC.TAKSIT where 1=0;
create table DBRIDGE_TGT.TAKSIT_TAHSILAT as select * from DBRIDGE_SRC.TAKSIT_TAHSILAT where 1=0;
create table DBRIDGE_TGT.TEMINAT as select * from DBRIDGE_SRC.TEMINAT where 1=0;
create table DBRIDGE_TGT.KREDI_HAREKET as select * from DBRIDGE_SRC.KREDI_HAREKET where 1=0;
alter table DBRIDGE_TGT.MUSTERI add primary key(MUSTERI_ID);
alter table DBRIDGE_TGT.KREDI add primary key(KREDI_ID);
alter table DBRIDGE_TGT.KREDI add constraint FK_KREDI_MUSTERI foreign key(MUSTERI_ID) references DBRIDGE_TGT.MUSTERI(MUSTERI_ID);
alter table DBRIDGE_TGT.ODEME_PLANI add primary key(PLAN_ID);
alter table DBRIDGE_TGT.ODEME_PLANI add constraint FK_PLAN_KREDI foreign key(KREDI_ID) references DBRIDGE_TGT.KREDI(KREDI_ID);
alter table DBRIDGE_TGT.TAKSIT add primary key(PLAN_ID,TAKSIT_NO);
alter table DBRIDGE_TGT.TAKSIT add constraint FK_TAKSIT_PLAN foreign key(PLAN_ID) references DBRIDGE_TGT.ODEME_PLANI(PLAN_ID);
alter table DBRIDGE_TGT.TAKSIT_TAHSILAT add primary key(TAHSILAT_ID);
alter table DBRIDGE_TGT.TAKSIT_TAHSILAT add constraint FK_TAHSILAT_TAKSIT foreign key(PLAN_ID,TAKSIT_NO) references DBRIDGE_TGT.TAKSIT(PLAN_ID,TAKSIT_NO);
alter table DBRIDGE_TGT.TEMINAT add primary key(TEMINAT_ID);
alter table DBRIDGE_TGT.TEMINAT add constraint FK_TEMINAT_KREDI foreign key(KREDI_ID) references DBRIDGE_TGT.KREDI(KREDI_ID);
alter table DBRIDGE_TGT.KREDI_HAREKET add primary key(HAREKET_ID);
exit
"@
$result = $sql | & sqlplus.exe -s '/ as sysdba'
if($LASTEXITCODE -ne 0 -or ($result -join "`n") -match 'ORA-|SP2-') {
    $safe = ($result -join "`n").Replace($adminPassword,'[redacted]').Replace($sourcePassword,'[redacted]').Replace($targetPassword,'[redacted]')
    Write-Host $safe
    throw 'Test ortamı kurulumu tamamlanamadı. Şifreler DPAPI dosyasında korundu; mevcut PDB otomatik silinmedi.'
}
Write-Host 'DATABRIDGE_TEST hazır: DBRIDGE_SRC (7 tablo, sentetik veriler), DBRIDGE_TGT (7 boş tablo).'
