package com.databridge;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class KeyPolicyTest {
  @Test
  void defaultsAreFailOnCollision() {
    assertEquals("ERROR", KeyPolicy.defaults().onConflict());
    assertEquals("KEEP", KeyPolicy.defaults().mode());
  }

  @Test
  void validatesGeneratedNamesAndIntegerIncrements() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KeyPolicy("SEQUENCE", "ID", "SEQ_X;DELETE", null, null, null, null, null)
                .normalized());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KeyPolicy("MAX", "ID", "", BigDecimal.ONE, new BigDecimal("0.5"), null, null, null)
                .normalized());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KeyPolicy("MAX", "ID", "", BigDecimal.ONE, BigDecimal.ZERO, null, null, null)
                .normalized());
  }

  @Test
  void encryptedMappingPreservesCompositeTypesAndHidesKeys() throws Exception {
    try (var map = new EncryptedKeyMap()) {
      List<Object> old = List.of(new BigDecimal("1.00"), "PRIVATE-KEY-SENTINEL");
      var mapped =
          List.<Object>of(
              new BigDecimal("900719925474099312345.25"),
              java.sql.Timestamp.valueOf("2026-11-15 01:02:03.123456789"));
      map.put("TEST", old, mapped);
      var result = map.get("TEST", List.of(BigDecimal.ONE, "PRIVATE-KEY-SENTINEL"));
      assertEquals(mapped, result);
      assertNull(map.get("OTHER", old));
      var field = EncryptedKeyMap.class.getDeclaredField("file");
      field.setAccessible(true);
      String bytes =
          new String(
              java.nio.file.Files.readAllBytes((java.nio.file.Path) field.get(map)),
              java.nio.charset.StandardCharsets.ISO_8859_1);
      assertFalse(bytes.contains("PRIVATE-KEY-SENTINEL"));
    }
  }
}
