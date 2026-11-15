package com.databridge;

import java.math.BigDecimal;
import java.util.*;

public record KeyPolicy(
    String mode,
    String column,
    String sequence,
    BigDecimal start,
    BigDecimal increment,
    List<String> groupColumns,
    List<String> businessColumns,
    String onConflict) {
  public static KeyPolicy defaults() {
    return new KeyPolicy(
        "KEEP", "", "", BigDecimal.ONE, BigDecimal.ONE, List.of(), List.of(), "ERROR");
  }

  public KeyPolicy normalized() {
    String m = mode == null ? "KEEP" : mode;
    if (!Set.of("KEEP", "SEQUENCE", "IDENTITY", "MAX", "BUSINESS").contains(m))
      throw new IllegalArgumentException("Invalid key strategy.");
    String c = column == null || column.isBlank() ? "" : OracleService.identifier(column);
    String s = sequence == null || sequence.isBlank() ? "" : OracleService.identifier(sequence);
    if (Set.of("SEQUENCE", "IDENTITY", "MAX").contains(m) && c.isBlank())
      throw new IllegalArgumentException("A generated key column is required.");
    if (m.equals("SEQUENCE") && s.isBlank())
      throw new IllegalArgumentException("A target sequence name is required.");
    String conflict = onConflict == null ? "ERROR" : onConflict;
    if (!Set.of("ERROR", "SKIP").contains(conflict))
      throw new IllegalArgumentException("Invalid conflict behavior.");
    BigDecimal first = start == null ? BigDecimal.ONE : start,
        step = increment == null ? BigDecimal.ONE : increment;
    if (first.stripTrailingZeros().scale() > 0
        || step.stripTrailingZeros().scale() > 0
        || first.signum() < 0
        || step.signum() <= 0)
      throw new IllegalArgumentException(
          "Start and increment must be integers; increment must be positive.");
    List<String> groups = normalize(groupColumns), business = normalize(businessColumns);
    if (m.equals("BUSINESS") && business.isEmpty())
      throw new IllegalArgumentException("Business key columns are required.");
    return new KeyPolicy(m, c, s, first, step, groups, business, conflict);
  }

  private static List<String> normalize(List<String> list) {
    if (list == null) return List.of();
    if (list.size() > 16)
      throw new IllegalArgumentException("Select up to 16 key/grouping columns.");
    return list.stream()
        .filter(s -> s != null && !s.isBlank())
        .map(s -> OracleService.identifier(s.strip()))
        .distinct()
        .toList();
  }
}
