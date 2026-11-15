package com.databridge;

import java.net.URI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.*;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

public final class DesktopLauncher {
  public static void main(String[] args) throws Exception {
    Backend.configure();
    var context = (ServletWebServerApplicationContext) SpringApplication.run(Backend.class, args);
    if (java.util.Arrays.asList(args).contains("--headless")) return;
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      shell.setText("DataBridge · Oracle data workspace");
      org.eclipse.swt.graphics.Image[] icons = new org.eclipse.swt.graphics.Image[4];
      int[] sizes = {16, 32, 48, 256};
      for (int i = 0; i < sizes.length; i++) {
        try (var stream =
            DesktopLauncher.class.getResourceAsStream("/icons/databridge-" + sizes[i] + ".png")) {
          icons[i] = new org.eclipse.swt.graphics.Image(display, stream);
        }
      }
      shell.setImages(icons);
      shell.addDisposeListener(
          event -> {
            for (var icon : icons) icon.dispose();
          });
      var screen = display.getClientArea();
      shell.setSize(Math.min(1440, screen.width), Math.min(940, screen.height));
      shell.setMinimumSize(Math.min(1100, screen.width), Math.min(740, screen.height));
      shell.setLayout(new FillLayout());
      System.setProperty(
          "org.eclipse.swt.browser.EdgeDataDir",
          java.nio.file.Path.of(System.getProperty("databridge.data-dir"), "webview").toString());
      Browser browser = new Browser(shell, SWT.EDGE);
      String origin = "http://127.0.0.1:" + context.getWebServer().getPort();
      browser.addLocationListener(
          new LocationAdapter() {
            @Override
            public void changing(LocationEvent e) {
              try {
                URI uri = URI.create(e.location);
                if (!e.location.equals("about:blank")
                    && !("http".equals(uri.getScheme())
                        && "127.0.0.1".equals(uri.getHost())
                        && uri.getPort() == context.getWebServer().getPort())) e.doit = false;
              } catch (Exception ex) {
                e.doit = false;
              }
            }
          });
      browser.setUrl(origin + "/#token=" + System.getProperty("databridge.token"));
      shell.setMaximized(true);
      shell.open();
      while (!shell.isDisposed()) {
        if (!display.readAndDispatch()) display.sleep();
      }
    } catch (org.eclipse.swt.SWTError error) {
      MessageBox box = new MessageBox(new Shell(display), SWT.ICON_ERROR | SWT.OK);
      box.setText("Unable to start WebView2");
      box.setMessage("Microsoft Edge WebView2 Runtime is required.\n" + error.getMessage());
      box.open();
    } finally {
      display.dispose();
      context.close();
    }
  }
}
