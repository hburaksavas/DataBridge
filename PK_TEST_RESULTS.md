# PK kararları — v0.5 test sonuçları

Yerel Oracle AI Database 26ai Free üzerinde, yalnızca bu çalışma için oluşturulan izole DBS_* / DBX_* şemaları kullanıldı.

| Test | Doğrulama |
|---|---|
| Sequence önerileri | SEQ_KREDI isim adayı, DEFAULT ve identity metadata kanıtları. Öneride sequence tüketilmedi. |
| Sequence ile yeni PK | Müşteri → kredi → ödeme planı → taksit → tahsilat zincirinde, bileşik FK dahil 33 kayıt doğru anahtarlara bağlandı. |
| MAX ve grup MAX | Hedef maksimum değerleri kullanıldı. PLAN_ID eşlendikten sonra her plan için TAKSIT_NO ayrı üretildi. |
| İş anahtarı | TCKN ile mevcut 2 hedef müşteri bulundu, 31 kayıt eklendi; krediler hedef müşteri ID'lerini kullandı. |
| Identity | GENERATED ALWAYS değerleri Oracle'dan alındı ve alt kayıtlara uygulandı. |
| Varsayılan çakışma | İlgisiz mevcut PK kaydı atlanmadı; hata verilip rollback yapıldı. |
| Gerçek eşzamanlı MAX yarışı | Başka bağlantı ID=2 ekledi. Bizim ikinci insert çakıştı; önceki ID=1 geri alındı, diğer bağlantının kaydı kaldı. |

Üç ek birim testi varsayılan ERROR politikasını, geçersiz üretim ayarlarını ve şifreli geçici eşlemede büyük ondalık/bileşik anahtarların korunmasını doğruladı.

Uygulamanın HTTP API'sinde v2 senaryo kaydı ve gerçek hedef metadata'dan sequence önerisi de doğrulandı. `oracle-key-policy-tests.json` ve `oracle-live-test-results.json` makine okunur raporlardır.

Bir milyon satır regresyonu KEEP/SKIP toplu yazım yolunda 256 MB JVM heap sınırıyla geçti. Bu ölçüm yeni PK üreten, satır bazında eşleme yapan yolun hız ölçümü değildir.

MAX+1 için ek anahtar tahsis kilidi veya otomatik tekrar yoktur. Sequence/identity değerleri rollback ile geri alınmaz. Satır içerikleri ve parolalar bu rapora dahil edilmemiştir.
