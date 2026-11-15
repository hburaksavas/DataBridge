# Diagram doğrulaması — v0.6

2026-11-15, yerel Oracle AI 26ai / DATABRIDGE_TEST.

- Normal Maven verify: 24 test geçti; 21 isteğe bağlı canlı test atlandı.
- `scripts/test-transfer-live.ps1 -GraphOnly`: dört canlı test geçti (DBS_37639C5E / DBX_37639C5E izole şemaları).
- FK olmayan sayısal bağlantıda kök koşulu + alt tablo koşulu: yalnızca iki kayıt seçildi, parent sequence ID’si child kolonuna eşlendi ve commit edildi.
- KEEP/SKIP ile mevcut hedef parent kaydına bağlanırken UNIQUE referansı OLD → EXISTING olarak yeniden eşlendi.
- Tür uyuşmazlığı, PK/UNIQUE olmayan parent kolonu ve kapsam dışı tablo koşulu reddedildi.
- `scripts/test-oracle-live.ps1`: 21 API/canlı kontrol ve iki JDBC testi geçti. Mevcut 33 kayıt aktarımı ve tekrar çalıştırması doğrulandı; yedi tablo/altı ilişkili diagram yerleşimi kaydedildi, manuel KREDI_NO → REFERANS_NO bağlantısı ve ek koşul ile 35 kayıt önizlendi.
- SWT/WebView2 paketinde iki menü, ana çalışma alanı, örnek diagram yükleme ve manuel ilişki tanımlama penceresi görsel olarak doğrulandı.

Manuel ilişkiler aynı şemada, döngüsüz eşitlik eşleştirmeleridir; parent kaynak kolonları etkin PK/UNIQUE olmalıdır. Oracle constraint oluşturulmaz. Bir milyon satır benchmarkı önceki KEEP/SKIP yolu içindir; yeni diagram veya manuel eşleme için ayrıca milyon satır performans iddiası yoktur.
