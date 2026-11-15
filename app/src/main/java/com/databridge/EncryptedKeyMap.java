package com.databridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Bounded-memory, ephemeral key mapping: HMAC lookup keys and AES-GCM encrypted values. */
final class EncryptedKeyMap implements AutoCloseable {
  private final Path file;
  private final Connection db;
  private final byte[] secret = new byte[32];
  private final ObjectMapper json = new ObjectMapper();
  private final Mac mac;
  private final SecureRandom random = new SecureRandom();

  EncryptedKeyMap() throws Exception {
    random.nextBytes(secret);
    mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    Path directory =
        Path.of(
            System.getProperty("databridge.data-dir", System.getProperty("java.io.tmpdir")),
            "keymaps");
    Files.createDirectories(directory);
    file = Files.createTempFile(directory, "keys-", ".db");
    db = DriverManager.getConnection("jdbc:sqlite:" + file);
    try (var s = db.createStatement()) {
      s.execute("PRAGMA journal_mode=OFF");
      s.execute("PRAGMA temp_store=MEMORY");
      s.execute("PRAGMA cache_size=-4096");
      s.execute("CREATE TABLE mappings(k TEXT PRIMARY KEY,v BLOB NOT NULL)");
    }
    db.setAutoCommit(false);
  }

  private String key(String namespace, List<Object> values) throws Exception {
    return Base64.getEncoder()
        .encodeToString(
            mac.doFinal(
                (namespace
                        + "|"
                        + json.writeValueAsString(
                            values.stream().map(EncryptedKeyMap::encode).toList()))
                    .getBytes(StandardCharsets.UTF_8)));
  }

  void put(String namespace, List<Object> old, List<Object> values) throws Exception {
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, nonce));
    byte[] content =
        cipher.doFinal(
            json.writeValueAsBytes(values.stream().map(EncryptedKeyMap::encode).toList()));
    byte[] packed = new byte[nonce.length + content.length];
    System.arraycopy(nonce, 0, packed, 0, nonce.length);
    System.arraycopy(content, 0, packed, nonce.length, content.length);
    try (var s =
        db.prepareStatement(
            "INSERT INTO mappings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
      s.setString(1, key(namespace, old));
      s.setBytes(2, packed);
      s.executeUpdate();
    }
  }

  List<Object> get(String namespace, List<Object> old) throws Exception {
    try (var s = db.prepareStatement("SELECT v FROM mappings WHERE k=?")) {
      s.setString(1, key(namespace, old));
      try (var r = s.executeQuery()) {
        if (!r.next()) return null;
        byte[] packed = r.getBytes(1);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            new SecretKeySpec(secret, "AES"),
            new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
        var items =
            json.readValue(
                cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), String[].class);
        List<Object> result = new ArrayList<>();
        for (String item : items) result.add(decode(item));
        return result;
      }
    }
  }

  private static String encode(Object value) {
    if (value == null) return null;
    if (value instanceof Number n)
      return "N:" + new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
    if (value instanceof Timestamp t) return "T:" + t;
    if (value instanceof byte[] b) return "B:" + Base64.getEncoder().encodeToString(b);
    return "S:" + value;
  }

  private static Object decode(String value) {
    if (value == null) return null;
    return switch (value.substring(0, 2)) {
      case "N:" -> new BigDecimal(value.substring(2));
      case "T:" -> Timestamp.valueOf(value.substring(2));
      case "B:" -> Base64.getDecoder().decode(value.substring(2));
      default -> value.substring(2);
    };
  }

  public void close() {
    try {
      db.rollback();
      db.close();
    } catch (Exception ignored) {
    }
    Arrays.fill(secret, (byte) 0);
    try {
      Files.deleteIfExists(file);
    } catch (Exception ignored) {
      file.toFile().deleteOnExit();
    }
  }
}
