package com.databridge;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class SelectionFilterTest {
  private final Set<String> columns = Set.of("DURUM", "TUTAR", "ID", "AD", "TARIH");

  @Test
  void bindsAllLiteralsAndPreservesBooleanGrouping() {
    var f =
        SelectionFilter.compile("DURUM = 'AKTIF' AND (TUTAR >= 100.50 OR ID IN (1,2))", columns);
    assertEquals(
        List.of("AKTIF", new BigDecimal("100.50"), new BigDecimal("1"), new BigDecimal("2")),
        f.parameters());
    assertFalse(f.sql().contains("AKTIF"));
    assertTrue(f.sql().contains("t.\"DURUM\" = ?"));
  }

  @Test
  void quotedContentIsDataNotSql() {
    var f = SelectionFilter.compile("AD='O''Brien; DROP TABLE X'", columns);
    assertEquals(List.of("O'Brien; DROP TABLE X"), f.parameters());
    assertFalse(f.sql().contains("DROP"));
  }

  @Test
  void rejectsStatementsSubqueriesFunctionsAndUnknownColumns() {
    for (String sql :
        List.of(
            "ID=1; delete from X",
            "ID IN (SELECT ID FROM X)",
            "DURUM='A' OR 1=1",
            "UNKNOWN=1",
            "UPPER(AD)='A'",
            "ID=1 --comment"))
      assertThrows(
          IllegalArgumentException.class, () -> SelectionFilter.compile(sql, columns), sql);
  }

  @Test
  void nullNegationAndDateAreTyped() {
    var f =
        SelectionFilter.compile(
            "NOT (AD IS NULL) AND TARIH >= DATE '2026-01-01' AND ID NOT IN (1,2)", columns);
    assertInstanceOf(java.sql.Date.class, f.parameters().getFirst());
    assertTrue(f.sql().contains("NOT IN"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SelectionFilter.compile("TARIH = DATE '2026-02-31'", columns));
  }
}
