package com.databridge;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Readable INI/XML metadata adapter. Proprietary encrypted exports are explicitly rejected. */
public final class ConnectionImporter {
  public record Preview(List<ConnectionProfile> profiles, List<String> warnings) {}

  public static String decode(byte[] data) {
    if (data.length > 5_000_000) throw new IllegalArgumentException("The file exceeds the 5 MB limit.");
    if (data.length >= 2 && data[0] == (byte) 0xff && data[1] == (byte) 0xfe)
      return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
    if (data.length >= 2 && data[0] == (byte) 0xfe && data[1] == (byte) 0xff)
      return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data))
          .toString()
          .replace("\uFEFF", "");
    } catch (CharacterCodingException ex) {
      return new String(data, Charset.forName("windows-1254"));
    }
  }

  public Preview parse(byte[] bytes) {
    String text = decode(bytes).strip();
    if (text.indexOf('\0') >= 0)
      throw new IllegalArgumentException(
          "Binary or encrypted files are not supported. A readable connection definition is required.");
    List<Map<String, String>> records = text.startsWith("<") ? xml(text) : ini(text);
    List<ConnectionProfile> profiles = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    for (var r : records) {
      String database =
          get(r, "database", "logondatabase", "connectstring", "connectidentifier", "tnsalias");
      String user = get(r, "username", "logonusername", "user");
      if (database.isEmpty()) continue;
      String name = get(r, "name", "displayname", "description");
      if (name.isEmpty()) name = (user.isEmpty() ? "" : user + "@") + database;
      String folder = get(r, "folder", "foldername", "path");
      String descriptor = database.startsWith("(") ? database : "";
      String role = get(r, "connectas", "role", "logonrole").toUpperCase(Locale.ROOT);
      if (role.isEmpty() || role.equals("0")) role = "NORMAL";
      if (role.equals("1")) role = "SYSDBA";
      if (role.equals("2")) role = "SYSOPER";
      if (!Set.of("NORMAL", "SYSDBA", "SYSOPER").contains(role)) {
        warnings.add(name + ": connection role imported as NORMAL.");
        role = "NORMAL";
      }
      profiles.add(
          new ConnectionProfile(
              null,
              name,
              folder.isEmpty() ? "PLSQL Developer" : folder,
              "TEST",
              user,
              descriptor.isEmpty() ? "TNS" : "DESCRIPTOR",
              "",
              1521,
              "",
              descriptor.isEmpty() ? database : "",
              descriptor,
              role,
              "#386ce0",
              false,
              false,
              "PLSQL"));
    }
    if (profiles.isEmpty())
      throw new IllegalArgumentException(
          "No readable connections found. This PL/SQL Developer export format is not yet"
              + " supported; an anonymized sample is needed to add support.");
    warnings.add(
        "Compatibility adapter: readable INI/XML fields imported. Verify compatibility using an export"
            + " from your PL/SQL Developer version.");
    warnings.add(
        "Passwords and startup SQL scripts were excluded. Resolve TNS aliases with tnsnames.ora"
            + " and verify the environment label.");
    return new Preview(profiles, warnings);
  }

  private static String get(Map<String, String> row, String... keys) {
    for (String key : keys) {
      String value = row.get(key);
      if (value != null && !value.isBlank()) return value.strip();
    }
    return "";
  }

  private static List<Map<String, String>> ini(String text) {
    List<Map<String, String>> result = new ArrayList<>();
    Map<String, String> current = new LinkedHashMap<>();
    Map<String, Map<String, String>> indexed = new LinkedHashMap<>();
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
      if (line.startsWith("[") && line.endsWith("]")) {
        if (!current.isEmpty()) result.add(current);
        result.addAll(indexed.values());
        current = new LinkedHashMap<>();
        indexed.clear();
        continue;
      }
      int eq = line.indexOf('=');
      if (eq < 1) continue;
      String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT).replace(" ", "");
      String value = line.substring(eq + 1).strip();
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
        value = value.substring(1, value.length() - 1);
      var suffix = java.util.regex.Pattern.compile("^([a-z]+)(\\d+)$").matcher(key);
      if (suffix.matches())
        indexed
            .computeIfAbsent(suffix.group(2), k -> new LinkedHashMap<>())
            .put(suffix.group(1), value);
      else current.put(key, value);
    }
    if (!current.isEmpty()) result.add(current);
    result.addAll(indexed.values());
    return result;
  }

  private static List<Map<String, String>> xml(String text) {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      var doc =
          factory
              .newDocumentBuilder()
              .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
      List<Map<String, String>> records = new ArrayList<>();
      var nodes = doc.getElementsByTagName("*");
      for (int i = 0; i < nodes.getLength(); i++) {
        Element element = (Element) nodes.item(i);
        if (!Set.of("connection", "item", "logon")
            .contains(element.getTagName().toLowerCase(Locale.ROOT))) continue;
        Map<String, String> row = new LinkedHashMap<>();
        for (int a = 0; a < element.getAttributes().getLength(); a++) {
          Node attr = element.getAttributes().item(a);
          row.put(attr.getNodeName().toLowerCase(Locale.ROOT), attr.getNodeValue());
        }
        for (int c = 0; c < element.getChildNodes().getLength(); c++) {
          Node child = element.getChildNodes().item(c);
          if (child instanceof Element ce) {
            if (ce.hasAttribute("name") && ce.hasAttribute("value"))
              row.put(ce.getAttribute("name").toLowerCase(Locale.ROOT), ce.getAttribute("value"));
            else row.put(ce.getTagName().toLowerCase(Locale.ROOT), ce.getTextContent());
          }
        }
        records.add(row);
      }
      return records;
    } catch (Exception ex) {
      throw new IllegalArgumentException(
          "The XML file could not be read or parsed securely.");
    }
  }
}
