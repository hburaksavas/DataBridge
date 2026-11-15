package com.databridge;

import java.sql.*;
import java.util.*;
import oracle.jdbc.pool.OracleDataSource;
import org.springframework.stereotype.Service;

@Service
public class OracleService {
  private final ProfileStore store;

  public OracleService(ProfileStore store) {
    this.store = store;
  }

  Connection open(ConnectionProfile input, String supplied) throws SQLException {
    var p = input.normalized();
    if (p.username().isBlank()) throw new IllegalArgumentException("A username is required.");
    String password =
        supplied != null && !supplied.isEmpty()
            ? supplied
            : (input.id() == null ? "" : store.password(input.id()));
    if (password.isEmpty())
      throw new IllegalArgumentException("Enter a password or use a saved password.");
    OracleDataSource ds = new OracleDataSource();
    ds.setURL(p.jdbcUrl());
    ds.setUser(p.username());
    ds.setPassword(password);
    ds.setLoginTimeout(10);
    Properties props = new Properties();
    props.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
    props.setProperty("oracle.jdbc.ReadTimeout", "120000");
    if (!p.role().equals("NORMAL"))
      props.setProperty("internal_logon", p.role().toLowerCase(Locale.ROOT));
    ds.setConnectionProperties(props);
    try {
      return ds.getConnection();
    } catch (SQLException e) {
      throw new ConnectionFailure(p.name(), e);
    }
  }

  static final class ConnectionFailure extends SQLException {
    final String connectionName;

    ConnectionFailure(String name, SQLException cause) {
      super("Oracle connection failed", cause.getSQLState(), cause.getErrorCode(), cause);
      connectionName = name;
    }
  }

  void readOnly(Connection c) throws SQLException {
    c.setAutoCommit(false);
    try (var s = c.createStatement()) {
      s.execute("SET TRANSACTION READ ONLY");
    }
  }

  public Map<String, Object> test(ConnectionProfile p, String password) throws SQLException {
    long start = System.nanoTime();
    try (var c = open(p, password)) {
      readOnly(c);
      try (var s = c.createStatement()) {
        s.setQueryTimeout(10);
        try (var rs =
            s.executeQuery(
                "SELECT SYS_CONTEXT('USERENV','DB_NAME'), SYS_CONTEXT('USERENV','SESSION_USER'),"
                    + " SYS_CONTEXT('USERENV','SERVICE_NAME') FROM dual")) {
          rs.next();
          return Map.of(
              "ok",
              true,
              "database",
              Objects.toString(rs.getString(1), ""),
              "user",
              Objects.toString(rs.getString(2), ""),
              "service",
              Objects.toString(rs.getString(3), ""),
              "elapsedMs",
              (System.nanoTime() - start) / 1_000_000);
        }
      }
    }
  }

  public List<Map<String, Object>> tables(String id, String password, String schema)
      throws SQLException {
    var p = store.get(id);
    try (var c = open(p, password)) {
      readOnly(c);
      return accessibleTables(c, schema);
    }
  }

  // Oracle-maintained owners and internal storage tables are not transfer candidates.
  private static final String APPLICATION_TABLES =
      "SELECT t.owner, t.table_name, t.num_rows FROM all_tables t"
          + " JOIN all_users u ON u.username=t.owner"
          + " WHERE u.oracle_maintained='N' AND t.nested='NO'"
          + " AND t.secondary='N' AND (t.iot_type IS NULL OR t.iot_type='IOT')"
          + " AND t.table_name NOT LIKE 'BIN$%'";

  List<Map<String, Object>> accessibleTables(Connection c, String schema) throws SQLException {
    if (schema == null || schema.isBlank())
      return query(c, APPLICATION_TABLES + " ORDER BY t.owner, t.table_name");
    return query(c, APPLICATION_TABLES + " AND t.owner=? ORDER BY t.table_name",
        identifier(schema.trim()));
  }

  void requireApplicationTable(Connection c, String owner, String table) throws SQLException {
    if (query(c, APPLICATION_TABLES + " AND t.owner=? AND t.table_name=?", owner, table)
        .isEmpty())
      throw new IllegalArgumentException(
          "Table not found, not accessible, or is an Oracle system table: " + owner + "." + table);
  }

  public Map<String, Object> discover(String id, String password, String schema, String table)
      throws SQLException {
    var p = store.get(id);
    String owner = identifier(schema), name = identifier(table);
    try (var c = open(p, password)) {
      readOnly(c);
      requireApplicationTable(c, owner, name);
      var columns =
          query(
              c,
              "SELECT column_name, data_type, nullable, column_id FROM all_tab_columns WHERE"
                  + " owner=? AND table_name=? ORDER BY column_id",
              owner,
              name);
      if (columns.isEmpty())
        throw new IllegalArgumentException("Table not found or not accessible.");
      String sql =
          """
          SELECT fk.owner AS child_owner, fk.table_name AS child_table, fk.constraint_name,
                 fc.column_name AS child_column, pk.owner AS parent_owner, pk.table_name AS parent_table,
                 pc.column_name AS parent_column, fc.position, fk.status, fk.validated
          FROM all_constraints fk
          JOIN all_constraints pk ON pk.owner=fk.r_owner AND pk.constraint_name=fk.r_constraint_name
          JOIN all_cons_columns fc ON fc.owner=fk.owner AND fc.constraint_name=fk.constraint_name
          JOIN all_cons_columns pc ON pc.owner=pk.owner AND pc.constraint_name=pk.constraint_name AND pc.position=fc.position
          WHERE fk.constraint_type='R' AND ((fk.owner=? AND fk.table_name=?) OR (pk.owner=? AND pk.table_name=?))
          ORDER BY fk.owner,fk.constraint_name,fc.position
          """;
      var raw = query(c, sql, owner, name, owner, name);
      Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
      for (var r : raw) {
        String key = r.get("CHILD_OWNER") + "." + r.get("CONSTRAINT_NAME");
        var group =
            groups.computeIfAbsent(
                key,
                k -> {
                  Map<String, Object> v = new LinkedHashMap<>(r);
                  v.remove("CHILD_COLUMN");
                  v.remove("PARENT_COLUMN");
                  v.remove("POSITION");
                  v.put("COLUMN_PAIRS", new ArrayList<Map<String, Object>>());
                  v.put("SOURCE", "FK");
                  v.put(
                      "DIRECTION",
                      r.get("CHILD_OWNER").equals(owner) && r.get("CHILD_TABLE").equals(name)
                          ? "PARENT"
                          : "CHILD");
                  return v;
                });
        @SuppressWarnings("unchecked")
        var pairs = (List<Map<String, Object>>) group.get("COLUMN_PAIRS");
        pairs.add(Map.of("child", r.get("CHILD_COLUMN"), "parent", r.get("PARENT_COLUMN")));
      }
      return Map.of(
          "owner",
          owner,
          "table",
          name,
          "columns",
          columns,
          "primaryKey",
          KeyMetadata.primaryKey(c, owner, name),
          "relations",
          groups.values(),
          "warnings",
          List.of(
              "Only direct foreign keys were discovered. Custom relationships are not automatically"
                  + " inferred. Row counts are based on ALL_TABLES statistics."));
    }
  }

  static String identifier(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_$#]{0,127}"))
      throw new IllegalArgumentException("Invalid schema or table name.");
    return value.toUpperCase(Locale.ROOT);
  }

  private List<Map<String, Object>> query(Connection c, String sql, Object... params)
      throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      s.setQueryTimeout(20);
      for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
      try (var rs = s.executeQuery()) {
        List<Map<String, Object>> data = new ArrayList<>();
        var md = rs.getMetaData();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= md.getColumnCount(); i++)
            row.put(md.getColumnLabel(i), rs.getObject(i));
          data.add(row);
        }
        return data;
      }
    }
  }
}
