package com.databridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProfileStore {
  private final JdbcTemplate db;
  private final ObjectMapper mapper;

  public ProfileStore(JdbcTemplate db, ObjectMapper mapper) {
    this.db = db;
    this.mapper = mapper;
    db.execute(
        "CREATE TABLE IF NOT EXISTS connections(id TEXT PRIMARY KEY, profile TEXT NOT NULL, secret"
            + " BLOB)");
    db.execute("CREATE TABLE IF NOT EXISTS scenarios(id TEXT PRIMARY KEY, body TEXT NOT NULL)");
    db.execute("CREATE TABLE IF NOT EXISTS transfers(id TEXT PRIMARY KEY, body TEXT NOT NULL)");
  }

  public List<ConnectionProfile> all() {
    return db.query(
        "SELECT profile, secret IS NOT NULL AS saved FROM connections ORDER BY"
            + " json_extract(profile,'$.folder'),json_extract(profile,'$.name')",
        (r, n) -> withSaved(parse(r.getString(1)), r.getBoolean(2)));
  }

  ConnectionProfile withSaved(ConnectionProfile p, boolean saved) {
    return new ConnectionProfile(
        p.id(),
        p.name(),
        p.folder(),
        p.environment(),
        p.username(),
        p.mode(),
        p.host(),
        p.port(),
        p.service(),
        p.database(),
        p.descriptor(),
        p.role(),
        p.color(),
        p.readOnly(),
        saved,
        p.origin());
  }

  public ConnectionProfile get(String id) {
    return all().stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Connection not found."));
  }

  private ConnectionProfile parse(String json) {
    try {
      return mapper.readValue(json, ConnectionProfile.class);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to read the connection.");
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to create the record.");
    }
  }

  public synchronized ConnectionProfile save(
      ConnectionProfile input, String password, boolean remember, boolean clearPassword) {
    var p = input.normalized();
    byte[] secret = null;
    if (!clearPassword) {
      var existing =
          db.query("SELECT secret FROM connections WHERE id=?", (r, n) -> r.getBytes(1), p.id());
      if (!existing.isEmpty()) secret = existing.getFirst();
    }
    if (password != null && !password.isEmpty() && remember)
      secret = Crypt32Util.cryptProtectData(password.getBytes(StandardCharsets.UTF_8));
    if (!remember) secret = null;
    db.update(
        "INSERT INTO connections(id,profile,secret) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " profile=excluded.profile,secret=excluded.secret",
        p.id(),
        json(withSaved(p, false)),
        secret);
    return withSaved(p, secret != null);
  }

  public String password(String id) {
    var data = db.query("SELECT secret FROM connections WHERE id=?", (r, n) -> r.getBytes(1), id);
    if (data.isEmpty() || data.getFirst() == null) return "";
    byte[] clear = Crypt32Util.cryptUnprotectData(data.getFirst());
    try {
      return new String(clear, StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(clear, (byte) 0);
    }
  }

  public void delete(String id) {
    db.update("DELETE FROM connections WHERE id=?", id);
  }

  public List<Map<String, Object>> scenarios() {
    return db.query(
        "SELECT body FROM scenarios",
        (r, n) -> {
          try {
            return mapper.readValue(
                r.getString(1),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
          } catch (Exception e) {
            throw new IllegalStateException("Unable to read the scenario.");
          }
        });
  }

  @org.springframework.transaction.annotation.Transactional
  public synchronized Map<String, Object> saveScenario(Map<String, Object> data) {
    String id = Objects.toString(data.get("id"), UUID.randomUUID().toString());
    String name = Objects.toString(data.get("name"), "").strip();
    if (name.isBlank()) throw new IllegalArgumentException("A scenario name is required.");
    String key = scenarioNameKey(name);
    for (var existing : scenarios()) {
      if (!id.equals(existing.get("id"))
          && key.equals(scenarioNameKey(Objects.toString(existing.get("name"), ""))))
        throw new IllegalArgumentException(
            "A scenario with this name already exists. Select it from the suggestions or choose"
                + " another name.");
    }
    data.put("name", name);
    data.put("id", id);
    data.put("version", 3);
    db.update(
        "INSERT INTO scenarios(id,body) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET"
            + " body=excluded.body",
        id,
        json(data));
    return data;
  }

  private static String scenarioNameKey(String name) {
    return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC)
        .strip()
        .toLowerCase(Locale.ROOT);
  }

  public void saveTransfer(Map<String, Object> report) {
    db.update(
        "INSERT INTO transfers(id,body) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET"
            + " body=excluded.body",
        report.get("id"),
        json(report));
  }

  public List<Map<String, Object>> transferHistory() {
    return db.query(
        "SELECT body FROM transfers ORDER BY json_extract(body,'$.startedAt') DESC LIMIT 100",
        (r, n) -> {
          try {
            return mapper.readValue(
                r.getString(1),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
          } catch (Exception e) {
            throw new IllegalStateException("Unable to read transfer history.");
          }
        });
  }
}
