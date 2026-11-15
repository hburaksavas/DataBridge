# DataBridge geliştirme planı

## Tamamlanan temel

- React + SWT/WebView2 + Java 21/Spring Boot masaüstü uygulaması.
- TNS ve okunabilir bağlantı tanımlarını içe aktarma, Windows DPAPI parola koruması.
- FK keşfi, çok seviyeli senaryo, snapshot önizlemesi, atomik aktarım ve iptal.
- Gerçek Oracle 26ai ve bir milyon satır benchmarkı.

## PK ve kayıt kimliği kararları — v0.5 tamamlandı

- Anahtar politikası tablo/kolon bazında senaryoya kaydedilir ve paylaşılır.
- Varsayılan: kaynak anahtarını koru, çakışmada hata ve tüm aktarım için rollback.
- Mevcut PK'yi atlama yalnızca açık kullanıcı seçimiyle kullanılır; PK eşitliği kayıt kimliği olarak varsayılmaz.
- Hedef sequence ile yeni ID. İsimden SEQ_CREDIT/CREDIT_SEQ/SQ_CREDIT gibi adaylar önerilir; identity/default/trigger kanıtları ayrıca gösterilir. Öneride NEXTVAL çağrılmaz.
- Hedef identity ile yeni ID; gerçekten üretilen değer alınır.
- MAX+1: hedefteki maksimum değerden üretim, boş tablo başlangıcı ve artış seçimi. Grup bazında MAX+1 de desteklenir. Ek anahtar tahsis kilidi yok; yarışta unique/PK hatası bütün aktarımı geri alır. Otomatik tekrar yok.
- İş anahtarıyla mevcut hedef kaydı bul ve hedef PK'yi kullan. Bulunamazsa hata.
- Eski→yeni anahtarlar, bileşik FK'ler dahil bütün seçili ilişkili kayıtlara uygulanır.
- Eşlemeler büyük veri için bellekte sınırsız tutulmaz; geçici disk verisi şifrelenir.
- Sequence/identity değerleri rollback ile geri alınmaz; UI ve önizlemede belirtilir.
- Eski senaryolar açılır; yeni politikalar v2 senaryo biçiminde paylaşılır.

## Sonraki aşamalar

- Maskeleme ve tutarlı değer dönüşümü.
- FK olmayan ilişkiler için otomatik öneriler.
- Excel içe/dışa aktarım, zamanlama, checkpoint ve devam etme.
- Gerçek PL/SQL Developer export örneğiyle sürüm uyumluluğunu kesinleştirme.


## Öncelikli diagram tasarımcısı — v0.6 tamamlandı

- Ana ekran Veri Aktarımı; yalnızca Veri Aktarımı ve Bağlantılar menüleri.
- Kompakt tablo tarayıcı, sürüklenebilir tablo düğümleri, zoom/sığdırma/otomatik yerleşim.
- FK keşfi ve keşfedilmeyen tabloyu adıyla ekleme.
- Bağlantı noktalarıyla manuel PK/UNIQUE kolon eşleştirmesi; çok kolonlu ilişkiler.
- Tablo bazlı koşullar ve sağ panelde PK politikası.
- Manuel ilişkilerin gerçek seçime, yazma sırasına ve anahtar eşlemesine uygulanması.
- Senaryo seç/kaydet/paylaş/önizle/başlat aynı çalışma alanında; v3 diagram konumları ve filtreler.
- Dört izole gerçek Oracle diagram testi ve örnek yedi tablo/35 kayıt kapsamı.
