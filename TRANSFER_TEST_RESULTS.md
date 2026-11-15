# Gerçek Oracle aktarım testi — v0.4

Tarih: 15.11.2026, Europe/Istanbul. Ortam: Oracle AI Database 26ai Free 23.26.3.0.0, Java 21, Windows x64.

- Bir milyon satır gerçekten aktarıldı ve commit edildi.
- Önizleme ve aktarım toplamı: **40,63 saniye**.
- Test JVM maksimum heap: **256 MB**.
- Veri: NUMBER primary key, VARCHAR2(12), VARCHAR2(64), NUMBER(18,2), DATE. Ondalık değer ve tarih-saat hedefte doğrulandı.
- Bu yerel ve basit kolonlu bir benchmarktır; uzak ağ ve karmaşık üretim şemaları için süre garantisi değildir.

## İlişkili kredi aktarımı

DBRIDGE_SRC → DBRIDGE_TGT gerçek uygulama API'si kullanıldı.

| Tablo | Hedef kayıt |
|---|---:|
| MUSTERI | 2 |
| KREDI | 2 |
| ODEME_PLANI | 2 |
| TAKSIT | 24 |
| TAKSIT_TAHSILAT | 1 |
| TEMINAT | 2 |
| KREDI_HAREKET | 0 |
| **Toplam** | **33** |

Kapalı kredi alınmadı. FK'siz hareketler otomatik dahil edilmedi. Yeniden çalıştırmada 0 ekleme, 33 atlama doğrulandı.

## Doğrulanan hata ve iptal davranışları

- Önizleme sonrasında kaynağa eklenen kayıt, tutarlı snapshot sayesinde aktarıma dahil olmadı.
- Sonraki tabloda CHECK hatası olduğunda önceki tablo yazmaları geri alındı.
- İlk paket yazıldıktan sonra iptal edildi; hedefte 0 kalıcı kayıt doğrulandı.
- Aynı gerçek şema ve PROD etiketli hedef reddedildi.
- Uydurulmuş FK ve daha dar sayısal scale reddedildi.
- Önizleme sonrasında FK devre dışı bırakıldığında, çalıştırma öncesi doğrulama aktarımı reddetti.

Testler bu çalışma için oluşturulan DATABRIDGE_TEST, DBS_* ve DBX_* şemalarında çalıştı. Mevcut BANKING_DB verilerine dokunulmadı. Parolalar rapora eklenmedi.

Makine okunur raporlar: oracle-transfer-benchmark.json ve oracle-live-test-results.json.
