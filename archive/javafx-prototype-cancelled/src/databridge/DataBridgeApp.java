package databridge;

import javafx.application.Application;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.Insets;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import java.nio.file.Files;
import java.util.*;

public final class DataBridgeApp extends Application {
    private Stage stage;
    private final VBox body = new VBox(18), nav = new VBox(7);
    private final Label breadcrumb = new Label(), status = new Label("Hazır · Java 21 / JavaFX · Örnek veriler");
    private final String[] pages = {"Genel bakış", "Senaryolar", "İlişki kataloğu", "Bağlantılar", "Görevler ve geçmiş"};
    private final String[] icons = {"▦", "◇", "⑂", "▤", "◷"};
    private String scenarioName = "Kredi ve bağlı verileri", condition = "DURUM = 'AKTIF'", limit = "100";
    private String existing = "Atla ve doğrula", missing = "Aktarımı durdur", target = "UAT_BANK", jobName = "UAT kredi hazırlığı";
    private boolean movement, scheduled, maskId = true, maskName = true, maskContact = true;
    private int step;
    private Timeline animation;

    @Override public void start(Stage primary) {
        stage = primary;
        BorderPane shell = new BorderPane(); shell.getStyleClass().add("shell");
        VBox sidebar = new VBox(10); sidebar.getStyleClass().add("sidebar"); sidebar.setPrefWidth(226);
        Label brand = label("◈  DataBridge", "brand");
        Label sub = label("TEST VERİSİ ÇALIŞMA ALANI", "sidebar-caption");
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().addAll(brand, sub, new Region() {{setMinHeight(22);}}, nav, spacer,
            label("●  Java 21 prototipi", "mint"), label("Tüm veriler örnektir.\nOracle bağlantısı kurulmaz.", "sidebar-note"), label("DESKTOP / WINDOWS     v0.2", "sidebar-caption"));
        shell.setLeft(sidebar);
        BorderPane main = new BorderPane(); HBox header = new HBox(); header.getStyleClass().add("header");
        Region fill = new Region(); HBox.setHgrow(fill, Priority.ALWAYS); header.getChildren().addAll(breadcrumb, fill, label("PROD → TEST     /     DB", "environment")); main.setTop(header);
        body.setPadding(new Insets(28, 32, 32, 32)); ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true); scroll.getStyleClass().add("content-scroll"); main.setCenter(scroll);
        status.getStyleClass().add("status"); status.setMaxWidth(Double.MAX_VALUE); main.setBottom(status); shell.setCenter(main);
        Scene scene = new Scene(shell, 1380, 880); scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("theme.css")).toExternalForm());
        stage.setTitle("DataBridge — Java 21 ekran prototipi"); stage.setMinWidth(1080); stage.setMinHeight(720); stage.setScene(scene); navigate(0); stage.show();
    }
    private Label label(String text, String style) { Label l = new Label(text); l.setWrapText(true); l.getStyleClass().add(style); return l; }
    private Label text(String text) { return label(text, "muted"); }
    private Button button(String text, Runnable action, boolean primary) { Button b = new Button(text); b.getStyleClass().add(primary ? "primary" : "secondary"); b.setOnAction(e -> action.run()); return b; }
    private HBox row(Node... nodes) { HBox r = new HBox(10, nodes); r.setAlignment(javafx.geometry.Pos.CENTER_LEFT); return r; }
    private VBox stack(Node... nodes) { return new VBox(13, nodes); }
    private VBox card(Node... nodes) { VBox p = stack(nodes); p.getStyleClass().add("card"); return p; }
    private void heading(String title, String subtitle) { body.getChildren().add(stack(label(title, "heading"), text(subtitle))); }
    private void notice(String message) { Label l = label(message, "notice"); l.setMaxWidth(Double.MAX_VALUE); body.getChildren().add(l); }
    private void notify(String message) { status.setText(message); }
    private void navigation(int selected) {
        nav.getChildren().clear();
        for (int i=0; i<pages.length; i++) { int index=i; Button b=button(icons[i]+"   "+pages[i], () -> navigate(index), false); b.getStyleClass().add("nav-button"); if(i==selected) b.getStyleClass().add("selected"); b.setMaxWidth(Double.MAX_VALUE); nav.getChildren().add(b); }
    }
    private void navigate(int page) {
        if(animation!=null) animation.stop(); body.getChildren().clear(); navigation(page); breadcrumb.setText("Çalışma alanı  /  "+pages[page]);
        switch(page) { case 0 -> home(); case 1 -> scenarios(); case 2 -> catalog(); case 3 -> connections(); case 4 -> jobs(); }
    }
    private void table(String[] headers, String[][] values, java.util.function.IntConsumer action) {
        GridPane grid=new GridPane(); grid.getStyleClass().add("data-table");
        for(int c=0;c<headers.length;c++) { ColumnConstraints cc=new ColumnConstraints(); cc.setPercentWidth(c==0 ? 32 : 68.0/(headers.length-1)); grid.getColumnConstraints().add(cc); Label l=label(headers[c].toUpperCase(Locale.forLanguageTag("tr")),"column-title"); l.setMaxWidth(Double.MAX_VALUE); grid.add(l,c,0); }
        for(int r=0;r<values.length;r++) for(int c=0;c<headers.length;c++) { Label l=label(values[r][c], c==0 ? "cell strong" : "cell"); l.getStyleClass().remove("cell strong"); l.getStyleClass().add("cell"); if(c==0) l.getStyleClass().add("strong"); l.setMaxWidth(Double.MAX_VALUE); if(action!=null) { int index=r; l.setStyle("-fx-cursor: hand;"); l.setOnMouseClicked(e -> action.accept(index)); } grid.add(l,c,r+1); }
        body.getChildren().add(card(grid));
    }
    private void home() {
        heading("Test verisi, ilişkileriyle birlikte.", "Prod verisini seçin, kapsamı doğrulayın ve test ortamınızı hazırlayın.");
        HBox metrics=new HBox(16); for(String[] m: new String[][] {{"03","Örnek senaryo","Kredi, müşteri ve teminat"},{"18","Katalog ilişkisi","FK ve iş kuralları birlikte"},{"12.480","Son örnek aktarım","Temsili çalışma · 09:42"}}) { VBox tile=card(text(m[1]),label(m[0],"metric"),text(m[2])); HBox.setHgrow(tile,Priority.ALWAYS); tile.setMaxWidth(Double.MAX_VALUE); metrics.getChildren().add(tile); } body.getChildren().add(metrics);
        body.getChildren().add(card(label("Bir kredi seçin. Bağlı verileri biz bulalım.","section-title"), text("Otomatik keşif taslağını inceleyin; doğrulanmış ilişkilerle aktarım kapsamını tamamlayın."), row(button("＋ Senaryo oluştur",()->{step=0;designer();},true),button("Senaryoları incele →",()->navigate(1),false))));
        body.getChildren().add(label("SON ÖRNEK ÇALIŞMALAR","eyebrow"));
        table(new String[]{"Senaryo","Ortam","Durum","Kayıt"},new String[][]{{scenarioName,"PROD → UAT","✓ Tamamlandı","12.480"},{"Teminat örneklemi","PROD → TEST","✓ Tamamlandı","3.210"},{"Aktif müşteri kümesi","PROD → UAT","! İnceleme gerekli","840"}},i->navigate(4));
    }
    private void scenarios() {
        heading("Senaryolar","İlişkileri bir kez tanımlayın. Ekibinizle paylaşın, tekrar kullanın.");
        body.getChildren().add(row(button("＋ Senaryo oluştur",()->{step=0;designer();},true),button("↓ Senaryo içe aktar",this::importScenario,false),button("↑ Senaryo dışa aktar",this::exportScenario,false)));
        table(new String[]{"Senaryo","Başlangıç","İlişki","Durum"}, new String[][]{{scenarioName,"BANK.KREDI",movement?"6 dahil":"5 dahil · 1 öneri","Düzenle →"},{"Aktif müşteri kümesi","BANK.MUSTERI","4 ilişki","Görsel örnek"},{"Teminat örneklemi","BANK.TEMINAT","3 ilişki","Görsel örnek"}},i->{if(i==0){step=0;designer();}else notify("Düzenlenebilir prototip için ilk senaryoyu seçin.");});
        notice("İlk senaryo düzenlenebilir ve XML olarak dışa/içe aktarılabilir. Diğer senaryolar görsel örnektir. Bağlantı şifreleri paylaşılmaz.");
    }
    private TextField field(VBox p,String title,String value, java.util.function.Consumer<String> onChange) { p.getChildren().add(label(title,"field-label")); TextField f=new TextField(value); if(onChange!=null) f.textProperty().addListener((o,a,b)->onChange.accept(b)); p.getChildren().add(f); return f; }
    private ComboBox<String> select(VBox p,String title,String current,String... values) { p.getChildren().add(label(title,"field-label")); ComboBox<String> c=new ComboBox<>(); c.getItems().addAll(values); c.setValue(current); c.setMaxWidth(Double.MAX_VALUE); p.getChildren().add(c); return c; }
    private CheckBox check(String title,boolean value,java.util.function.Consumer<Boolean> changed) { CheckBox c=new CheckBox(title); c.setSelected(value); if(changed!=null)c.selectedProperty().addListener((o,a,b)->changed.accept(b)); return c; }
    private void designer() {
        body.getChildren().clear(); navigation(1); breadcrumb.setText("Senaryolar  /  Senaryo tasarımcısı"); heading("Senaryo tasarımcısı","Başlangıç tablosundan, doğrulanmış aktarım kapsamına.");
        String[] steps={"Başlangıç","İlişki keşfi","Kurallar","Önizleme"}; HBox tabs=new HBox(10); for(int i=0;i<4;i++){int n=i; tabs.getChildren().add(button((i+1)+"  "+steps[i],()->{step=n;designer();},step==i));} body.getChildren().add(tabs);
        switch(step) {
            case 0 -> { VBox f=card(); field(f,"Senaryo adı",scenarioName,v->scenarioName=v); select(f,"Kaynak bağlantısı","PROD_BANK · Salt okunur","PROD_BANK · Salt okunur"); select(f,"Başlangıç tablosu (prototip)","BANK.KREDI","BANK.KREDI"); field(f,"Deneme koşulu",condition,v->condition=v); field(f,"En fazla başlangıç kaydı",limit,v->limit=v); f.getChildren().add(text("Kaynak sorgulanmaz. Bu alanlar, senaryonun son kullanıcıya nasıl sunulacağını gösterir.")); body.getChildren().add(f); }
            case 1 -> discovery();
            case 2 -> rules();
            case 3 -> preview();
        }
        Button back=button("← Geri",()->{step--;designer();},false); back.setDisable(step==0);
        body.getChildren().add(row(button("Senaryolara dön",()->navigate(1),false),back,button(step==3?"Senaryoyu kaydet":step==0?"İlişkileri keşfet →":"Devam →",()->{if(step==3){navigate(1);notify("Senaryo oturum içinde kaydedildi. Kalıcı paylaşım için dışa aktarın.");}else {step++;designer();}},true)));
    }
    private void discovery() {
        notice(movement?"6 ilişki dahil · İnceleme tamamlandı · Keşif sonuçları temsilidir.":"6 ilişki bulundu · 5 dahil · 1 inceleme bekliyor · Keşif sonuçları temsilidir.");
        TreeItem<String> root=new TreeItem<>("BANK.KREDI  /  başlangıç"); root.setExpanded(true);
        TreeItem<String> plan=new TreeItem<>("ODEME_PLANI   ·   FK"); plan.getChildren().add(new TreeItem<>("TAKSIT   ·   FK")); plan.setExpanded(true);
        TreeItem<String> collateral=new TreeItem<>("KREDI_TEMINAT   ·   Katalog"); collateral.getChildren().add(new TreeItem<>("TEMINAT   ·   FK")); collateral.setExpanded(true);
        TreeItem<String> move=new TreeItem<>(movement?"KREDI_HAREKET   ·   Doğrulanmış":"KREDI_HAREKET   ·   İnceleme gerekli"); root.getChildren().addAll(List.of(new TreeItem<>("MUSTERI   ·   FK / burada dur"),plan,collateral,move));
        TreeView<String> tree=new TreeView<>(root); tree.setPrefHeight(305); tree.getSelectionModel().select(move);
        VBox details=new VBox(10); showRelation(details,"KREDI_HAREKET");
        tree.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null)showRelation(details,b.getValue().split("\\s")[0]);});
        VBox left=stack(label("İLİŞKİ AĞACI","eyebrow"),tree,text("Sınır: Müşterinin diğer kredileri alınmaz."),button("＋ Manuel ilişki tanımla",this::relationDialog,false));
        GridPane columns=new GridPane(); columns.setHgap(24); ColumnConstraints a=new ColumnConstraints();a.setPercentWidth(48);ColumnConstraints b=new ColumnConstraints();b.setPercentWidth(52);columns.getColumnConstraints().addAll(a,b); columns.add(left,0,0);columns.add(details,1,0);body.getChildren().add(card(columns));
    }
    private void showRelation(VBox p,String table) {
        p.getChildren().clear(); boolean candidate=table.equals("KREDI_HAREKET"); p.getChildren().addAll(label(table,"section-title"),label(candidate?(movement?"✓ Doğrulandı ve kapsama alındı":"İnceleme gerekli · Kural önerisi"):"✓ Şemadan veya katalogdan bulundu","eyebrow"));
        if(candidate) { field(p,"Kaynak kolon","KREDI.KREDI_NO",null);field(p,"İlişkili kolon","KREDI_HAREKET.REFERANS_NO",null);field(p,"Ek koşul","REFERANS_TIPI = 'KREDI'",null);select(p,"Takip kuralı","Bağlı kayıtları al, burada dur","Bağlı kayıtları al, burada dur");p.getChildren().add(button(movement?"Kapsamdan çıkar":"Doğrula ve dahil et",()->{movement=!movement;designer();},true)); }
        else { p.getChildren().add(text("Bu ilişki örnek kapsamda yer alıyor. Ayrıntılı düzenleme sonraki sürümde eklenecek.")); p.getChildren().add(text(table.equals("MUSTERI")?"Gerekli müşteriyi al ve burada dur. Diğer kredilere geçme.":"Seçilen başlangıç kredilerine bağlı kayıtları al.")); }
    }
    private void rules() {
        VBox f=card(label("Hedef ve veri kuralları","section-title")); ComboBox<String> e=select(f,"Hedefte bulunan kayıt",existing,"Atla ve doğrula","Anahtara göre güncelle","Hata ver"); e.valueProperty().addListener((o,a,b)->existing=b); ComboBox<String> m=select(f,"Eksik zorunlu ilişki",missing,"Aktarımı durdur","Uyarı oluştur"); m.valueProperty().addListener((o,a,b)->missing=b);
        f.getChildren().addAll(label("MASKELEME","eyebrow"),check("TCKN → Tutarlı test kimliği",maskId,v->maskId=v),check("Müşteri adı → Sentetik ad",maskName,v->maskName=v),check("Telefon / e-posta → Test değeri",maskContact,v->maskContact=v),text("Aynı kaynak değeri, ilişkili kayıtlarda aynı test değerine dönüşür."),label("KULLANICI PARAMETRELERİ","eyebrow"),text("Kredi numarası listesi · Durum · Tarih aralığı · En fazla kredi sayısı")); body.getChildren().add(f);
    }
    private void preview() {
        notice("Örnek kapsam · "+condition+" · Sınır: "+limit+" kredi. Aşağıdaki sayılar gerçek sorgu sonucu değildir.");
        table(new String[]{"Tablo","Örnek kayıt","Kapsam","Kontrol"},new String[][]{{"MUSTERI","82","Gerekli üst kayıt",maskName?"✓ Maskeleme açık":"Maskeleme kapalı"},{"KREDI",limit,"Başlangıç kümesi","✓ Anahtar mevcut"},{"ODEME_PLANI","100","Bağlı kayıtlar","✓ Dahil"},{"TAKSIT","2.400","Bağlı kayıtlar","✓ Dahil"},{"TEMINAT","146","Gerekli üst kayıt","✓ Dahil"},{"KREDI_TEMINAT","146","Bağlı kayıtlar","✓ Dahil"},{"KREDI_HAREKET",movement?"8.420":"—","İş kuralı",movement?"✓ Dahil":"Kapsam dışında"}},null);
        body.getChildren().add(card(label("Çalıştırma özeti","section-title"),text("Mevcut kayıt: "+existing+"  ·  Eksik ilişki: "+missing),text("Maskeleme: Kimlik "+(maskId?"açık":"kapalı")+", ad "+(maskName?"açık":"kapalı")+", iletişim "+(maskContact?"açık":"kapalı")),text("Sıra: MUSTERI / TEMINAT → KREDI → ODEME_PLANI / KREDI_TEMINAT → TAKSIT / HAREKET"),button("Bu senaryoyla görev oluştur →",this::jobDialog,true)));
    }
    private void catalog() {
        heading("İlişki kataloğu","Bir kez doğrulayın; farklı senaryolarda tekrar kullanın.");body.getChildren().add(row(button("＋ İlişki tanımla",this::relationDialog,false),button("Keşif önerilerini incele",()->{step=1;designer();},true)));
        table(new String[]{"İlişki","Kaynak","Koşul","Durum"},new String[][]{{"KREDI → MUSTERI","Foreign key","MUSTERI_ID","Doğrulanmış"},{"KREDI → ODEME_PLANI","Foreign key","KREDI_ID","Doğrulanmış"},{"KREDI → KREDI_TEMINAT","İş kuralı","KREDI_NO","Doğrulanmış"},{"KREDI → KREDI_HAREKET","Keşif önerisi","REFERANS_NO + TİP",movement?"Doğrulanmış":"İnceleme gerekli"}},i->{step=1;designer();});
    }
    private void connections() {
        heading("Bağlantılar","Üretim kaynağını ve test hedeflerini açıkça ayırın.");body.getChildren().add(button("＋ Bağlantı ekle",this::connectionDialog,true));table(new String[]{"Bağlantı","Ortam","Erişim","Durum"},new String[][]{{"PROD_BANK","Üretim","Salt okunur","Örnek"},{"UAT_BANK","Kullanıcı testi","Okuma / yazma","Örnek"},{"TEST_BANK","Geliştirme testi","Okuma / yazma","Örnek"}},i->connectionDialog());notice("Bağlantı bilgileri saklanmaz. Bu prototipte ağ bağlantısı ve parola yönetimi yoktur.");
    }
    private void jobs() {
        heading("Görevler ve geçmiş","Çalıştırma, zamanlama ve sonuç takibini inceleyin.");body.getChildren().add(row(button("＋ Görev oluştur",this::jobDialog,true),button("Excel aktarımı",this::excelDialog,false)));
        table(new String[]{"Görev","Senaryo","Zamanlama","Durum"},new String[][]{{jobName,scenarioName,scheduled?"Hafta içi 07:00 (örnek)":"Elle çalıştırma","Simülasyona hazır"},{"Gece test verisi","Aktif müşteri kümesi","Her gün 02:00 (örnek)","Görsel örnek"}},i->jobDialog());
        ProgressBar bar=new ProgressBar(0);bar.setMaxWidth(Double.MAX_VALUE);Label result=text("Hazır · Gerçek veri aktarılmaz.");
        Button start=button("▶ Simülasyonu başlat",()->{if(animation!=null)animation.stop();bar.setProgress(0);animation=new Timeline(new KeyFrame(Duration.millis(160),e->{bar.setProgress(Math.min(1,bar.getProgress()+0.04));result.setText(bar.getProgress()>=1?"✓ Simülasyon tamamlandı · Veritabanına yazılmadı.":"Örnek aktarım: %"+Math.round(bar.getProgress()*100)+" · BANK.TAKSIT");}));animation.setCycleCount(25);animation.play();},true);
        body.getChildren().add(card(label("Aktarım izleyicisi","section-title"),text("PROD_BANK → "+target+" / "+scenarioName),bar,result,row(start,button("Durdur",()->{if(animation!=null)animation.stop();result.setText("Simülasyon durduruldu.");},false))));
    }
    private Dialog<Void> dialog(String title,VBox content) { Dialog<Void> d=new Dialog<>();d.initOwner(stage);d.setTitle(title);d.getDialogPane().setContent(content);d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);d.getDialogPane().getStylesheets().add(getClass().getResource("theme.css").toExternalForm());d.getDialogPane().setPrefWidth(530);return d; }
    private void connectionDialog() { VBox p=stack(label("Oracle bağlantısı","section-title"));field(p,"Bağlantı adı","Yeni test bağlantısı",null);select(p,"Ortam","Test","Test","UAT","Üretim · Salt okunur");field(p,"Sunucu / port / servis","oracle-test:1521/TESTDB",null);field(p,"Kullanıcı adı","TEST_USER",null);p.getChildren().add(label("Parola","field-label"));p.getChildren().add(new PasswordField());p.getChildren().add(text("Görsel form: Bilgiler saklanmaz, bağlantı kurulmaz."));dialog("Bağlantı tasarımı",p).showAndWait(); }
    private void relationDialog() { VBox p=stack(label("İlişki tanımla","section-title"));field(p,"Kaynak tablo / kolon","BANK.KREDI / KREDI_NO",null);field(p,"İlişkili tablo / kolon","BANK.KREDI_HAREKET / REFERANS_NO",null);field(p,"Ek koşul","REFERANS_TIPI = 'KREDI'",null);p.getChildren().add(text("Form örneğidir. Kural kaydetmez. Örnek hareket ilişkisini keşif ekranından doğrulayabilirsiniz."));dialog("Manuel ilişki",p).showAndWait(); }
    private void jobDialog() { VBox p=stack(label("Aktarım görevi","section-title"));TextField n=field(p,"Görev adı",jobName,null);select(p,"Senaryo",scenarioName,scenarioName);ComboBox<String> t=select(p,"Hedef ortam",target,"UAT_BANK","TEST_BANK");CheckBox s=check("Hafta içi 07:00 (örnek zamanlama)",scheduled,null);p.getChildren().addAll(s,text("İşletim sisteminde görev oluşturulmaz. Kaydetme yalnızca prototip oturumu içindir."));Dialog<Void> d=dialog("Görev oluştur",p);p.getChildren().add(button("Görevi kaydet ve göster",()->{jobName=n.getText();target=t.getValue();scheduled=s.isSelected();d.close();navigate(4);},true));d.showAndWait(); }
    private void excelDialog() { VBox p=stack(label("Excel aktarımı","section-title"));select(p,"Yön","Oracle → Excel","Oracle → Excel","Excel → Oracle");field(p,"Tablo","BANK.KREDI",null);field(p,"Dosya","kredi-test-verisi.xlsx",null);field(p,"Kolon eşleştirme","A: KREDI_NO → KREDI_NO",null);p.getChildren().addAll(check("Maskeleme uygula",true,null),text("Ekran örneğidir. Excel dosyası okunmaz veya oluşturulmaz."));dialog("Excel aktarımı",p).showAndWait(); }
    private String xml(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;"); }
    private FileChooser chooser() {FileChooser c=new FileChooser();c.getExtensionFilters().add(new FileChooser.ExtensionFilter("DataBridge senaryosu (*.xml)","*.xml"));return c;}
    private void exportScenario() {FileChooser c=chooser();c.setInitialFileName("kredi-senaryosu.xml");java.io.File file=c.showSaveDialog(stage);if(file==null)return;try{String content="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<scenario version=\"1\">\n";for(String[] pair:new String[][]{{"name",scenarioName},{"root","BANK.KREDI"},{"condition",condition},{"limit",limit},{"movements",String.valueOf(movement)},{"existing",existing},{"missing",missing},{"maskId",String.valueOf(maskId)},{"maskName",String.valueOf(maskName)},{"maskContact",String.valueOf(maskContact)}})content+="  <"+pair[0]+">"+xml(pair[1])+"</"+pair[0]+">\n";Files.writeString(file.toPath(),content+"</scenario>\n");notify("Senaryo XML olarak dışa aktarıldı.");}catch(Exception ex){error(ex);}}
    private String value(Element e,String key){var nodes=e.getElementsByTagName(key);if(nodes.getLength()!=1)throw new IllegalArgumentException("Eksik alan: "+key);return nodes.item(0).getTextContent();}
    private void importScenario() {java.io.File f=chooser().showOpenDialog(stage);if(f==null)return;try{if(Files.size(f.toPath())>1_000_000)throw new IllegalArgumentException("Senaryo dosyası çok büyük.");DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);Element e=factory.newDocumentBuilder().parse(f).getDocumentElement();if(!e.getTagName().equals("scenario")||!e.getAttribute("version").equals("1")||!value(e,"root").equals("BANK.KREDI"))throw new IllegalArgumentException("BANK.KREDI tabanlı v1 prototip senaryosu seçin.");String nn=value(e,"name"),nc=value(e,"condition"),nl=value(e,"limit"),ne=value(e,"existing"),nm=value(e,"missing");boolean mv=Boolean.parseBoolean(value(e,"movements")),mi=Boolean.parseBoolean(value(e,"maskId")),mn=Boolean.parseBoolean(value(e,"maskName")),mc=Boolean.parseBoolean(value(e,"maskContact"));if(nn.isBlank()||Integer.parseInt(nl)<1)throw new IllegalArgumentException("Ad ve kayıt sınırını kontrol edin.");scenarioName=nn;condition=nc;limit=nl;existing=ne;missing=nm;movement=mv;maskId=mi;maskName=mn;maskContact=mc;navigate(1);notify("Senaryo içe aktarıldı.");}catch(Exception ex){error(ex);}}
    private void error(Exception ex){Alert a=new Alert(Alert.AlertType.ERROR,ex.getMessage(),ButtonType.OK);a.initOwner(stage);a.setHeaderText("Dosya işlemi tamamlanamadı");a.showAndWait();}
    public static void main(String[] args) {launch(args);}
}
