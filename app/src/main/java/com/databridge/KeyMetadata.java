package com.databridge;

import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;

@Service
public class KeyMetadata {
  private final ProfileStore store;
  private final OracleService oracle;

  public KeyMetadata(ProfileStore store, OracleService oracle) {
    this.store = store;
    this.oracle = oracle;
  }

  public record OptionsRequest(String connectionId, String password, List<String> tables) {}

  public Map<String, Object> options(OptionsRequest request) throws SQLException {
    if (request.tables == null || request.tables.size() > 25)
      throw new IllegalArgumentException("En fazla 25 tablo incelenebilir.");
    try (var c = oracle.open(store.get(request.connectionId), request.password)) {
      oracle.readOnly(c);
      String owner;
      try (var s = c.createStatement();
          var r = s.executeQuery("SELECT SYS_CONTEXT('USERENV','SESSION_USER') FROM dual")) {
        r.next();
        owner = r.getString(1);
      }
      List<String> sequences = new ArrayList<>();
      try (var s =
          c.prepareStatement(
              "SELECT sequence_name FROM all_sequences WHERE sequence_owner=? ORDER BY"
                  + " sequence_name")) {
        s.setString(1, owner);
        try (var r = s.executeQuery()) {
          while (r.next()) sequences.add(r.getString(1));
        }
      }
      Map<String, Object> result = new LinkedHashMap<>();
      for (String raw : request.tables) {
        String table = OracleService.identifier(raw);
        List<String> pk = primaryKey(c, owner, table);
        List<Map<String, Object>> columns = new ArrayList<>();
        Map<String, String> defaults = new HashMap<>(), identity = new HashMap<>();
        try (var s =
            c.prepareStatement(
                "SELECT column_name,data_type,data_scale,data_default FROM all_tab_columns WHERE"
                    + " owner=? AND table_name=? ORDER BY column_id")) {
          s.setString(1, owner);
          s.setString(2, table);
          try (var r = s.executeQuery()) {
            while (r.next()) {
              String col = r.getString(1);
              columns.add(
                  Map.of(
                      "name",
                      col,
                      "type",
                      r.getString(2),
                      "integer",
                      r.getString(2).equals("NUMBER")
                          && (r.getString(3) == null || r.getInt(3) == 0)));
              defaults.put(col, Objects.toString(r.getString(4), ""));
            }
          }
        }
        try (var s =
            c.prepareStatement(
                "SELECT column_name,sequence_name FROM all_tab_identity_cols WHERE owner=? AND"
                    + " table_name=?")) {
          s.setString(1, owner);
          s.setString(2, table);
          try (var r = s.executeQuery()) {
            while (r.next()) identity.put(r.getString(1), r.getString(2));
          }
        }
        StringBuilder triggers = new StringBuilder();
        try (var s =
            c.prepareStatement(
                "SELECT trigger_body FROM all_triggers WHERE table_owner=? AND table_name=? AND"
                    + " status='ENABLED'")) {
          s.setString(1, owner);
          s.setString(2, table);
          try (var r = s.executeQuery()) {
            while (r.next()) triggers.append(r.getString(1)).append('\n');
          }
        }
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (String col : pk) {
          if (identity.containsKey(col))
            suggestions.add(
                Map.of(
                    "column",
                    col,
                    "mode",
                    "IDENTITY",
                    "sequence",
                    identity.get(col),
                    "confidence",
                    "HIGH",
                    "reason",
                    "Oracle identity definition"));
          for (String seq : sequences) {
            if (seq.equals(identity.get(col))) continue;
            String reason = "", confidence = "MEDIUM";
            if (uses(defaults.getOrDefault(col, ""), seq)) {
              reason = "NEXTVAL in column DEFAULT";
              confidence = "HIGH";
            } else if (uses(triggers.toString(), seq)
                && Pattern.compile("(?i):\\s*NEW\\s*\\.\\s*\"?" + Pattern.quote(col) + "\"?")
                    .matcher(triggers)
                    .find()) {
              reason = "Sequence and column appear in a trigger; verify its conditions";
              confidence = "HIGH";
            } else if (Set.of(
                    "SEQ_" + table,
                    table + "_SEQ",
                    "SQ_" + table,
                    table + "_SQ",
                    table + "_" + col + "_SEQ",
                    "SEQ_" + table + "_" + col)
                .contains(seq)) {
              reason = "Table/column name match";
            }
            if (!reason.isEmpty())
              suggestions.add(
                  Map.of(
                      "column",
                      col,
                      "mode",
                      "SEQUENCE",
                      "sequence",
                      seq,
                      "confidence",
                      confidence,
                      "reason",
                      reason));
          }
        }
        result.put(
            table,
            Map.of(
                "primaryKey",
                pk,
                "columns",
                columns,
                "identities",
                identity,
                "suggestions",
                suggestions));
      }
      return Map.of("owner", owner, "sequences", sequences, "tables", result);
    }
  }

  private static boolean uses(String text, String sequence) {
    return Pattern.compile(
            "(?i)(?<![A-Z0-9_$#])\"?"
                + Pattern.quote(sequence)
                + "\"?\\s*\\.\\s*\"?NEXTVAL\"?(?![A-Z0-9_$#])")
        .matcher(text)
        .find();
  }

  static List<String> primaryKey(Connection c, String owner, String table) throws SQLException {
    List<String> keys = new ArrayList<>();
    try (var s =
        c.prepareStatement(
            "SELECT cc.column_name FROM all_constraints k JOIN all_cons_columns cc ON"
                + " cc.owner=k.owner AND cc.constraint_name=k.constraint_name WHERE k.owner=? AND"
                + " k.table_name=? AND k.constraint_type='P' AND k.status='ENABLED' ORDER BY"
                + " cc.position")) {
      s.setString(1, owner);
      s.setString(2, table);
      try (var r = s.executeQuery()) {
        while (r.next()) keys.add(r.getString(1));
      }
    }
    return keys;
  }
}
