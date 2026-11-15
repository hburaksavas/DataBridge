package com.databridge;

import java.nio.file.*;
import java.util.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Backend {
  static void configure() throws Exception {
    System.setProperty("databridge.instance", Long.toString(ProcessHandle.current().pid()));
    if (System.getProperty("databridge.data-dir") == null) {
      String base = System.getenv("LOCALAPPDATA");
      System.setProperty(
          "databridge.data-dir",
          Path.of(base == null ? System.getProperty("user.home") : base, "DataBridge").toString());
    }
    Files.createDirectories(Path.of(System.getProperty("databridge.data-dir")));
    if (System.getProperty("databridge.token") == null)
      System.setProperty("databridge.token", UUID.randomUUID() + "-" + UUID.randomUUID());
  }
}
