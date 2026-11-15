using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace DataTool.Prototype;

internal sealed class PrototypeUi
{
    readonly Window window;
    readonly StackPanel body = new(), navigation = new();
    readonly TextBlock breadcrumb = new(), status = new();
    readonly string[] pages = ["Genel bakış", "Senaryolar", "İlişki kataloğu", "Bağlantılar", "Görevler ve geçmiş"];
    readonly string[] icons = ["▦", "◇", "⑂", "▤", "◷"];
    int step;
    string name = "Kredi ve bağlı verileri", root = "BANK.KREDI", condition = "DURUM = 'AKTIF'", limit = "100";
    bool movements, scheduled;
    readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(180) };
    ProgressBar? progress;
    TextBlock? progressLabel;
    public static void Build(Window window) => _ = new PrototypeUi(window);
    static SolidColorBrush Ink(string hex) => new((Color)ColorConverter.ConvertFromString(hex));
    PrototypeUi(Window host)
    {
        window = host;
        window.Title = "DataBridge — Oracle test verisi · Prototip";
        window.Width = 1380; window.Height = 900; window.MinWidth = 1100; window.MinHeight = 720;
        window.WindowStartupLocation = WindowStartupLocation.CenterScreen;
        window.Background = Ink("#F3F5F9"); window.FontFamily = new FontFamily("Segoe UI");
        window.Foreground = Ink("#19253B");
        var shell = new Grid(); shell.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(224) }); shell.ColumnDefinitions.Add(new ColumnDefinition());
        var sidebar = new DockPanel { Margin = new Thickness(18, 28, 18, 22) };
        var brand = Stack(Text("◈  DataBridge", 25, "#FFFFFF", true), Text("TEST VERİSİ ÇALIŞMA ALANI", 10, "#8FA6C8"), new Border { Height = 25 });
        DockPanel.SetDock(brand, Dock.Top); sidebar.Children.Add(brand);
        var foot = Stack(Text("●  Ekran prototipi", 13, "#8EE1CA", true), Text("Tüm veriler örnektir.\nOracle bağlantısı kurulmaz.", 11, "#B8C5D8"), Text("WORKSPACE / LOCAL     v0.1", 10, "#8297B5"));
        DockPanel.SetDock(foot, Dock.Bottom); sidebar.Children.Add(foot); sidebar.Children.Add(navigation);
        shell.Children.Add(new Border { Background = Ink("#14243D"), Child = sidebar });
        var main = new Grid(); Grid.SetColumn(main, 1); main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(72) }); main.RowDefinitions.Add(new RowDefinition()); main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(36) });
        var header = new Grid(); breadcrumb.VerticalAlignment = VerticalAlignment.Center; breadcrumb.Foreground = Ink("#63738B"); header.Children.Add(breadcrumb);
        var badge = Text("PROD → TEST     /     DB", 11, "#52756A", true); badge.HorizontalAlignment = HorizontalAlignment.Right; badge.VerticalAlignment = VerticalAlignment.Center; header.Children.Add(badge);
        main.Children.Add(new Border { Background = Brushes.White, Padding = new Thickness(30, 0, 30, 0), Child = header, BorderBrush = Ink("#E2E7EF"), BorderThickness = new Thickness(0, 0, 0, 1) });
        body.Margin = new Thickness(30, 25, 30, 25); var scroll = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; Grid.SetRow(scroll, 1); main.Children.Add(scroll);
        status.Text = "Hazır · Örnek verilerle etkileşimli arayüz"; status.Foreground = Ink("#718099"); status.FontSize = 11;
        var bottom = new Border { Background = Brushes.White, Padding = new Thickness(30, 8, 30, 0), Child = status }; Grid.SetRow(bottom, 2); main.Children.Add(bottom); shell.Children.Add(main); window.Content = shell;
        timer.Tick += (_, _) => { if (progress is null) return; progress.Value = Math.Min(100, progress.Value + 4); progressLabel!.Text = progress.Value < 100 ? $"Örnek aktarım: %{progress.Value:0} · BANK.TAKSIT" : "Simülasyon tamamlandı · Gerçek veritabanına yazılmadı."; if (progress.Value == 100) timer.Stop(); };
        window.Closed += (_, _) => timer.Stop();
        Navigate(0);
    }
    TextBlock Text(string value, double size = 13, string color = "#61718A", bool bold = false) => new() { Text = value, FontSize = size, Foreground = Ink(color), FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 9) };
    StackPanel Stack(params UIElement[] items) { var p = new StackPanel(); foreach (var item in items) p.Children.Add(item); return p; }
    StackPanel Row(params UIElement[] items) { var p = Stack(items); p.Orientation = Orientation.Horizontal; return p; }
    Button Button(string label, Action action, bool primary = false)
    {
        var b = new Button { Content = label, Padding = new Thickness(15, 10, 15, 10), Margin = new Thickness(0, 0, 9, 6), Background = Ink(primary ? "#3467DC" : "#FFFFFF"), Foreground = Ink(primary ? "#FFFFFF" : "#273952"), BorderBrush = Ink(primary ? "#3467DC" : "#DCE2EB"), Cursor = System.Windows.Input.Cursors.Hand, FontSize = 13 };
        var border = new FrameworkElementFactory(typeof(Border)); border.SetValue(Border.CornerRadiusProperty, new CornerRadius(6)); border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty)); border.SetValue(Border.BorderBrushProperty, new TemplateBindingExtension(Control.BorderBrushProperty)); border.SetValue(Border.BorderThicknessProperty, new Thickness(1)); border.SetValue(Border.PaddingProperty, new TemplateBindingExtension(Control.PaddingProperty));
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter)); presenter.SetValue(ContentPresenter.HorizontalAlignmentProperty, HorizontalAlignment.Center); border.AppendChild(presenter); b.Template = new ControlTemplate(typeof(Button)) { VisualTree = border };
        b.Click += (_, _) => action(); return b;
    }
    Border Card(UIElement content) => new() { Child = content, Background = Brushes.White, BorderBrush = Ink("#E1E6EF"), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(10), Padding = new Thickness(22), Margin = new Thickness(0, 0, 0, 18) };
    void Heading(string title, string subtitle) { body.Children.Add(Text(title, 28, "#19253B", true)); body.Children.Add(Text(subtitle)); Gap(); }
    void Gap() => body.Children.Add(new Border { Height = 14 });
    void Notice(string message) => body.Children.Add(new Border { Background = Ink("#EAF1FF"), Padding = new Thickness(14), CornerRadius = new CornerRadius(7), Margin = new Thickness(0, 0, 0, 18), Child = Text(message, 12, "#3762A5") });
    void Notify(string message) => status.Text = message;
    void Navigate(int index)
    {
        timer.Stop(); body.Children.Clear(); navigation.Children.Clear(); breadcrumb.Text = "Çalışma alanı  /  " + pages[index];
        for (int i = 0; i < pages.Length; i++) { int n = i; var b = Button(icons[i] + "   " + pages[i], () => Navigate(n)); b.Background = Ink(i == index ? "#2A4264" : "#14243D"); b.Foreground = Ink(i == index ? "#FFFFFF" : "#B8C5D8"); b.BorderBrush = Brushes.Transparent; b.HorizontalContentAlignment = HorizontalAlignment.Left; b.Margin = new Thickness(0, 0, 0, 9); navigation.Children.Add(b); }
        switch (index) { case 0: Home(); break; case 1: Scenarios(); break; case 2: Catalog(); break; case 3: Connections(); break; case 4: Jobs(); break; }
    }
    void Table(string[] headers, string[][] rows, Action<int>? action = null)
    {
        var table = new Grid(); for (int c = 0; c < headers.Length; c++) table.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(c == 0 ? 1.5 : 1, GridUnitType.Star) });
        for (int r = 0; r <= rows.Length; r++) { table.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); for (int c = 0; c < headers.Length; c++) { var cell = new Border { Child = Text(r == 0 ? headers[c].ToUpper(System.Globalization.CultureInfo.GetCultureInfo("tr-TR")) : rows[r - 1][c], r == 0 ? 10 : 13, r == 0 ? "#8692A5" : "#35455E", r == 0 || c == 0), Padding = new Thickness(8, 13, 8, 7), BorderBrush = Ink("#EBEFF5"), BorderThickness = new Thickness(0, 0, 0, 1) }; if (r > 0 && action != null) { int n = r - 1; cell.Cursor = System.Windows.Input.Cursors.Hand; cell.MouseLeftButtonUp += (_, _) => action(n); } Grid.SetRow(cell, r); Grid.SetColumn(cell, c); table.Children.Add(cell); } } body.Children.Add(Card(table));
    }
    void Home()
    {
        Heading("Test verisi, ilişkileriyle birlikte.", "Prod verisini seçin, kapsamı doğrulayın ve test ortamınızı hazırlayın.");
        var metrics = new UniformGrid { Columns = 3 };
        foreach (var m in new[] { ("03", "Paylaşılan senaryo", "Kredi, müşteri ve teminat"), ("18", "Doğrulanmış ilişki", "FK ve iş kuralları birlikte"), ("12.480", "Son aktarımda kayıt", "Örnek çalışma · Bugün 09:42") }) { var card = Card(Stack(Text(m.Item2, 12), Text(m.Item1, 32, "#263D62", true), Text(m.Item3, 11))); card.Margin = new Thickness(0, 0, 14, 18); metrics.Children.Add(card); } body.Children.Add(metrics);
        body.Children.Add(Card(Stack(Text("Bir kredi seçin. Bağlı verileri biz bulalım.", 20, "#19253B", true), Text("Şemadan keşfedilen ilişkileri inceleyin; doğrulanmış iş kurallarıyla kapsamı tamamlayın."), Row(Button("＋  Senaryo oluştur", () => { step = 0; Designer(); }, true), Button("Senaryoları incele →", () => Navigate(1))))));
        body.Children.Add(Text("SON ÇALIŞMALAR", 11, "#7B8A9F", true)); Table(["Senaryo", "Ortam", "Durum", "Kayıt"], [[name, "PROD → UAT", "✓ Tamamlandı", "12.480"], ["Teminat örneklemi", "PROD → TEST", "✓ Tamamlandı", "3.210"], ["Aktif müşteri kümesi", "PROD → UAT", "! İnceleme gerekli", "840"]], _ => Navigate(4));
    }
    void Scenarios()
    {
        Heading("Senaryolar", "İlişkileri bir kez tanımlayın. Ekibinizle paylaşın, tekrar kullanın."); body.Children.Add(Row(Button("＋ Yeni senaryo", () => { step = 0; Designer(); }, true), Button("↓ JSON içe aktar", Import), Button("↑ JSON dışa aktar", Export))); Gap();
        Table(["Senaryo", "Başlangıç tablosu", "İlişki", "Sürüm", "Durum"], [[name, root, movements ? "6 ilişki" : "5 ilişki", "1.2", "Hazır →"], ["Aktif müşteri kümesi", "BANK.MUSTERI", "4 ilişki", "1.0", "Örnek"], ["Teminat örneklemi", "BANK.TEMINAT", "3 ilişki", "0.4", "Örnek"]], i => { if (i == 0) { step = 0; Designer(); } else Notify("Bu satır görsel örnektir. Düzenlenebilir örnek için ilk senaryoyu seçin."); });
        Notice("İlk senaryoya tıklayarak düzenleyin. JSON paylaşımı çalışır; şifre ve bağlantı bilgisi içermez.");
    }
    TextBox Field(StackPanel panel, string label, string value, Action<string>? changed = null)
    {
        panel.Children.Add(Text(label, 12, "#394B65", true)); var box = new TextBox { Text = value, Padding = new Thickness(10, 8, 10, 8), BorderBrush = Ink("#DCE2EB"), Margin = new Thickness(0, 0, 0, 14), FontSize = 13 }; if (changed != null) box.TextChanged += (_, _) => changed(box.Text); panel.Children.Add(box); return box;
    }
    ComboBox Select(StackPanel panel, string label, string[] options)
    {
        panel.Children.Add(Text(label, 12, "#394B65", true)); var combo = new ComboBox { ItemsSource = options, SelectedIndex = 0, Padding = new Thickness(9, 7, 9, 7), Margin = new Thickness(0, 0, 0, 14) }; panel.Children.Add(combo); return combo;
    }
    CheckBox Check(string label, bool selected = true) => new() { Content = label, IsChecked = selected, Margin = new Thickness(0, 7, 0, 9), FontSize = 13 };
    void Designer()
    {
        body.Children.Clear(); breadcrumb.Text = "Senaryolar  /  Senaryo tasarımcısı"; Heading("Senaryo tasarımcısı", "Kapsamı otomatik keşfedin, belirsiz ilişkileri netleştirin.");
        string[] steps = ["Başlangıç", "İlişki keşfi", "Kurallar", "Önizleme"]; body.Children.Add(Row(steps.Select((s, i) => (UIElement)Button($"{i + 1}  {s}", () => { step = Array.IndexOf(steps, s); Designer(); }, i == step)).ToArray())); Gap();
        switch (step)
        {
            case 0:
                var form = new StackPanel(); Field(form, "Senaryo adı", name, v => name = v); Select(form, "Kaynak bağlantısı", ["PROD_BANK · Salt okunur", "PROD_REPORT · Salt okunur"]); Select(form, "Başlangıç tablosu (örnek)", [root]);
                Field(form, "Deneme koşulu", condition, v => condition = v); Field(form, "En fazla başlangıç kaydı", limit, v => limit = v); form.Children.Add(Text("Bu koşul, örnek kapsamı incelemek içindir. Son kullanıcı parametreleri Kurallar adımında belirlenir.", 12)); body.Children.Add(Card(form)); break;
            case 1: Discovery(); break;
            case 2:
                var rules = new StackPanel(); rules.Children.Add(Text("Veri ve hedef kuralları", 18, "#19253B", true)); Select(rules, "Hedefte bulunan kayıtlar", ["Atla ve ilişkileri doğrula", "Anahtara göre güncelle", "Hata ver"]); Select(rules, "Eksik zorunlu ilişki", ["Aktarımı durdur", "Uyarı oluştur"]);
                foreach (string label in new[] { "TCKN → Tutarlı test kimliği", "Müşteri adı → Sentetik ad", "Telefon / e-posta → Test değeri", "Ürün ve şube → Hedefte varlığını doğrula" }) rules.Children.Add(Check(label)); rules.Children.Add(Text("Çalıştırırken sorulacak parametreler", 15, "#19253B", true)); foreach (string label in new[] { "Kredi numarası listesi", "Durum", "Tarih aralığı", "En fazla kredi sayısı" }) rules.Children.Add(Check(label)); body.Children.Add(Card(rules)); break;
            case 3: Preview(); break;
        }
        body.Children.Add(Row(Button("← Senaryolar", () => Navigate(1)), Button("Geri", () => { step = Math.Max(0, step - 1); Designer(); }), Button(step == 3 ? "Senaryoyu kaydet" : step == 0 ? "İlişkileri keşfet →" : "Devam →", () => { if (step == 3) { Navigate(1); Notify("Senaryo oturum için kaydedildi. Kalıcı paylaşım için JSON dışa aktarın."); } else { step++; Designer(); } }, true)));
    }
    void Discovery()
    {
        Notice(movements ? "6 ilişki kapsama alındı · İnceleme tamamlandı · Temsili keşif sonuçları" : "6 ilişki bulundu · 5 dahil · 1 ilişki inceleme bekliyor · Temsili keşif sonuçları");
        var split = new Grid(); split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.2, GridUnitType.Star) }); split.ColumnDefinitions.Add(new ColumnDefinition());
        var tree = new StackPanel(); tree.Children.Add(Text(root + "  /  BAŞLANGIÇ", 14, "#23426B", true));
        foreach (var label in new[] { "├ MUSTERI          FK · Burada dur", "├ ODEME_PLANI      FK · Alt kayıtları al", "│  └ TAKSIT        FK · Alt kayıtları al", "├ KREDI_TEMINAT    Katalog · Doğrulanmış", "│  └ TEMINAT       FK · Üst kaydı al", movements ? "└ KREDI_HAREKET    Doğrulanmış" : "└ KREDI_HAREKET    Öneri · İncele →" }) { var t = Text(label, 12, label.Contains("Öneri") ? "#A47622" : "#35455E"); t.FontFamily = new FontFamily("Consolas"); t.Margin = new Thickness(0, 15, 14, 15); tree.Children.Add(t); }
        tree.Children.Add(Text("Sınır: Müşterinin diğer kredileri alınmaz.", 12, "#4F7C69"));
        var details = new StackPanel { Margin = new Thickness(18, 0, 0, 0) }; details.Children.Add(Text("KREDI → KREDI_HAREKET", 16, "#19253B", true)); details.Children.Add(Text(movements ? "✓ Doğrulandı ve kapsama alındı" : "İlişki adayı · Uzman doğrulaması gerekli", 12, movements ? "#33816C" : "#A47622")); Field(details, "Kaynak kolon", "KREDI.KREDI_NO"); Field(details, "Hedef kolon", "KREDI_HAREKET.REFERANS_NO"); Field(details, "Ek koşul", "REFERANS_TIPI = 'KREDI'"); Select(details, "Takip kuralı", ["Bağlı kayıtları al, burada dur", "Bağlı kayıtları al ve devam et", "Kapsam dışında bırak"]);
        details.Children.Add(Button(movements ? "Kapsamdan çıkar" : "Doğrula ve dahil et", () => { movements = !movements; Designer(); }, true)); details.Children.Add(Button("＋ Manuel ilişki ekle", RelationDialog)); split.Children.Add(tree); Grid.SetColumn(details, 1); split.Children.Add(details); body.Children.Add(Card(split));
    }
    void Preview()
    {
        Notice($"Örnek kapsam · {root} · {condition} · En fazla {limit} başlangıç kaydı. Sayılar gerçek sorgu sonucu değildir.");
        Table(["Tablo", "Örnek kayıt", "Yöntem", "Kontrol"], [["MUSTERI", "82", "Gerekli üst kayıt", "✓ Maskeleme açık"], ["KREDI", limit, "Başlangıç kümesi", "✓ Anahtar mevcut"], ["ODEME_PLANI", "100", "Bağlı kayıtlar", "✓ İlişki tutarlı"], ["TAKSIT", "2.400", "Bağlı kayıtlar", "✓ İlişki tutarlı"], ["TEMINAT", "146", "Gerekli üst kayıt", "✓ İlişki tutarlı"], ["KREDI_TEMINAT", "146", "Bağlı kayıtlar", "✓ İlişki tutarlı"], ["KREDI_HAREKET", movements ? "8.420" : "—", movements ? "Doğrulanmış kural" : "Kapsam dışında", movements ? "✓ Dahil" : "! Dahil edilmedi"]]);
        body.Children.Add(Text("Aktarım sırası", 14, "#273952", true)); body.Children.Add(Text("MUSTERI / TEMINAT → KREDI → ODEME_PLANI / KREDI_TEMINAT → TAKSIT / HAREKET", 12)); body.Children.Add(Button("Bu senaryoyla görev oluştur →", JobDialog, true)); Gap();
    }
    void Catalog()
    {
        Heading("İlişki kataloğu", "Doğrulanmış ilişkileri senaryolar arasında yeniden kullanın."); body.Children.Add(Row(Button("＋ İlişki tanımla", RelationDialog), Button("Keşif önerilerini incele", () => { step = 1; Designer(); }, true))); Gap();
        Table(["İlişki", "Kaynak", "Koşul", "Durum"], [["KREDI → MUSTERI", "Foreign key", "MUSTERI_ID", "Doğrulanmış"], ["KREDI → ODEME_PLANI", "Foreign key", "KREDI_ID", "Doğrulanmış"], ["KREDI → KREDI_TEMINAT", "İş kuralı", "KREDI_NO", "Doğrulanmış"], ["KREDI → KREDI_HAREKET", "Keşif önerisi", "REFERANS_NO + TİP", movements ? "Doğrulanmış" : "İnceleme gerekli"]], _ => { step = 1; Designer(); });
    }
    void Connections()
    {
        Heading("Bağlantılar", "Kaynak ve hedef ortamlarını açıkça ayırın."); body.Children.Add(Button("＋ Bağlantı ekle", ConnectionDialog, true)); Gap(); Table(["Bağlantı", "Ortam", "Erişim", "Durum"], [["PROD_BANK", "Üretim", "Salt okunur", "Örnek bağlantı"], ["UAT_BANK", "Kullanıcı testi", "Okuma / yazma", "Örnek bağlantı"], ["TEST_BANK", "Geliştirme testi", "Okuma / yazma", "Örnek bağlantı"]], _ => ConnectionDialog()); Notice("Bu ekran bağlantı tasarımını gösterir. Girilen bilgiler saklanmaz ve ağ bağlantısı kurulmaz.");
    }
    void Jobs()
    {
        Heading("Görevler ve geçmiş", "Senaryoları çalıştırın, zamanlayın ve sonuçları izleyin."); body.Children.Add(Row(Button("＋ Görev oluştur", JobDialog, true), Button("Excel aktarımı", ExcelDialog))); Gap();
        Table(["Görev", "Senaryo", "Zamanlama", "Son durum"], [["UAT kredi hazırlığı", name, scheduled ? "Hafta içi 07:00 (örnek)" : "Elle çalıştırma", "Tamamlandı"], ["Gece test verisi", "Aktif müşteri kümesi", "Her gün 02:00 (örnek)", "Bekliyor"]], _ => JobDialog());
        progress = new ProgressBar { Height = 8, Maximum = 100, Foreground = Ink("#3467DC"), Background = Ink("#E7EDF7"), Margin = new Thickness(0, 10, 0, 15) }; progressLabel = Text("Örnek çalışma hazır. Simülasyonu başlatarak ilerlemeyi inceleyin.");
        body.Children.Add(Card(Stack(Text("Aktarım izleyicisi", 18, "#19253B", true), Text("PROD_BANK → UAT_BANK  /  " + name), progress, progressLabel, Row(Button("▶ Simülasyonu başlat", () => { progress.Value = 0; timer.Start(); }, true), Button("Durdur", () => { timer.Stop(); progressLabel.Text = "Simülasyon durduruldu."; })))));
    }
    void Dialog(string title, Action<StackPanel, Window> content)
    {
        var dialog = new Window { Title = title, Width = 540, SizeToContent = SizeToContent.Height, MaxHeight = 790, Owner = window, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = Ink("#F3F5F9"), FontFamily = window.FontFamily, ResizeMode = ResizeMode.NoResize }; var panel = new StackPanel { Margin = new Thickness(26) }; panel.Children.Add(Text(title, 23, "#19253B", true)); content(panel, dialog); dialog.Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; dialog.ShowDialog();
    }
    void ConnectionDialog() => Dialog("Oracle bağlantısı", (p, w) => { Field(p, "Bağlantı adı", "Yeni test bağlantısı"); Select(p, "Ortam", ["Test", "UAT", "Üretim · Salt okunur"]); Field(p, "Sunucu / port / servis", "oracle-test:1521/TESTDB"); Field(p, "Kullanıcı adı", "TEST_USER"); p.Children.Add(Text("Şifre")); p.Children.Add(new PasswordBox { Padding = new Thickness(10), Margin = new Thickness(0, 0, 0, 15) }); p.Children.Add(Text("Prototip: Bağlantı testi yapılmaz ve bilgiler saklanmaz.")); p.Children.Add(Button("Kapat", () => w.Close(), true)); });
    void RelationDialog() => Dialog("İlişki tanımla", (p, w) => { Field(p, "Kaynak tablo / kolon", "BANK.KREDI / KREDI_NO"); Field(p, "İlişkili tablo / kolon", "BANK.KREDI_HAREKET / REFERANS_NO"); Field(p, "Ek koşul", "REFERANS_TIPI = 'KREDI'"); Select(p, "Takip davranışı", ["Bağlı kayıtları al, burada dur", "Üst kaydı al, burada dur", "İlişkilerden devam et"]); p.Children.Add(Text("Bu form etkileşim örneğidir. Yeni ilişki henüz saklanmaz; hareket ilişkisini keşif ekranından dahil edebilirsiniz.")); p.Children.Add(Button("Keşif ekranına dön", () => { w.Close(); step = 1; Designer(); }, true)); });
    void JobDialog() => Dialog("Aktarım görevi oluştur", (p, w) => { Field(p, "Görev adı", "UAT kredi hazırlığı"); Select(p, "Senaryo", [name + " · v1.2"]); Select(p, "Kaynak → Hedef", ["PROD_BANK → UAT_BANK", "PROD_BANK → TEST_BANK"]); Field(p, "Kredi koşulu", condition, v => condition = v); Field(p, "En fazla kredi", limit, v => limit = v); var check = Check("Hafta içi 07:00 için zamanla (örnek)", scheduled); p.Children.Add(check); p.Children.Add(Text("Prototip zamanlanmış görev oluşturmaz. Çalıştırma yalnızca animasyon simülasyonudur.")); p.Children.Add(Button("Görev ekranını göster", () => { scheduled = check.IsChecked == true; w.Close(); Navigate(4); }, true)); });
    void ExcelDialog() => Dialog("Excel aktarımı", (p, w) => { Select(p, "Aktarım yönü", ["Oracle → Excel", "Excel → Oracle"]); Field(p, "Tablo", root); Field(p, "Dosya adı", "kredi-test-verisi.xlsx"); Select(p, "Alan eşleştirme", ["A: KREDI_NO → KREDI_NO", "B: TUTAR → TUTAR"]); p.Children.Add(Check("Maskeleme kurallarını uygula")); p.Children.Add(Text("Ekran örneğidir. Excel dosyası okunmaz veya yazılmaz.")); p.Children.Add(Button("Kapat", () => w.Close(), true)); });
    void Export()
    {
        var dialog = new SaveFileDialog { Filter = "Senaryo JSON|*.json", FileName = "kredi-senaryosu.json" }; if (dialog.ShowDialog(window) != true) return;
        try { File.WriteAllText(dialog.FileName, JsonSerializer.Serialize(new Scenario(1, name, root, condition, limit, movements), new JsonSerializerOptions { WriteIndented = true })); Notify("Örnek senaryo JSON dosyasına aktarıldı."); } catch (Exception ex) { MessageBox.Show(window, ex.Message, "Dosya yazılamadı"); }
    }
    void Import()
    {
        var dialog = new OpenFileDialog { Filter = "Senaryo JSON|*.json" }; if (dialog.ShowDialog(window) != true) return;
        try { var data = JsonSerializer.Deserialize<Scenario>(File.ReadAllText(dialog.FileName)); if (data is null || data.SchemaVersion != 1 || string.IsNullOrWhiteSpace(data.Name) || string.IsNullOrWhiteSpace(data.RootTable) || data.RootTable != "BANK.KREDI") throw new InvalidDataException("Bu prototip için BANK.KREDI tabanlı bir senaryo seçin."); name = data.Name; root = data.RootTable; condition = data.Condition; limit = data.Limit; movements = data.IncludeMovements; Navigate(1); Notify("Senaryo içe aktarıldı."); } catch (Exception ex) { MessageBox.Show(window, ex.Message, "Senaryo okunamadı"); }
    }
    public record Scenario(int SchemaVersion, string Name, string RootTable, string Condition, string Limit, bool IncludeMovements);
}
