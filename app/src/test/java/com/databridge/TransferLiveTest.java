package com.databridge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "DATABRIDGE_TRANSFER_LIVE", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferLiveTest {
  static ProfileStore store;
  static OracleService oracle;
  static TransferService transfers;
  static ConnectionProfile source, target;
  static String sourceOwner, targetOwner;
  static List<TransferService.Relation> relations;
  static Map<String, Object> benchmark;

  @BeforeAll
  static void setup() throws Exception {
    sourceOwner = System.getenv("DATABRIDGE_TRANSFER_SOURCE");
    targetOwner = System.getenv("DATABRIDGE_TRANSFER_TARGET");
    assertTrue(sourceOwner.matches("DBS_[A-F0-9]{8}"));
    assertTrue(targetOwner.matches("DBX_[A-F0-9]{8}"));
    var db =
        new DriverManagerDataSource(
            "jdbc:sqlite:"
                + Files.createTempDirectory("databridge-transfer-live-").resolve("state.db"));
    store = new ProfileStore(new JdbcTemplate(db), new ObjectMapper());
    oracle = new OracleService(store);
    transfers = new TransferService(store, oracle);
    source =
        store.save(
            profile(sourceOwner, true),
            System.getenv("DATABRIDGE_TRANSFER_SOURCE_PASSWORD"),
            true,
            false);
    target =
        store.save(
            profile(targetOwner, false),
            System.getenv("DATABRIDGE_TRANSFER_TARGET_PASSWORD"),
            true,
            false);
    relations = new ArrayList<>();
    for (String table : List.of("KREDI", "ODEME_PLANI", "TAKSIT")) {
      @SuppressWarnings("unchecked")
      var found =
          (Collection<Map<String, Object>>)
              oracle.discover(source.id(), "", sourceOwner, table).get("relations");
      for (var r : found) {
        String name = r.get("CONSTRAINT_NAME").toString();
        if (relations.stream().anyMatch(x -> x.constraint().equals(name))) continue;
        if (!table.equals("KREDI") && !r.get("DIRECTION").equals("CHILD")) continue;
        relations.add(new ObjectMapper().convertValue(r, TransferService.Relation.class));
      }
    }
  }

  static ConnectionProfile profile(String user, boolean ro) {
    return new ConnectionProfile(
        null,
        user,
        "Live transfer",
        "TEST",
        user,
        "BASIC",
        "localhost",
        1521,
        "databridge_test",
        "",
        "",
        "NORMAL",
        "#3467dc",
        ro,
        false,
        "TEST");
  }

  static TransferService.Request request(
      String table, String condition, int limit, List<TransferService.Relation> edges) {
    Map<String, KeyPolicy> policies = new HashMap<>();
    policies.put(table, new KeyPolicy("KEEP", "", "", null, null, null, null, "SKIP"));
    for (var r : edges) {
      policies.put(r.childTable(), new KeyPolicy("KEEP", "", "", null, null, null, null, "SKIP"));
      policies.put(r.parentTable(), new KeyPolicy("KEEP", "", "", null, null, null, null, "SKIP"));
    }
    return new TransferService.Request(
        source.id(), target.id(), "", "", sourceOwner, table, condition, limit, edges, policies);
  }

  static Map<String, Object> await(String id) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(8);
    while (System.nanoTime() < deadline) {
      var job = transfers.job(id);
      if (!Set.of("RUNNING", "QUEUED").contains(job.get("status"))) return job;
      Thread.sleep(25);
    }
    transfers.cancel(id);
    throw new AssertionError("Canlı aktarım zaman aşımı.");
  }

  static long count(ConnectionProfile profile, String table, String condition) throws Exception {
    try (var c = oracle.open(profile, "");
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT COUNT(*) FROM \""
                    + table
                    + "\""
                    + (condition.isEmpty() ? "" : " WHERE " + condition))) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  @Order(1)
  void nestedSubsetAndExistingKeys() throws Exception {
    var p = transfers.preview(request("KREDI", "DURUM='AKTIF'", 100, relations));
    assertEquals(33, p.total());
    assertEquals("MUSTERI", p.tables().getFirst().table());
    var job = await(transfers.start(p.token()).get("id").toString());
    assertEquals("COMPLETED", job.get("status"), job.toString());
    assertEquals(33L, job.get("inserted"));
    assertEquals(2, count(target, "KREDI", ""));
    assertEquals(0, count(target, "KREDI", "KREDI_ID=102"));
    assertEquals(24, count(target, "TAKSIT", ""));
    assertEquals(0, count(target, "KREDI_HAREKET", ""));
    var again = transfers.preview(request("KREDI", "DURUM='AKTIF'", 100, relations));
    var repeated = await(transfers.start(again.token()).get("id").toString());
    assertEquals("COMPLETED", repeated.get("status"));
    assertEquals(0L, repeated.get("inserted"));
    assertEquals(33L, repeated.get("skipped"));
  }

  @Test
  @Order(2)
  void previewSnapshotDoesNotDrift() throws Exception {
    var p = transfers.preview(request("KREDI", "DURUM='AKTIF'", 100, relations));
    try (var c = oracle.open(source, "");
        var s = c.createStatement()) {
      s.executeUpdate("INSERT INTO KREDI VALUES(104,1,'KRD-104','AKTIF',7.25)");
    }
    try {
      var result = await(transfers.start(p.token()).get("id").toString());
      assertEquals("COMPLETED", result.get("status"));
      assertEquals(33L, result.get("processed"));
      assertEquals(0, count(target, "KREDI", "KREDI_ID=104"));
    } finally {
      try (var c = oracle.open(source, "");
          var s = c.createStatement()) {
        s.executeUpdate("DELETE FROM KREDI WHERE KREDI_ID=104");
      }
    }
  }

  @Test
  @Order(3)
  void constraintFailureRollsBackEarlierTables() throws Exception {
    try (var c = oracle.open(target, "");
        var s = c.createStatement()) {
      s.execute("ALTER TABLE ODEME_PLANI ADD CONSTRAINT TEST_BLOCK_PLAN CHECK (PLAN_ID <> 1002)");
    }
    try {
      var p = transfers.preview(request("KREDI", "KREDI_ID=102", 100, relations));
      var result = await(transfers.start(p.token()).get("id").toString());
      assertEquals("FAILED", result.get("status"), result.toString());
      assertEquals(0L, result.get("inserted"));
      assertEquals(0, count(target, "KREDI", "KREDI_ID=102"));
      assertEquals(2, count(target, "KREDI", ""));
    } finally {
      try (var c = oracle.open(target, "");
          var s = c.createStatement()) {
        s.execute("ALTER TABLE ODEME_PLANI DROP CONSTRAINT TEST_BLOCK_PLAN");
      }
    }
  }

  @Test
  @Order(4)
  void rejectsSameSchemaProdTargetAndForgedFk() throws Exception {
    var same =
        store.save(
            profile(sourceOwner, false),
            System.getenv("DATABRIDGE_TRANSFER_SOURCE_PASSWORD"),
            true,
            false);
    var req = request("KREDI", "", 10, relations);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transfers.preview(
                new TransferService.Request(
                    source.id(), same.id(), "", "", sourceOwner, "KREDI", "", 10, relations)));
    var prod =
        new ConnectionProfile(
            null,
            "prod",
            "test",
            "PROD",
            target.username(),
            target.mode(),
            target.host(),
            target.port(),
            target.service(),
            target.database(),
            target.descriptor(),
            "NORMAL",
            target.color(),
            false,
            false,
            "TEST");
    var saved = store.save(prod, System.getenv("DATABRIDGE_TRANSFER_TARGET_PASSWORD"), true, false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transfers.preview(
                new TransferService.Request(
                    source.id(), saved.id(), "", "", sourceOwner, "KREDI", "", 10, relations)));
    var real = relations.getFirst();
    var forged =
        new TransferService.Relation(
            real.childOwner(),
            real.childTable(),
            real.parentOwner(),
            real.parentTable(),
            real.constraint(),
            real.direction(),
            List.of(new TransferService.Pair("KREDI_ID", "MUSTERI_ID")));
    assertThrows(
        IllegalArgumentException.class,
        () -> transfers.preview(request("KREDI", "", 10, List.of(forged))));
  }

  @Test
  @Order(5)
  void cancelRollsBackBatches() throws Exception {
    var p = transfers.preview(request("PERF_ROWS", "", 1_000_000, List.of()));
    var id = transfers.start(p.token()).get("id").toString();
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (((Number) transfers.job(id).get("processed")).longValue() == 0
        && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(((Number) transfers.job(id).get("processed")).longValue() > 0);
    transfers.cancel(id);
    var result = await(id);
    assertEquals("CANCELLED", result.get("status"), result.toString());
    assertEquals(0, count(target, "PERF_ROWS", ""));
  }

  @Test
  @Order(4)
  void rejectsNarrowerNumericScaleBeforeWriting() throws Exception {
    try (var c = oracle.open(target, "");
        var s = c.createStatement()) {
      s.execute("ALTER TABLE PERF_ROWS MODIFY AMOUNT NUMBER(18,1)");
    }
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> transfers.preview(request("PERF_ROWS", "ID=1", 1, List.of())));
    } finally {
      try (var c = oracle.open(target, "");
          var s = c.createStatement()) {
        s.execute("ALTER TABLE PERF_ROWS MODIFY AMOUNT NUMBER(18,2)");
      }
    }
  }

  @Test
  @Order(6)
  void oneMillionRowsStreamAndCommit() throws Exception {
    long started = System.nanoTime();
    var p = transfers.preview(request("PERF_ROWS", "ID <= 1000000", 1_000_000, List.of()));
    assertEquals(1_000_000, p.total());
    var result = await(transfers.start(p.token()).get("id").toString());
    assertEquals("COMPLETED", result.get("status"), result.toString());
    assertEquals(1_000_000L, result.get("inserted"));
    assertEquals(1_000_000, count(target, "PERF_ROWS", ""));
    try (var c = oracle.open(target, "");
        var s = c.createStatement();
        var r = s.executeQuery("SELECT AMOUNT,CREATED_AT,VALUE FROM PERF_ROWS WHERE ID=1")) {
      assertTrue(r.next());
      assertEquals(0, new java.math.BigDecimal("1.25").compareTo(r.getBigDecimal(1)));
      assertEquals("2026-01-01 13:14:15.0", r.getTimestamp(2).toString());
      assertEquals("ROW-1", r.getString(3));
    }
    benchmark = new LinkedHashMap<>(result);
    benchmark.put("elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000d);
    benchmark.put("sourceSchema", sourceOwner);
    benchmark.put("targetSchema", targetOwner);
    benchmark.put("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1024 / 1024);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(
            java.nio.file.Path.of("..", "oracle-transfer-benchmark.json").toFile(), benchmark);
  }

  @Test
  @Order(7)
  void schemaChangeAfterPreviewIsRejected() throws Exception {
    var preview = transfers.preview(request("KREDI", "DURUM='AKTIF'", 100, relations));
    try (var connection = oracle.open(target, "");
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE KREDI DISABLE CONSTRAINT FK_KREDI_MUSTERI");
    }
    try {
      var result = await(transfers.start(preview.token()).get("id").toString());
      assertEquals("FAILED", result.get("status"), result.toString());
      assertEquals(0L, result.get("inserted"));
      assertEquals(2, count(target, "KREDI", ""));
    } finally {
      try (var connection = oracle.open(target, "");
          var statement = connection.createStatement()) {
        statement.execute("ALTER TABLE KREDI ENABLE VALIDATE CONSTRAINT FK_KREDI_MUSTERI");
      }
    }
  }

  @AfterAll
  static void close() {
    if (transfers != null) transfers.shutdown();
  }
}
