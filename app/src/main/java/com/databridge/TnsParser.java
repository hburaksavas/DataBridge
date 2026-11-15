package com.databridge;

import java.util.*;
import java.util.regex.*;

public final class TnsParser {
  public record Alias(String alias, String descriptor, String host, int port, String service) {}

  public record Result(List<Alias> aliases, List<String> warnings) {}

  public Result parse(String input) {
    String s = stripComments(input.replace("\uFEFF", ""));
    List<Alias> aliases = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    int pos = 0;
    while (pos < s.length()) {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
      if (pos >= s.length()) break;
      int equals = s.indexOf('=', pos);
      if (equals < 0) {
        if (!s.substring(pos).isBlank())
          throw new IllegalArgumentException("Expected '=' in TNS definition.");
        break;
      }
      String names = s.substring(pos, equals).strip();
      int start = equals + 1;
      while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
      if (names.equalsIgnoreCase("IFILE")) {
        int end = s.indexOf('\n', start);
        warnings.add("IFILE was not read automatically; import the referenced file separately.");
        pos = end < 0 ? s.length() : end + 1;
        continue;
      }
      if (start >= s.length() || s.charAt(start) != '(')
        throw new IllegalArgumentException("A TNS descriptor must start with '(': " + names);
      int depth = 0, end = start;
      char quote = 0;
      for (; end < s.length(); end++) {
        char ch = s.charAt(end);
        if (quote != 0) {
          if (ch == quote) quote = 0;
          continue;
        }
        if (ch == '\"' || ch == '\'') {
          quote = ch;
          continue;
        }
        if (ch == '(') depth++;
        if (ch == ')' && --depth == 0) {
          end++;
          break;
        }
      }
      if (depth != 0 || quote != 0)
        throw new IllegalArgumentException("Incomplete TNS descriptor: " + names);
      String desc = s.substring(start, end);
      String host = extract(desc, "HOST"), service = extract(desc, "SERVICE_NAME");
      if (service.isEmpty()) service = extract(desc, "SID");
      int port = 1521;
      try {
        String p = extract(desc, "PORT");
        if (!p.isEmpty()) port = Integer.parseInt(p);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid TNS port.");
      }
      for (String name : names.split(",")) {
        String alias = name.strip();
        if (!alias.matches("[a-zA-Z0-9_.#$-]+"))
          throw new IllegalArgumentException("Invalid TNS alias name.");
        if (aliases.stream().anyMatch(a -> a.alias.equalsIgnoreCase(alias)))
          throw new IllegalArgumentException("Tekrarlanan TNS alias: " + alias);
        aliases.add(new Alias(alias, desc, host, port, service));
      }
      pos = end;
    }
    if (aliases.isEmpty()) warnings.add("No connection aliases found in the file.");
    return new Result(aliases, warnings);
  }

  private static String extract(String s, String key) {
    Matcher m = Pattern.compile("(?i)\\(\\s*" + key + "\\s*=\\s*([^()]+)\\)").matcher(s);
    return m.find() ? m.group(1).strip().replaceAll("^[\"']|[\"']$", "") : "";
  }

  private static String stripComments(String s) {
    StringBuilder b = new StringBuilder();
    boolean comment = false;
    char quote = 0;
    for (char c : s.toCharArray()) {
      if (comment) {
        if (c == '\n') {
          comment = false;
          b.append(c);
        }
        continue;
      }
      if (quote != 0) {
        b.append(c);
        if (c == quote) quote = 0;
      } else if (c == '\"' || c == '\'') {
        quote = c;
        b.append(c);
      } else if (c == '#') comment = true;
      else b.append(c);
    }
    return b.toString();
  }
}
