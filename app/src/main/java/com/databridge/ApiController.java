package com.databridge;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final ProfileStore store;
  private final OracleService oracle;
  private final KeyMetadata keys;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  public ApiController(
      ProfileStore store,
      OracleService oracle,
      KeyMetadata keys,
      com.fasterxml.jackson.databind.ObjectMapper mapper) {
    this.store = store;
    this.oracle = oracle;
    this.keys = keys;
    this.mapper = mapper;
  }

  public record Save(
      ConnectionProfile profile,
      String password,
      boolean rememberPassword,
      boolean clearPassword) {}

  public record Probe(ConnectionProfile profile, String password) {}

  public record Credentials(String password, String schema, String table) {}

  @PostMapping("/keys/options")
  public Map<String, Object> keyOptions(@RequestBody KeyMetadata.OptionsRequest request)
      throws SQLException {
    return keys.options(request);
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    return Map.of(
        "version",
        "0.5.0",
        "java",
        Runtime.version().feature(),
        "connections",
        store.all().size(),
        "scenarios",
        store.scenarios().size());
  }

  @GetMapping("/connections")
  public List<ConnectionProfile> all() {
    return store.all();
  }

  @PostMapping("/connections")
  public ConnectionProfile save(@RequestBody Save req) {
    return store.save(req.profile, req.password, req.rememberPassword, req.clearPassword);
  }

  @DeleteMapping("/connections/{id}")
  public void delete(@PathVariable String id) {
    store.delete(id);
  }

  @PostMapping("/connections/test")
  public Map<String, Object> test(@RequestBody Probe req) throws SQLException {
    return oracle.test(req.profile, req.password);
  }

  @PostMapping("/connections/{id}/tables")
  public List<Map<String, Object>> tables(@PathVariable String id, @RequestBody Credentials req)
      throws SQLException {
    return oracle.tables(id, req.password, req.schema);
  }

  @PostMapping("/connections/{id}/discover")
  public Map<String, Object> discover(@PathVariable String id, @RequestBody Credentials req)
      throws SQLException {
    return oracle.discover(id, req.password, req.schema, req.table);
  }

  @PostMapping("/imports/tns")
  public TnsParser.Result tns(@RequestParam("file") MultipartFile file) throws Exception {
    return new TnsParser().parse(ConnectionImporter.decode(file.getBytes()));
  }

  @PostMapping("/imports/plsql")
  public ConnectionImporter.Preview plsql(@RequestParam("file") MultipartFile file)
      throws Exception {
    return new ConnectionImporter().parse(file.getBytes());
  }

  @org.springframework.transaction.annotation.Transactional
  @PostMapping("/imports/commit")
  public List<ConnectionProfile> commit(@RequestBody List<ConnectionProfile> profiles) {
    if (profiles.size() > 500)
      throw new IllegalArgumentException("Import up to 500 connections at a time.");
    var validated = profiles.stream().map(ConnectionProfile::normalized).toList();
    return validated.stream()
        .map(
            p ->
                store.save(
                    new ConnectionProfile(
                        null,
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
                        false,
                        p.origin()),
                    null,
                    false,
                    true))
        .toList();
  }

  @GetMapping("/tns/locations")
  public List<Map<String, Object>> locations() {
    List<Path> paths = new ArrayList<>();
    String admin = System.getenv("TNS_ADMIN"), home = System.getenv("ORACLE_HOME");
    if (admin != null && !admin.isBlank()) paths.add(Path.of(admin, "tnsnames.ora"));
    if (home != null && !home.isBlank())
      paths.add(Path.of(home, "network", "admin", "tnsnames.ora"));
    return paths.stream()
        .distinct()
        .map(p -> Map.<String, Object>of("path", p.toString(), "exists", Files.isRegularFile(p)))
        .toList();
  }

  @GetMapping("/scenarios")
  public List<Map<String, Object>> scenarios() {
    return store.scenarios();
  }

  @PostMapping("/scenarios")
  public Map<String, Object> scenario(@RequestBody Map<String, Object> data) {
    if (Objects.toString(data.get("name"), "").isBlank())
      throw new IllegalArgumentException("A scenario name is required.");
    OracleService.identifier(Objects.toString(data.get("owner"), ""));
    OracleService.identifier(Objects.toString(data.get("table"), ""));
    Object relations = data.get("relations");
    if (!(relations instanceof List<?> list) || list.size() > 500)
      throw new IllegalArgumentException("Invalid scenario relationship list.");
    Object limit = data.get("limit");
    if (!(limit instanceof Number number)
        || number.doubleValue() != number.intValue()
        || number.intValue() < 1
        || number.intValue() > 1_000_000)
      throw new IllegalArgumentException("The root row limit must be between 1 and 1,000,000.");
    Map<String, Object> clean = new LinkedHashMap<>();
    for (String key : List.of("id", "name", "owner", "table", "condition", "limit", "sourceId")) {
      if (data.get(key) != null) clean.put(key, data.get(key));
    }
    List<Map<String, Object>> cleanRelations = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> relation))
        throw new IllegalArgumentException("Invalid relationship definition.");
      Map<String, Object> safe = new LinkedHashMap<>();
      for (String key :
          List.of(
              "CHILD_OWNER", "CHILD_TABLE", "PARENT_OWNER", "PARENT_TABLE", "CONSTRAINT_NAME")) {
        safe.put(key, OracleService.identifier(Objects.toString(relation.get(key), "")));
      }
      safe.put("DIRECTION", "PARENT".equals(relation.get("DIRECTION")) ? "PARENT" : "CHILD");
      String relationSource = Objects.toString(relation.get("SOURCE"), "FK");
      if (!Set.of("FK", "MANUAL").contains(relationSource))
        throw new IllegalArgumentException("Invalid relationship source.");
      safe.put("SOURCE", relationSource);
      safe.put("STATUS", Objects.toString(relation.get("STATUS"), "UNKNOWN"));
      safe.put("VALIDATED", Objects.toString(relation.get("VALIDATED"), "UNKNOWN"));
      if (!(relation.get("COLUMN_PAIRS") instanceof List<?> pairs)
          || pairs.isEmpty()
          || pairs.size() > 32)
        throw new IllegalArgumentException("Invalid relationship column mapping.");
      List<Map<String, String>> cleanPairs = new ArrayList<>();
      for (Object pair : pairs) {
        if (!(pair instanceof Map<?, ?> columns))
          throw new IllegalArgumentException("Invalid column mapping.");
        cleanPairs.add(
            Map.of(
                "child",
                OracleService.identifier(Objects.toString(columns.get("child"), "")),
                "parent",
                OracleService.identifier(Objects.toString(columns.get("parent"), ""))));
      }
      safe.put("COLUMN_PAIRS", cleanPairs);
      cleanRelations.add(safe);
    }
    clean.put("relations", cleanRelations);
    Map<String, KeyPolicy> policies = new LinkedHashMap<>();
    if (data.get("keyPolicies") instanceof Map<?, ?> supplied) {
      if (supplied.size() > 25)
        throw new IllegalArgumentException("Up to 25 key strategies are supported.");
      for (var entry : supplied.entrySet()) {
        String table = OracleService.identifier(entry.getKey().toString());
        KeyPolicy policy = mapper.convertValue(entry.getValue(), KeyPolicy.class).normalized();
        policies.put(table, policy);
      }
    }
    clean.put("keyPolicies", policies);
    Map<String, String> filters = new LinkedHashMap<>();
    if (data.get("tableFilters") instanceof Map<?, ?> input) {
      if (input.size() > 25)
        throw new IllegalArgumentException("Up to 25 table filters are supported.");
      for (var e : input.entrySet()) {
        String filter = Objects.toString(e.getValue(), "");
        if (filter.length() > 8000) throw new IllegalArgumentException("The table filter is too long.");
        filters.put(OracleService.identifier(e.getKey().toString()), filter);
      }
    }
    clean.put("tableFilters", filters);
    if (data.get("graph") instanceof Map<?, ?> graph) {
      List<String> nodes = new ArrayList<>();
      Map<String, Map<String, Double>> positions = new LinkedHashMap<>();
      if (graph.get("tables") instanceof List<?> names) {
        if (names.size() > 25)
          throw new IllegalArgumentException("En fazla 25 grafik tablosu desteklenir.");
        for (Object name : names) nodes.add(OracleService.identifier(name.toString()));
      }
      if (graph.get("positions") instanceof Map<?, ?> points) {
        if (points.size() > 25) throw new IllegalArgumentException("Too many node positions.");
        for (var e : points.entrySet()) {
          String table = OracleService.identifier(e.getKey().toString());
          if (e.getValue() instanceof Map<?, ?> point
              && point.get("x") instanceof Number x
              && point.get("y") instanceof Number y) {
            double px = x.doubleValue(), py = y.doubleValue();
            if (!Double.isFinite(px)
                || !Double.isFinite(py)
                || Math.abs(px) > 10000
                || Math.abs(py) > 10000)
              throw new IllegalArgumentException("Invalid node position.");
            positions.put(table, Map.of("x", px, "y", py));
          }
        }
      }
      clean.put("graph", Map.of("tables", nodes, "positions", positions));
    }
    return store.saveScenario(clean);
  }

}
