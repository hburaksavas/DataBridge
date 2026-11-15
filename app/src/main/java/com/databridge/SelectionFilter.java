package com.databridge;

import java.math.BigDecimal;
import java.util.*;

/** Small expression grammar; literals are always JDBC binds. No SQL functions or subqueries. */
final class SelectionFilter {
  record Compiled(String sql, List<Object> parameters) {}

  record Token(String kind, String value) {}

  private final List<Token> tokens = new ArrayList<>();
  private final List<Object> values = new ArrayList<>();
  private final Set<String> columns;
  private int position;

  private SelectionFilter(String input, Set<String> columns) {
    this.columns = columns;
    if (input.length() > 8000)
      throw new IllegalArgumentException("Filters must be 8,000 characters or shorter.");
    int i = 0;
    while (i < input.length()) {
      char c = input.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '\'') {
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        i++;
        while (i < input.length()) {
          char next = input.charAt(i++);
          if (next == '\'') {
            if (i < input.length() && input.charAt(i) == '\'') {
              value.append('\'');
              i++;
            } else {
              closed = true;
              break;
            }
          } else value.append(next);
        }
        if (!closed) throw bad();
        tokens.add(new Token("STRING", value.toString()));
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int start = i++;
        while (i < input.length()
            && (Character.isLetterOrDigit(input.charAt(i)) || "_$#".indexOf(input.charAt(i)) >= 0))
          i++;
        tokens.add(new Token("WORD", input.substring(start, i).toUpperCase(Locale.ROOT)));
        continue;
      }
      if (Character.isDigit(c) || c == '-' || c == '+') {
        int start = i++;
        while (i < input.length() && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.'))
          i++;
        String number = input.substring(start, i);
        try {
          new BigDecimal(number);
        } catch (Exception ex) {
          throw bad();
        }
        tokens.add(new Token("NUMBER", number));
        continue;
      }
      if ("(),".indexOf(c) >= 0) {
        tokens.add(new Token(String.valueOf(c), String.valueOf(c)));
        i++;
        continue;
      }
      if ("=<>!".indexOf(c) >= 0) {
        String op = String.valueOf(c);
        i++;
        if (i < input.length() && "=>".indexOf(input.charAt(i)) >= 0) op += input.charAt(i++);
        if (!Set.of("=", "<>", "!=", "<", ">", "<=", ">=").contains(op)) throw bad();
        tokens.add(new Token("OP", op));
        continue;
      }
      throw bad();
    }
    if (tokens.size() > 1000) throw bad();
    tokens.add(new Token("END", ""));
  }

  static Compiled compile(String condition, Set<String> columns) {
    var p = new SelectionFilter(condition == null ? "" : condition, columns);
    if (p.peek().kind.equals("END")) return new Compiled("1=1", List.of());
    String sql = p.or();
    if (!p.peek().kind.equals("END")) throw bad();
    return new Compiled(sql, List.copyOf(p.values));
  }

  private Token peek() {
    return tokens.get(position);
  }

  private boolean eat(String value) {
    if (peek().value.equals(value)) {
      position++;
      return true;
    }
    return false;
  }

  private void require(String value) {
    if (!eat(value)) throw bad();
  }

  private String or() {
    String sql = and();
    while (eat("OR")) sql = "(" + sql + " OR " + and() + ")";
    return sql;
  }

  private String and() {
    String sql = term();
    while (eat("AND")) sql = "(" + sql + " AND " + term() + ")";
    return sql;
  }

  private String term() {
    if (eat("NOT")) return "(NOT " + term() + ")";
    if (eat("(")) {
      String sql = or();
      require(")");
      return "(" + sql + ")";
    }
    Token name = peek();
    if (!name.kind.equals("WORD") || !columns.contains(name.value))
      throw new IllegalArgumentException(
          "Unknown column or unsupported filter expression: " + name.value);
    position++;
    String col = "t.\"" + name.value + "\"";
    if (eat("IS")) {
      boolean not = eat("NOT");
      require("NULL");
      return col + " IS " + (not ? "NOT " : "") + "NULL";
    }
    boolean not = eat("NOT");
    if (eat("IN")) {
      require("(");
      StringJoiner binds = new StringJoiner(",");
      binds.add(literal());
      while (eat(",")) binds.add(literal());
      require(")");
      return col + (not ? " NOT IN (" : " IN (") + binds + ")";
    }
    if (eat("LIKE")) return col + (not ? " NOT LIKE " : " LIKE ") + literal();
    if (not) throw bad();
    Token op = peek();
    if (!op.kind.equals("OP")) throw bad();
    position++;
    return col + " " + op.value + " " + literal();
  }

  private String literal() {
    Token t = peek();
    position++;
    switch (t.kind) {
      case "STRING" -> values.add(t.value);
      case "NUMBER" -> values.add(new BigDecimal(t.value));
      case "WORD" -> {
        if (!t.value.equals("DATE") || !peek().kind.equals("STRING")) throw bad();
        try {
          values.add(java.sql.Date.valueOf(java.time.LocalDate.parse(peek().value)));
          position++;
        } catch (Exception ex) {
          throw bad();
        }
      }
      default -> throw bad();
    }
    return "?";
  }

  private static IllegalArgumentException bad() {
    return new IllegalArgumentException(
        "Supported filters: column comparisons, AND/OR/NOT, IN, LIKE, IS NULL and DATE"
            + " 'YYYY-MM-DD'. SQL betikleri ve alt sorgular kabul edilmez.");
  }
}
