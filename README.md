# DataBridge

Oracle üretim verisinden test veri kümeleri hazırlamak için Windows masaüstü uygulaması.

Aktif teknoloji kararı: **Java 21 + Spring Boot + React/TypeScript + SWT/WebView2**. İptal edilmiş C# ve JavaFX prototipleri Git geçmişinde bulunabilir.

## Geliştirici için ilk kurulum

Desteklenen geliştirme ortamı **Windows x64**'tür. SWT ve Windows DPAPI kullanıldığı için masaüstü uygulamasını macOS, Linux veya WSL üzerinden çalıştırmak desteklenmez.

Önce şu araçları kurun ve yeni bir PowerShell penceresi açın:

- Git.
- **JDK 21 x64**: Yalnızca JRE yeterli değildir; `javac` ve `jpackage` bulunmalıdır. JDK'nın `bin` klasörünü PATH'e ekleyin.
- **Node.js 22.12+** ve beraberindeki npm.
- **Microsoft Edge WebView2 Runtime x64**: Masaüstü arayüzü için gereklidir.

Aşağıdaki komutlarla araçları kontrol edin. `java` ve `javac` çıktılarının ikisi de 21 olmalıdır; makinede birden fazla Java kuruluysa PATH sırasını kontrol edin.

```powershell
git --version
java -version
javac -version
jpackage --version
node --version
npm.cmd --version
```

### Klonlama, derleme ve ilk çalıştırma

```powershell
git clone https://github.com/hburaksavas/DataBridge.git
Set-Location DataBridge
.\build.ps1
```

İlk derlemede npm ve Maven bağımlılıklarının indirilmesi için internet erişimi gerekir. Maven'ı ayrıca kurmanız gerekmez; script Maven 3.9.9'u `tools/` altına indirip SHA512 kontrolünü yapar. Script `JAVA_HOME` değerini PATH'te bulunan `javac.exe` üzerinden ayarlar; ön yüzü derler, Java testlerini çalıştırır ve Windows uygulama paketini üretir.

PowerShell script çalıştırmayı execution policy nedeniyle engellerse, kurum politikanız izin veriyorsa yalnızca o PowerShell oturumu için aşağıdaki komutu çalıştırıp derlemeyi yeniden başlatın:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\build.ps1
```

Derleme tamamlandıktan sonra, aynı repo klasöründen uygulamayı açın:

```powershell
$applicationDirectory = (Get-Content -LiteralPath .\latest-build.txt -Raw).Trim()
Start-Process -FilePath (Join-Path $applicationDirectory 'DataBridge.exe')
```

Paket `dist/<tarih-saat>/DataBridge/` altında oluşur. `latest-build.txt` son başarılı paketin konumunu tutar. Kaynak kod değişikliklerinden sonra `.\build.ps1` çalıştırıp yeni paketi açın.

### Veritabanı bağlantısını hazırlama

Uygulamayı derlemek ve açmak için Oracle kurulumu gerekmez. Gerçek tablo keşfi ve veri aktarımı için ağ üzerinden erişebileceğiniz bir Oracle veritabanı gerekir. Oracle JDBC sürücüsü Maven tarafından indirilir; Oracle Client kurulumu zorunlu değildir.

1. Uygulamada kaynak bağlantıyı ekleyin: sunucu, port, servis adı, kullanıcı ve parola bilgilerini kendi ortamınızdan girin. TNS kullanıyorsanız kendi `tnsnames.ora` dosyanızı içe aktarın.
2. Kaynak hesabın aktarılacak tabloları okuyabildiğini doğrulayın ve bağlantı testini çalıştırın. Üretim kaynağını salt okunur olarak tanımlayın.
3. Aktarım yapacaksanız ayrı, üretim olmayan ve yazılabilir bir hedef bağlantı ekleyin. Hedefte uyumlu tablo, kolon, PK ve FK yapıları önceden bulunmalıdır; uygulama hedef şemayı oluşturmaz.
4. Tablo tarayıcısında kaynak şemayı girip **Load tables** düğmesini kullanın veya `OWNER.TABLE_NAME` ile tablo ekleyin. Yeni senaryolarda başlangıç kayıt sınırı **1**'dir.

Bağlantılar ve senaryolar ilk açılışta otomatik oluşturulan `%LOCALAPPDATA%\DataBridge\application.db` içinde saklanır; ayrıca SQLite sunucusu veya `.env` dosyası hazırlamanız gerekmez. Günlükler aynı dizindeki `logs/` altındadır. Kayıtlı parolalar Windows kullanıcısına bağlı DPAPI ile korunur; başka bir geliştiricinin yerel veritabanını kopyalamak yerine bağlantılarınızı kendiniz tanımlayın.

### Geliştirme sırasında doğrulama

Repo kökünden paket üretmeden ön yüz derlemesini ve Java testlerini çalıştırmak için:

```powershell
.\build.ps1 -NoPackage
```

Bu komut Java paketini ve bağımlılıklarını hazırlar; yeni EXE veya `latest-build.txt` üretmez. Hazırlanan JAR'ı JDK 21 ile doğrudan çalıştırabilirsiniz:

```powershell
java -jar .\app\target\package\databridge-desktop-0.6.0.jar
```

Yalnızca ön yüz derlemesini veya hata mesajı sözleşme testlerini çalıştırmak için:

```powershell
npm.cmd --prefix frontend run build
node --experimental-strip-types --test .\frontend\scripts\errors.test.mjs
```

Bu komutlar ilk kurulumdaki bağımlılıkların indirilmiş olmasını gerektirir. Vite geliştirme sunucusunda API proxy ve masaüstü oturum anahtarı akışı yapılandırılmamıştır; `npm run dev` tek başına tam uygulamayı başlatmaz. Tam uygulamayı EXE veya yukarıdaki JAR komutuyla açın. Standart testler için canlı Oracle gerekmez; canlı Oracle testleri ayrıca etkinleştirilmedikçe atlanır. Yerel Oracle kurulum scriptleri, örnek bağlantı dosyaları ve test raporları repoya dahil değildir.

## Çalıştırma

`latest-build.txt` son uygulama klasörünü gösterir. Bu klasördeki `DataBridge.exe` ile açın.

Java 21 çalışma ortamı pakete dahildir; kullanıcı makinesinde Java kurulumu gerekmez. EXE tek başına taşınmaz: `app` ve `runtime` klasörleriyle birlikte kopyalayın. Windows x64 ve Microsoft Edge WebView2 Runtime gerekir. Paket bir kurulum sihirbazı değil, taşınabilir uygulama klasörüdür.

## Derleme

Windows x64 üzerinde JDK 21 ve Node.js 22.12+ (veya uygun daha yeni sürüm) gerekir:

```powershell
.\build.ps1
```

Script gerektiğinde Maven 3.9.9 indirip SHA512 kontrolü yapar; `npm ci`, TypeScript/React üretim derlemesi ve Java testlerini çalıştırır; jpackage ile tarihli `dist` klasörüne EXE ve çalışma ortamını üretir. Yalnızca doğrulama için `./build.ps1 -NoPackage` kullanılabilir. İlk derlemede internet erişimi gerekir. Node/npm ve Maven son kullanıcı paketinde gerekmez.

## v0.6 çalışan özellikler

- Bağlantı ekleme, düzenleme, silme, klasörleme ve arama.
- Görünen ad, kullanıcı, ortam, gösterge rengi, NORMAL/SYSDBA/SYSOPER rolü.
- Doğrudan servis bağlantısı, TNS alias ve tam Oracle bağlantı tanımı.
- `tnsnames.ora` içe aktarma: önizleme, satır seçimi, klasör/ortam seçimi, çoklu alias, çoklu RAC adresleri, SERVICE_NAME ve SID tanımlarının korunması.
- TNS_ADMIN ve ORACLE_HOME konumlarının gösterilmesi. IFILE dosyaları otomatik takip edilmez; kullanıcı ayrıca içe aktarır.
- Gerçek Oracle JDBC bağlantı testi; zaman aşımı ve kullanıcıya yönelik Oracle hata mesajları.
- Oracle ALL_TABLES / ALL_TAB_COLUMNS üzerinden tablo ve kolon okuma; doğrudan gelen/giden FK keşfi, bileşik anahtar kolonlarının sıralı eşleştirilmesi.
- İlişki kapsamını seçme; senaryoyu SQLite'a kaydetme ve JSON ile paylaşma/içe aktarma. Paylaşımda yerel bağlantı kimliği çıkarılır.
- İsteğe bağlı parola saklama: Windows DPAPI. Parola API cevaplarına veya paylaşım dosyalarına eklenmez.

## PL/SQL Developer uyumluluğu

**Henüz tüm PL/SQL Developer export biçimleriyle doğrulanmış bir importer değildir.** Resmi belgelerde bağlantı tanımlarının dışa aktarımı anlatılıyor, ancak dosya şeması yayınlanmıyor.

Şu an okunabilir INI/XML kayıtları için metadata adaptörü bulunur. Name/DisplayName, Username/LogonUsername, Database/LogonDatabase, Folder ve ConnectAs/Role alanları tanınır. INI'de ayrı bağlantı bölümleri ve `Name0`, `Database0` gibi indeksli alanlar desteklenir. XML'de connection/item/logon elementleri ve okunabilir alanlar desteklenir.

Şifreli, ikili veya tanınmayan dosyalar açıklayıcı hata verir. Parolalar ve initialization scriptleri alınmaz. TNS aliasları önceden içe aktarılan TNS tanımlarıyla eşleştirilir; eşleşmeyen bağlantılar kaydedilmeden önce çözümlenmelidir. Kesin sürüm uyumluluğu için anonimleştirilmiş gerçek export örneği gerekir.

## Veri ve güvenlik

Veriler `%LOCALAPPDATA%\DataBridge\application.db`, dönen günlükler `%LOCALAPPDATA%\DataBridge\logs` altında tutulur. DPAPI parolaları mevcut Windows kullanıcısına bağlıdır; farklı kullanıcı/bilgisayara DB kopyalamak parolaları taşımaya yetmez. Parola saklama kutusunu kaldırıp kaydetmek kayıtlı parolayı kaldırır.

Spring Boot yalnızca `127.0.0.1` üzerinde dinamik port açar. API çağrıları her açılışta üretilen oturum anahtarı gerektirir; yabancı origin reddedilir. Arayüz harici CDN veya servis kullanmaz. Üretim bağlantıları salt okunur olarak kaydedilir. Bu sürümün Oracle işlemleri salt okunur transaction içinde yürütülür; kullanıcı SQL'i veya import edilmiş başlangıç betikleri çalıştırılmaz.

## Henüz uygulanmayanlar

FK olmayan ilişkiler için otomatik tahmin, gerçek maskeleme, Excel aktarımı, zamanlama, checkpoint ve kesilen aktarımı kaldığı yerden devam ettirme bu sürümde yoktur. Arayüz başarı taklidi yapmaz; mevcut işlevlerin sınırını açıkça belirtir. Yerel Oracle benchmarkında bir milyon sentetik kayıt, 256 MB JVM heap sınırıyla yaklaşık 41 saniyede aktarıldı. Bu sonuç beş basit kolonlu yerel test içindir; uzak ağ, LOB veya farklı şemalar için performans garantisi değildir.

Yerel Oracle AI Database 26ai Free 23.26.3.0.0 üzerinde gerçek bağlantı ve metadata testleri yapıldı. Ayrı DATABRIDGE_TEST PDB içindeki DBRIDGE_SRC ve DBRIDGE_TGT şemaları kullanıldı. Canlı test raporları yerel olarak üretilir ve Git takibinde tutulmaz. Otomatik testler TNS ve uyumluluk parserlarını, API yetkilendirmesini, SQLite kalıcılığını, toplu import doğrulamasını, Windows parola şifrelemesini ve senaryo kaydını kapsar.

## Kaynak düzeni

- `frontend/`: React, TypeScript, Vite ve CSS.
- `app/`: Spring Boot REST API, Oracle JDBC, SQLite, importerlar ve SWT launcher.
- `build.ps1`: Doğrulama ve Windows paketleme.

Masaüstü pencere SWT `Browser(SWT.EDGE)` kullanır; arayüz derlenip Spring Boot ile aynı origin üzerinden sunulur. Oracle işlemleri servis katmanındadır; React doğrudan Oracle'a erişmez.

## Referanslar

- [SWT WebView2](https://github.com/eclipse-platform/eclipse.platform.swt/blob/master/bundles/org.eclipse.swt/Readme.WebView2.md)
- [PL/SQL Developer Connection List](https://www.allroundautomations.com/products/pl-sql-developer/features/connection-list/)
- [Java 21 jpackage](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)

## Gerçek Oracle aktarım akışı

1. Senaryolar ekranında tabloyu keşfedin. Alt ilişki satırındaki **Alt ilişkiler** düğmesiyle ödeme planı → taksit → tahsilat gibi daha derin yolları keşfedip dahil edin. Üst kayda gidilmesi, o üst kaydın diğer alt kayıtlarını otomatik olarak seçmez.
2. Senaryoyu kaydedin; **Veri aktarımı** ekranından senaryo, kaynak ve yazılabilir test hedefini seçin.
3. Başlangıç koşulunu ve kayıt sınırını belirleyip önizleme oluşturun. Başlangıç kayıtları primary key sırasıyla seçilir. Filtre grammarı kolon karşılaştırmaları, AND/OR/NOT, IN, LIKE, IS NULL ve DATE 'YYYY-MM-DD' destekler. Literal değerler JDBC bind parametresidir; serbest SQL, fonksiyon veya alt sorgu kabul edilmez.
4. Tablo bazında gerçek kapsamı ve yazma sırasını inceleyip aktarımı başlatın. Önizleme kaynakta READ ONLY transaction snapshot'ını 5 dakika tutar. Önizlenen snapshot çalıştırmada tekrar kullanılır; süresi dolan veya kullanılmış token yeniden çalıştırılamaz. Aynı anda üç önizleme ve bir aktarım desteklenir.
5. İlerlemeyi izleyin veya iptal edin. Satırlar tablo genişliğine göre en fazla 500'lük paketlerle stream edilir. Bütün hedef yazmaları tek transaction içindedir. Varsayılan olarak PK çakışması hata verir ve tüm aktarım rollback olur. Yalnızca açık KEEP/SKIP politikasında mevcut PK atlanır. İptal/hata rollback yapar. Commit yanıtı kaybolursa başarı/rollback varsayılmadan UNKNOWN sonucu gösterilir.

Hedef üretim olarak etiketlenmiş veya salt okunur olamaz; yönetici rolü kullanılmaz. Aynı gerçek veritabanı ve şema kaynak/hedef olarak seçilemez. Önizlemede kolon tür/uzunluk/precision/scale, PK ve FK yapıları karşılaştırılır. Çalıştırmadan önce tablolar ROW EXCLUSIVE NOWAIT ile DDL değişikliklerine karşı kilitlenip yapı yeniden doğrulanır; diğer kullanıcıların DML işlemleri tümüyle engellenmez. Çakışmalar Oracle tarafından hata verilirse transaction geri alınır.

İlk aktarım sürümü tek kaynak şeması → hedef bağlantının kendi şemasını destekler. İlişki seçim grafiği ve hedef yazma sırası döngüsüz olmalı; tüm tablolarda etkin PK ve eşleşen, doğrulanmış FK yapıları gerekir. LOB, sanal kolon, etkin hedef triggerları ve şemalar arası FK henüz desteklenmez; sessizce atlanmaz, önizleme durur. Desteklenen türler NUMBER/FLOAT, CHAR/VARCHAR2/NCHAR/NVARCHAR2, RAW, DATE, TIMESTAMP, BINARY_FLOAT ve BINARY_DOUBLE'dır. Kaynak kök sınırı 1 milyon, toplam kapsam 5 milyon, ilişki sınırı 24'tür.

Mevcut kayıtlar güncellenmez. PK eşitliği kendiliğinden aynı kayıt sayılmaz; açık SKIP veya iş anahtarı eşleştirmesi gerekir. Dahil edilmeyen üst kayıtlar hedefte bulunmalıdır; hedef FK'leri eksik kayıtları reddeder. Maskeleme bu sürümde uygulanmaz; prod verisini gerçek bir test ortamına aktarmadan önce veri kullanım kurallarınızı gözetin.

Aktarım geçmişi yerel SQLite'ta saklanır; satır içerikleri veya parolalar geçmişe yazılmaz. Uygulama kapanıp yeniden açıldıktan sonra çalışan görünen eski kayıtlar doğrulanmamış sonuç olarak gösterilir; otomatik devam etme yoktur.

## v0.5 — PK kararları

Veri Aktarımı ekranında tablo seçilip sağdaki **Key strategy** sekmesinden her tabloya yöntem seçilir. Hedef bağlantı seçilip metadata önerileri istenebilir; hedef bağlantının kimliği/parolası senaryo dosyasına eklenmez. Yeni senaryolar v3 JSON biçimindedir; v1/v2 senaryolar açılır ve aksi açıkça seçilmedikçe yeni güvenli varsayılan KEEP/ERROR kullanılır.

| Yöntem | Davranış |
|---|---|
| KEEP / ERROR | Kaynak PK korunur; PK/FK'den eşlenecek parçalar dönüştürülür. Çakışmada tüm aktarım geri alınır. |
| KEEP / SKIP | Açık uzman seçimiyle aynı PK'deki mevcut kayıt kullanılır; içerik güncellenmez. |
| SEQUENCE | Hedefin kendi şemasındaki seçilen sequence'den yeni sayısal PK parçası üretilir. |
| IDENTITY | Hedef identity kolonu INSERT'e verilmez; gerçekten üretilen değer JDBC generated keys ile alınır. GENERATED ALWAYS desteklenir. |
| MAX | MAX + artış; boş tablo/grup başlangıcı, pozitif tam sayı artışı ve diğer PK parçalarıyla gruplama. |
| BUSINESS | Hedefteki etkin PK/UNIQUE kolonlarıyla mevcut kayıt bulunur; hedef anahtarları kullanılır. Bulunamazsa veya NULL/çoklu eşleşme varsa hata. |

İsim önerileri SEQ_CREDIT, CREDIT_SEQ, SQ_CREDIT gibi eşleşmeleri içerir. Kolon DEFAULT, identity ve trigger kanıtları ayrıca gösterilir. Öneriler otomatik uygulanmaz ve NEXTVAL çağrılmaz. Etkin hedef triggerlarıyla aktarım halen desteklenmez; trigger kanıtı yalnızca danışma amaçlıdır.

Eski/yeni anahtar tuple'ları bileşik FK'ler dahil seçili alt tablolara uygulanır. Başka bir FK'den gelen PK parçası ayrıca üretilemez; üst tablodan eşlenmelidir. Üretilen kolon bir sayısal PK parçası olmalıdır. Mevcut başka UNIQUE alanlar korunur; bunlar çakışırsa da rollback olur. SEQUENCE/IDENTITY/MAX yeniden çalıştırıldığında yeni kayıtlar üretir; otomatik idempotence sağlamaz. Tekrarlı mantıksal kayıtları birleştirmek için uygun BUSINESS politikası seçilmelidir.

MAX+1 için ayrıca tahsis veya diğer yazmaları engelleyen kilit alınmaz. Genel ROW EXCLUSIVE DDL koruması diğer DML işlemlerine izin verir. Gerçek eşzamanlı PK yarışı testi, başka işlem kaydı eklerken bizim önceki insert'ümüzün geri alındığını doğrular. Otomatik tekrar yoktur.

Sequence/identity tüketilen değerleri rollback veya iptal ile geri alınmaz. Sequence değerleri küçük paketlerle önceden alınabileceği için boşluklar oluşabilir.

Anahtar eşlemeleri satır içerikleri yerine yalnızca gerekli anahtar tuple'larını tutar. Büyük eşlemeler bellekte sınırsız birikmez: geçici SQLite dosyasında HMAC indeksler ve AES-GCM şifreli değerler kullanılır. Geçici anahtar yalnızca işlem belleğindedir; dosya bitiş/hata/iptalde temizlenir. Süreç aniden kapanırsa diskte şifreli kalıntı kalabilir; otomatik checkpoint/yeniden başlatma sağlamaz.

Yeni anahtar yolunda kayıtlar generated-key ve eşleme doğruluğu için satır bazında yazılır; yalnızca KEEP politikaları bulunan senaryolar toplu yazım yolunu kullanır. Bir milyon satır benchmarkı KEEP/SKIP yolu içindir; yeniden anahtarlama hızının ölçümü değildir.



## v0.6 — Diagram ile senaryo oluşturma

Ana ekran Veri Aktarımıdır; menüde yalnızca Veri Aktarımı ve Bağlantılar bulunur. Sol tablo listesinden veya adıyla tablo ekleyin, başlangıç tablosunu seçin ve FK keşfet düğmesini kullanın. Başlıkları sürükleyerek taşıyın; otomatik yerleşim, sığdırma ve yakınlaştırma kullanılabilir.

FK olmayan bağlantı için üst tablonun sağ noktasını ve bağlı tablonun sol noktasını seçin veya tablo özelliklerinden Manuel ilişki kur düğmesine basın. Bir veya birden fazla kolon çifti tanımlanır; üst kolonlar kaynakta etkin PK/UNIQUE olmalı, kolon türleri eşleşmelidir. Ek tablo koşulu verilebilir. Bu tanım veritabanına FK eklemez; veri seçimine, yazma sırasına ve eski→yeni anahtar eşlemesine uygulanır. Aynı şema içindeki döngüsüz eşitlik ilişkileri desteklenir; bağımsız tablolar taslakta tutulabilir fakat çalıştırmadan önce başlangıca bağlanmalıdır.

Senaryo seçme, kaydetme, v3 JSON içe/dışa aktarma, önizleme ve başlatma aynı çalışma alanındadır. Diagram konumları ve tablo koşulları senaryoya kaydedilir. Start düğmesi önizlemeyi otomatik hazırlar ve modal pencerede açar. Önizleme modalındaki Start transfer ile aktarım onaylanır; İşlemler menüsünden çalıştırma ve geçmiş açılabilir; çalıştırma penceresi kapatıldığında iş arka planda devam eder. PK kararları seçili tablonun sağ panelindedir.


Uygulama ikonu `branding/databridge.ico` ile Windows EXE’ye gömülür; SWT pencere ikonları `app/src/main/resources/icons`, arayüz/favikon `frontend/public/databridge.svg` dosyalarındadır.

Arayüz İngilizcedir. Data Transfer ve Connections üst navigasyonda yer alır; sol menü kaldırılmıştır. Yumuşak görsel tema `frontend/src/soft.css` üzerinden uygulanır. Bağlantı ve senaryo adları ile Oracle nesne isimleri kullanıcı verisi olarak korunur.

Aktarım araç çubuğu sadeleştirilmiştir: senaryo seçimi ve esnek ad alanı solda, içe/dışa aktarma Actions yanında sağda yer alır. Kaydet ikon düğmesidir. Üst/alt teknik durum alanları kaldırılmıştır. Load tables hedef bağlantısından sonra gelir. Senaryo açıldığında veya tablo seçildiğinde kolon bilgileri otomatik yüklenir. Kayıtlı parola yoksa yalnızca ihtiyaç anında oturum parolası istenir; kalıcı kaydedilmez.

Connections ekranı daraltılmış iki kolonlu bir yönetim alanıdır: temel bağlantı ve giriş alanları kompakt formda; klasör, rol ve renk Advanced settings bölümündedir. Aktarım ekranında Start bağlantı satırının sağındadır; Load tables senaryo kontrol grubuna hizalanır.

Senaryo seçimi ve adı tek bir aramalı combobox alanındadır. Yazarken kaydedilmiş senaryolar listelenir; ok tuşları ve Enter ile seçim yapılabilir. Yeni ad Save ile kaydedilir. Aynı ada sahip ikinci senaryo oluşturulamaz (Unicode normalizasyonu ve büyük/küçük harf farkları dahil); mevcut senaryo kendi kimliğiyle güncellenebilir.

DataBridge ikonu indigo zemin üzerinde beyaz köprü kemeri ve bağlantılı diyagram düğümleridir; mercan üst düğüm ve turkuaz bağlantı hattı kullanılır. SVG, SWT PNG boyutları ve çok boyutlu Windows ICO `python branding/generate_icon.py` ile üretilir (Pillow gerekir). Script ayrıca Git takibinde tutulmayan `branding/icon-preview.png` önizlemesini üretir.
### Oracle tablo ve şema seçimi

Veri transferinde **Schema filter (optional)** boş bırakıldığında kullanıcının erişebildiği uygulama tabloları tüm şemalardan listelenir. Grant ile erişilen tablolar da `OWNER.TABLE_NAME` adıyla görünür. Filtre yalnızca listeyi daraltır; senaryodaki tablo sahibini değiştirmez. İlk tablo seçildiğinde gerçek sahibi senaryoya kaydedilir. Elle eklemede `OWNER.TABLE_NAME` kullanılabilir; yalnızca tablo adı girilirse filtre veya senaryonun sahibi kullanılır.

Oracle tarafından yönetilen şemalar (`ALL_USERS.ORACLE_MAINTAINED = 'Y'`), nested/secondary depolama tabloları, IOT overflow/mapping tabloları ve recycle bin tabloları listelenmez. Aynı denetim elle eklemede ve transfer önizlemesinde uygulanır. Keşif sorgusu Oracle 12.1.0.2 ve üzerindeki `ORACLE_MAINTAINED` sütununu kullanır.

Mevcut transfer modeli senaryo başına tek kaynak tablo sahibini destekler. Başka şemadan erişilebilir bir tablo kök olarak seçilebilir; aynı senaryoda farklı sahiplerin tabloları birleştirilemez. Hedef tablo sahibi hedef bağlantısının kullanıcısıdır.


### Diagram üzerinden ilişki tanımlama

Şema filtresi ve **Load tables**, **Source tables** panelinin üst araç çubuğundadır. Bir tablonun sağ bağlantı noktasından diğer tabloya ok sürükleyip bırakarak kolon eşleştirme penceresini açabilirsiniz. Ok sürükleme sırasında canlı görünür; hedef tablo vurgulanır. Başlangıç tablosu parent, bırakılan tablo child olarak seçilir. Boş alana bırakmak, Escape veya Cancel işlemi iptal eder. Klavyeyle çıkış noktasını etkinleştirip hedef giriş noktasını seçmek de desteklenir. İlişki senaryoda saklanır; veritabanında constraint oluşturulmaz.

### Transferi başlatma

**Start**, doğrulamayı ve önizleme hazırlığını otomatik başlatıp transfer önizleme penceresini açar. Ayrı Preview düğmesi yoktur. Satır sayıları, ekleme sırası ve anahtar stratejileri incelendikten sonra penceredeki **Start transfer** ile transfer onaylanır. Pencereyi kapatmak veri aktarımını başlatmaz. Hazırlama hataları aynı pencerede gösterilir ve **Try again** ile yeniden denenebilir.

### Vektör ikon ve yönlendiren hata mesajları

İkon düz indigo zemin, tutarlı çizgiler, köprü/tablolar ve turkuaz aktarım oku ile vektör olarak yenilendi. Kaynak `branding/databridge.svg`; `python branding/generate_icon.py` SVG, web kopyası, SWT PNG boyutları ve çok boyutlu Windows ICO dosyasını birlikte üretir.

Bağlantı, keşif, önizleme ve aktarım hataları açıklama, **What to do** adımları ve referans kodu ile gösterilir. Kimlik doğrulama, servis/listener, ağ, grant, PK/UNIQUE/FK, kolon uyumsuzluğu, kilit, kota/alan ve snapshot hataları ayrı yönlendirme içerir. JDBC hata metni, SQL ve parolalar istemci yanıtına taşınmaz. Arka planda başarısız olan aktarımın hata ayrıntıları geçmişe kaydedilir; işlem sonucu belirsizse hedef veriyi doğrulamadan tekrar aktarım başlatılmaması belirtilir.

Hata eşleme testleri Java test paketindedir. Ön yüz sözleşme testleri: `cd frontend; node --experimental-strip-types --test scripts/errors.test.mjs`.

## Git kapsamı

Kaynak kod, testler, derleme scripti, package-lock.json ve uygulama ikonları depoda tutulur. Derleme çıktıları, bağımlılıklar, yerel test raporları, PLAN.md, ikon önizlemesi, kimlik bilgileri ve yerel veritabanları `.gitignore` ile dışlanır.

Oracle kurulum ve canlı test scriptleri (`scripts/`) yerel tutulur; depoya dahil değildir.
