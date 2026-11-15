package com.databridge;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Applies PK policies and rewrites FK tuples using an encrypted disk-backed mapping. */
final class KeyTransferEngine implements AutoCloseable {
  record ForeignKey(String parent, List<String> childColumns, List<String> parentColumns) {}

  record Spec(
      String table,
      List<String> columns,
      List<String> pk,
      Map<String, String> types,
      List<ForeignKey> foreignKeys,
      KeyPolicy policy,
      long count) {}

  private final Connection target;
  private final String owner;
  private final Map<String, Spec> specs;
  private final EncryptedKeyMap mappings;
  private final Map<String, PreparedStatement> inserts = new HashMap<>();
  private final Map<String, ArrayDeque<BigDecimal>> sequenceValues = new HashMap<>();
  private final Map<String, Long> generated = new HashMap<>();
  private final Consumer<Statement> active;

  KeyTransferEngine(Connection target, String owner, List<Spec> tables, Consumer<Statement> active)
      throws Exception {
    this.target = target;
    this.owner = owner;
    this.active = active;
    specs = tables.stream().collect(Collectors.toMap(Spec::table, x -> x));
    mappings = new EncryptedKeyMap();
  }

  boolean row(String table, LinkedHashMap<String, Object> original) throws Exception {
    Spec spec = specs.get(table);
    KeyPolicy policy = spec.policy;
    LinkedHashMap<String, Object> values = new LinkedHashMap<>(original);
    for (ForeignKey fk : spec.foreignKeys) {
      List<Object> old = project(original, fk.childColumns);
      if (old.stream().anyMatch(Objects::isNull)) continue;
      if (!specs.containsKey(fk.parent)) continue;
      List<Object> mapped = mappings.get(namespace(fk.parent, fk.parentColumns), old);
      if (mapped == null)
        throw new IllegalArgumentException(
            "Selected parent mapping not found: " + table + " → " + fk.parent);
      for (int i = 0; i < fk.childColumns.size(); i++)
        values.put(fk.childColumns.get(i), mapped.get(i));
    }
    boolean inserted = true;
    if (policy.mode().equals("BUSINESS")) {
      if (project(values, policy.businessColumns()).stream().anyMatch(Objects::isNull))
        throw new IllegalArgumentException("A business key cannot be NULL: " + table);
      var found = find(spec, policy.businessColumns(), values);
      if (found == null)
        throw new IllegalArgumentException("No target row found for business key: " + table);
      values = found;
      inserted = false;
    } else {
      switch (policy.mode()) {
        case "SEQUENCE" -> values.put(policy.column(), nextSequence(spec));
        case "MAX" -> values.put(policy.column(), nextMaximum(spec, values));
        case "KEEP" -> {
          if (policy.onConflict().equals("SKIP")) {
            var found = find(spec, spec.pk, values);
            if (found != null) {
              values = found;
              inserted = false;
            }
          }
        }
        case "IDENTITY" -> {}
        default -> throw new IllegalArgumentException("Invalid key strategy.");
      }
      if (inserted) insert(spec, values);
    }
    Set<List<String>> references = new LinkedHashSet<>();
    references.add(spec.pk);
    for (Spec child : specs.values())
      for (ForeignKey fk : child.foreignKeys)
        if (fk.parent.equals(table)) references.add(fk.parentColumns);
    for (List<String> reference : references)
      mappings.put(
          namespace(table, reference), project(original, reference), project(values, reference));
    return inserted;
  }

  private void insert(Spec spec, LinkedHashMap<String, Object> values) throws SQLException {
    boolean identity = spec.policy.mode().equals("IDENTITY");
    List<String> columns =
        spec.columns.stream().filter(c -> !identity || !c.equals(spec.policy.column())).toList();
    PreparedStatement statement = inserts.get(spec.table);
    if (statement == null) {
      String sql =
          "INSERT INTO "
              + qualified(owner, spec.table)
              + " ("
              + columns.stream().map(KeyTransferEngine::q).collect(Collectors.joining(","))
              + ") VALUES ("
              + String.join(",", Collections.nCopies(columns.size(), "?"))
              + ")";
      statement =
          identity
              ? target.prepareStatement(sql, new String[] {spec.policy.column()})
              : target.prepareStatement(sql);
      statement.setQueryTimeout(60);
      inserts.put(spec.table, statement);
    }
    active.accept(statement);
    for (int i = 0; i < columns.size(); i++)
      bind(statement, i + 1, values.get(columns.get(i)), spec.types.get(columns.get(i)));
    if (statement.executeUpdate() != 1) throw new SQLException("Unable to insert a row with its key.");
    if (identity)
      try (var keys = statement.getGeneratedKeys()) {
        if (!keys.next()) throw new SQLException("Unable to retrieve the identity value.");
        values.put(spec.policy.column(), keys.getBigDecimal(1));
      }
  }

  private LinkedHashMap<String, Object> find(
      Spec spec, List<String> keys, Map<String, Object> values) throws SQLException {
    String where = keys.stream().map(c -> q(c) + " = ?").collect(Collectors.joining(" AND "));
    try (var statement =
        target.prepareStatement(
            "SELECT "
                + spec.columns.stream().map(KeyTransferEngine::q).collect(Collectors.joining(","))
                + " FROM "
                + qualified(owner, spec.table)
                + " WHERE "
                + where
                + " FETCH FIRST 2 ROWS ONLY")) {
      statement.setQueryTimeout(30);
      active.accept(statement);
      for (int i = 0; i < keys.size(); i++)
        bind(statement, i + 1, values.get(keys.get(i)), spec.types.get(keys.get(i)));
      try (var rs = statement.executeQuery()) {
        if (!rs.next()) return null;
        var found = read(rs, spec.columns, spec.types);
        if (rs.next())
          throw new IllegalArgumentException("Target mapping is not unique: " + spec.table);
        return found;
      }
    }
  }

  private BigDecimal nextSequence(Spec spec) throws SQLException {
    ArrayDeque<BigDecimal> pool =
        sequenceValues.computeIfAbsent(spec.table, k -> new ArrayDeque<>());
    if (pool.isEmpty()) {
      int size =
          (int) Math.min(500, Math.max(1, spec.count - generated.getOrDefault(spec.table, 0L)));
      try (var s =
          target.prepareStatement(
              "SELECT "
                  + qualified(owner, spec.policy.sequence())
                  + ".NEXTVAL FROM dual CONNECT BY LEVEL<=?")) {
        s.setInt(1, size);
        s.setQueryTimeout(30);
        active.accept(s);
        try (var r = s.executeQuery()) {
          while (r.next()) pool.add(r.getBigDecimal(1));
        }
      }
    }
    generated.merge(spec.table, 1L, Long::sum);
    return pool.removeFirst();
  }

  private BigDecimal nextMaximum(Spec spec, Map<String, Object> values) throws Exception {
    KeyPolicy p = spec.policy;
    List<Object> group = project(values, p.groupColumns());
    if (group.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("MAX+1 grup kolonu NULL olamaz: " + spec.table);
    String ns = spec.table + "|MAX|" + p.column() + "|" + String.join(",", p.groupColumns());
    List<Object> cached = mappings.get(ns, group);
    BigDecimal next;
    if (cached != null) next = (BigDecimal) cached.getFirst();
    else {
      String where =
          p.groupColumns().isEmpty()
              ? ""
              : " WHERE "
                  + p.groupColumns().stream()
                      .map(c -> q(c) + " = ?")
                      .collect(Collectors.joining(" AND "));
      try (var s =
          target.prepareStatement(
              "SELECT MAX(" + q(p.column()) + ") FROM " + qualified(owner, spec.table) + where)) {
        s.setQueryTimeout(30);
        active.accept(s);
        for (int i = 0; i < p.groupColumns().size(); i++)
          bind(s, i + 1, group.get(i), spec.types.get(p.groupColumns().get(i)));
        try (var r = s.executeQuery()) {
          r.next();
          BigDecimal max = r.getBigDecimal(1);
          next = max == null ? p.start() : max.add(p.increment());
        }
      }
    }
    mappings.put(ns, group, List.of(next.add(p.increment())));
    return next;
  }

  static LinkedHashMap<String, Object> read(
      ResultSet rs, List<String> names, Map<String, String> types) throws SQLException {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < names.size(); i++) {
      String type = types.get(names.get(i));
      Object value =
          switch (type) {
            case "NUMBER", "FLOAT" -> rs.getBigDecimal(i + 1);
            case "DATE", "TIMESTAMP" -> rs.getTimestamp(i + 1);
            case "RAW" -> rs.getBytes(i + 1);
            case "BINARY_FLOAT" -> {
              float v = rs.getFloat(i + 1);
              yield rs.wasNull() ? null : v;
            }
            case "BINARY_DOUBLE" -> {
              double v = rs.getDouble(i + 1);
              yield rs.wasNull() ? null : v;
            }
            case "NVARCHAR2", "NCHAR" -> rs.getNString(i + 1);
            default -> rs.getString(i + 1);
          };
      row.put(names.get(i), value);
    }
    return row;
  }

  private static void bind(PreparedStatement s, int i, Object value, String type)
      throws SQLException {
    if (type.equals("NVARCHAR2") || type.equals("NCHAR")) s.setNString(i, (String) value);
    else if (value instanceof byte[] b) s.setBytes(i, b);
    else s.setObject(i, value);
  }

  static List<ForeignKey> foreignKeys(Connection c, String owner, String table)
      throws SQLException {
    Map<String, List<String>> children = new LinkedHashMap<>(), parents = new LinkedHashMap<>();
    Map<String, String> tables = new LinkedHashMap<>();
    try (var s =
        c.prepareStatement(
            "SELECT fk.constraint_name,pk.table_name,fc.column_name,pc.column_name FROM"
                + " all_constraints fk JOIN all_constraints pk ON pk.owner=fk.r_owner AND"
                + " pk.constraint_name=fk.r_constraint_name JOIN all_cons_columns fc ON"
                + " fc.owner=fk.owner AND fc.constraint_name=fk.constraint_name JOIN"
                + " all_cons_columns pc ON pc.owner=pk.owner AND"
                + " pc.constraint_name=pk.constraint_name AND pc.position=fc.position WHERE"
                + " fk.owner=? AND fk.table_name=? AND fk.constraint_type='R' ORDER BY"
                + " fk.constraint_name,fc.position")) {
      s.setString(1, owner);
      s.setString(2, table);
      try (var r = s.executeQuery()) {
        while (r.next()) {
          String key = r.getString(1);
          tables.put(key, r.getString(2));
          children.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getString(3));
          parents.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getString(4));
        }
      }
    }
    return tables.keySet().stream()
        .map(
            k ->
                new ForeignKey(
                    tables.get(k), List.copyOf(children.get(k)), List.copyOf(parents.get(k))))
        .toList();
  }

  static void validate(Connection c, String owner, Spec spec) throws SQLException {
    KeyPolicy p = spec.policy;
    Set<String> columns = new HashSet<>(spec.columns);
    if (!columns.containsAll(p.groupColumns()) || !columns.containsAll(p.businessColumns()))
      throw new IllegalArgumentException("Unknown column in key strategy: " + spec.table);
    if (Set.of("MAX", "SEQUENCE", "IDENTITY").contains(p.mode())) {
      if (!spec.pk.contains(p.column()) || !"NUMBER".equals(spec.types.get(p.column())))
        throw new IllegalArgumentException(
            "The generated column must be a numeric primary key component: " + spec.table);
      if (spec.foreignKeys.stream().anyMatch(fk -> fk.childColumns.contains(p.column())))
        throw new IllegalArgumentException(
            "A key component mapped from a parent cannot also be generated: " + spec.table + "." + p.column());
    }
    if (p.mode().equals("MAX")
        && (!spec.pk.containsAll(p.groupColumns()) || p.groupColumns().contains(p.column())))
      throw new IllegalArgumentException("MAX grouping columns must be other primary key components.");
    if (p.mode().equals("SEQUENCE"))
      try (var s =
          c.prepareStatement(
              "SELECT COUNT(*) FROM all_sequences WHERE sequence_owner=? AND sequence_name=?")) {
        s.setString(1, owner);
        s.setString(2, p.sequence());
        try (var r = s.executeQuery()) {
          r.next();
          if (r.getInt(1) != 1)
            throw new IllegalArgumentException("Target sequence not found: " + p.sequence());
        }
      }
    if (p.mode().equals("IDENTITY"))
      try (var s =
          c.prepareStatement(
              "SELECT COUNT(*) FROM all_tab_identity_cols WHERE owner=? AND table_name=? AND"
                  + " column_name=?")) {
        s.setString(1, owner);
        s.setString(2, spec.table);
        s.setString(3, p.column());
        try (var r = s.executeQuery()) {
          r.next();
          if (r.getInt(1) != 1)
            throw new IllegalArgumentException("The target column is not an identity: " + spec.table);
        }
      }
    if (p.mode().equals("BUSINESS")) {
      Set<String> actual = new HashSet<>(p.businessColumns());
      Map<String, Set<String>> unique = new HashMap<>();
      try (var s =
          c.prepareStatement(
              "SELECT k.constraint_name,cc.column_name FROM all_constraints k JOIN all_cons_columns"
                  + " cc ON cc.owner=k.owner AND cc.constraint_name=k.constraint_name WHERE"
                  + " k.owner=? AND k.table_name=? AND k.constraint_type IN ('P','U') AND"
                  + " k.status='ENABLED' AND k.validated='VALIDATED'")) {
        s.setString(1, owner);
        s.setString(2, spec.table);
        try (var r = s.executeQuery()) {
          while (r.next())
            unique.computeIfAbsent(r.getString(1), k -> new HashSet<>()).add(r.getString(2));
        }
      }
      if (unique.values().stream().noneMatch(actual::equals))
        throw new IllegalArgumentException(
            "Business key columns must have an enabled PK/UNIQUE constraint in the target: " + spec.table);
    }
  }

  private static List<Object> project(Map<String, Object> values, List<String> columns) {
    List<Object> result = new ArrayList<>();
    for (String c : columns) result.add(values.get(c));
    return result;
  }

  private static String namespace(String table, List<String> columns) {
    return table + "|KEY|" + String.join(",", columns);
  }

  private static String q(String name) {
    return "\"" + OracleService.identifier(name) + "\"";
  }

  private static String qualified(String owner, String name) {
    return q(owner) + "." + q(name);
  }

  public void close() {
    inserts
        .values()
        .forEach(
            s -> {
              try {
                s.close();
              } catch (Exception ignored) {
              }
            });
    mappings.close();
  }
}
