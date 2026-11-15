package com.databridge;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DATABRIDGE_TRANSFER_LIVE", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KeyPolicyLiveTest {
  static final List<Map<String,String>> outcomes=new ArrayList<>();
  @org.junit.jupiter.api.extension.RegisterExtension
  static final org.junit.jupiter.api.extension.TestWatcher watcher=new org.junit.jupiter.api.extension.TestWatcher(){
    @Override public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext c){outcomes.add(Map.of("test",c.getRequiredTestMethod().getName(),"status","PASS"));}
    @Override public void testFailed(org.junit.jupiter.api.extension.ExtensionContext c,Throwable error){outcomes.add(Map.of("test",c.getRequiredTestMethod().getName(),"status","FAIL","errorType",error.getClass().getSimpleName()));}
  };
  static ProfileStore store;
  static OracleService oracle;
  static TransferService service;
  static ConnectionProfile source, target;
  static String owner, targetOwner;

  @BeforeAll
  static void setup() throws Exception {
    TransferLiveTest.setup();
    store = TransferLiveTest.store;
    oracle = TransferLiveTest.oracle;
    service = TransferLiveTest.transfers;
    source = TransferLiveTest.source;
    target = TransferLiveTest.target;
    owner = TransferLiveTest.sourceOwner;
    targetOwner = TransferLiveTest.targetOwner;
    try (var c = oracle.open(target, "");
        var s = c.createStatement()) {
      s.execute("CREATE SEQUENCE SEQ_MUSTERI START WITH 8001");
      s.execute("CREATE SEQUENCE SEQ_KREDI START WITH 45001");
      s.execute("CREATE SEQUENCE SEQ_ODEME_PLANI START WITH 60001");
      s.execute("CREATE SEQUENCE SEQ_KEY_DEFAULT START WITH 90001");
      s.execute("ALTER TABLE MUSTERI ADD CONSTRAINT UQ_MUSTERI_TCKN UNIQUE(TCKN)");
      s.execute(
          "CREATE TABLE DEFAULT_EXAMPLE(ID NUMBER DEFAULT SEQ_KEY_DEFAULT.NEXTVAL PRIMARY KEY)");
      s.execute(
          "CREATE TABLE ID_PARENT(ID NUMBER GENERATED ALWAYS AS IDENTITY(START WITH 50001) PRIMARY"
              + " KEY,CODE VARCHAR2(20))");
      s.execute(
          "CREATE TABLE ID_CHILD(ID NUMBER PRIMARY KEY,PARENT_ID NUMBER,CONSTRAINT FK_ID_PARENT"
              + " FOREIGN KEY(PARENT_ID) REFERENCES ID_PARENT(ID))");
    }
    try (var c = oracle.open(source, "");
        var s = c.createStatement()) {
      s.execute("CREATE TABLE ID_PARENT(ID NUMBER PRIMARY KEY,CODE VARCHAR2(20))");
      s.execute(
          "CREATE TABLE ID_CHILD(ID NUMBER PRIMARY KEY,PARENT_ID NUMBER,CONSTRAINT FK_ID_PARENT"
              + " FOREIGN KEY(PARENT_ID) REFERENCES ID_PARENT(ID))");
      s.execute("INSERT INTO ID_PARENT VALUES(1,'A')");
      s.execute("INSERT INTO ID_PARENT VALUES(2,'B')");
      s.execute("INSERT INTO ID_CHILD VALUES(91,1)");
      s.execute("INSERT INTO ID_CHILD VALUES(92,2)");
    }
  }

  @BeforeEach
  void clearOwnedFixture() throws Exception {
    assertTrue(targetOwner.startsWith("DBX_"));
    try (var c = oracle.open(target, "");
        var s = c.createStatement()) {
      for (String table :
          List.of(
              "TAKSIT_TAHSILAT",
              "TAKSIT",
              "TEMINAT",
              "ODEME_PLANI",
              "KREDI_HAREKET",
              "KREDI",
              "MUSTERI",
              "PERF_ROWS",
              "ID_CHILD",
              "ID_PARENT")) s.executeUpdate("DELETE FROM " + table);
    }
  }

  static KeyPolicy policy(String mode, String col, String seq) {
    return new KeyPolicy(
        mode, col, seq, BigDecimal.ONE, BigDecimal.ONE, List.of(), List.of(), "ERROR");
  }

  static Map<String, KeyPolicy> sequences() {
    return new HashMap<>(
        Map.of(
            "MUSTERI",
            policy("SEQUENCE", "MUSTERI_ID", "SEQ_MUSTERI"),
            "KREDI",
            policy("SEQUENCE", "KREDI_ID", "SEQ_KREDI"),
            "ODEME_PLANI",
            policy("SEQUENCE", "PLAN_ID", "SEQ_ODEME_PLANI")));
  }

  static TransferService.Request request(Map<String, KeyPolicy> policies) {
    return new TransferService.Request(
        source.id(),
        target.id(),
        "",
        "",
        owner,
        "KREDI",
        "DURUM='AKTIF'",
        100,
        TransferLiveTest.relations,
        policies);
  }

  static Map<String, Object> run(TransferService.Request req) throws Exception {
    var p = service.preview(req);
    return TransferLiveTest.await(service.start(p.token()).get("id").toString());
  }

  static long scalar(String sql) throws Exception {
    try (var c = oracle.open(target, "");
        var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  @Order(1)
  void sequenceSuggestionsDoNotConsumeValues() throws Exception {
    long before =
        scalar("SELECT LAST_NUMBER FROM USER_SEQUENCES WHERE SEQUENCE_NAME='SEQ_KEY_DEFAULT'");
    var metadata =
        new KeyMetadata(store, oracle)
            .options(
                new KeyMetadata.OptionsRequest(
                    target.id(), "", List.of("KREDI", "DEFAULT_EXAMPLE", "ID_PARENT")));
    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata);
    assertTrue(json.contains("SEQ_KREDI"), json);
    assertTrue(json.contains("name match"), json);
    assertTrue(json.contains("NEXTVAL in column DEFAULT"), json);
    assertTrue(json.contains("IDENTITY"), json);
    assertEquals(
        before,
        scalar("SELECT LAST_NUMBER FROM USER_SEQUENCES WHERE SEQUENCE_NAME='SEQ_KEY_DEFAULT'"));
  }

  @Test
  @Order(2)
  void sequenceRemapsNestedAndCompositeForeignKeys() throws Exception {
    var result = run(request(sequences()));
    assertEquals("COMPLETED", result.get("status"), result.toString());
    assertEquals(33L, result.get("inserted"));
    assertEquals(
        2, scalar("SELECT COUNT(*) FROM KREDI WHERE KREDI_ID>=45001 AND MUSTERI_ID>=8001"));
    assertEquals(24, scalar("SELECT COUNT(*) FROM TAKSIT WHERE PLAN_ID>=60001"));
    assertEquals(
        1,
        scalar(
            "SELECT COUNT(*) FROM TAKSIT_TAHSILAT t JOIN TAKSIT k ON k.PLAN_ID=t.PLAN_ID AND"
                + " k.TAKSIT_NO=t.TAKSIT_NO WHERE t.PLAN_ID>=60001"));
  }

  @Test
  @Order(3)
  void maximumAndGroupedMaximumUseMappedParentKeys() throws Exception {
    try (var c = oracle.open(target, "");
        var s = c.createStatement()) {
      s.executeUpdate("INSERT INTO MUSTERI VALUES(9000,'Existing','99999999999')");
      s.executeUpdate("INSERT INTO KREDI VALUES(9500,9000,'EXISTING','TEST',1)");
    }
    Map<String, KeyPolicy> policies = new HashMap<>();
    policies.put("MUSTERI", policy("MAX", "MUSTERI_ID", ""));
    policies.put("KREDI", policy("MAX", "KREDI_ID", ""));
    policies.put(
        "ODEME_PLANI",
        new KeyPolicy(
            "MAX",
            "PLAN_ID",
            "",
            new BigDecimal("7000"),
            BigDecimal.ONE,
            List.of(),
            List.of(),
            "ERROR"));
    policies.put(
        "TAKSIT",
        new KeyPolicy(
            "MAX",
            "TAKSIT_NO",
            "",
            new BigDecimal("5"),
            new BigDecimal("2"),
            List.of("PLAN_ID"),
            List.of(),
            "ERROR"));
    var result = run(request(policies));
    assertEquals("COMPLETED", result.get("status"), result.toString());
    assertEquals(
        2,
        scalar(
            "SELECT COUNT(*) FROM KREDI WHERE KREDI_ID IN (9501,9502) AND MUSTERI_ID IN"
                + " (9001,9002)"));
    assertEquals(
        24,
        scalar(
            "SELECT COUNT(*) FROM TAKSIT WHERE PLAN_ID IN (7000,7001) AND MOD(TAKSIT_NO,2)=1 AND"
                + " TAKSIT_NO BETWEEN 5 AND 27"));
    assertEquals(
        1, scalar("SELECT COUNT(*) FROM TAKSIT_TAHSILAT WHERE PLAN_ID=7000 AND TAKSIT_NO=5"));
  }

  @Test
  @Order(4)
  void businessKeyReusesActualTargetIdentity() throws Exception {
    try (var c = oracle.open(target, "");
        var s = c.createStatement()) {
      s.executeUpdate("INSERT INTO MUSTERI VALUES(7001,'Existing A','00000000001')");
      s.executeUpdate("INSERT INTO MUSTERI VALUES(7002,'Existing B','00000000002')");
    }
    var policies = sequences();
    policies.put(
        "MUSTERI",
        new KeyPolicy("BUSINESS", "", "", null, null, List.of(), List.of("TCKN"), "ERROR"));
    var result = run(request(policies));
    assertEquals("COMPLETED", result.get("status"), result.toString());
    assertEquals(31L, result.get("inserted"));
    assertEquals(2L, result.get("skipped"));
    assertEquals(2, scalar("SELECT COUNT(*) FROM KREDI WHERE MUSTERI_ID IN (7001,7002)"));
  }

  @Test
  @Order(5)
  void identityValuesAreReadAndMappedToChildren() throws Exception {
    var relation =
        new TransferService.Relation(
            owner,
            "ID_CHILD",
            owner,
            "ID_PARENT",
            "FK_ID_PARENT",
            "CHILD",
            List.of(new TransferService.Pair("PARENT_ID", "ID")));
    var request =
        new TransferService.Request(
            source.id(),
            target.id(),
            "",
            "",
            owner,
            "ID_PARENT",
            "",
            100,
            List.of(relation),
            Map.of(
                "ID_PARENT",
                policy("IDENTITY", "ID", ""),
                "ID_CHILD",
                new KeyPolicy(
                    "MAX",
                    "ID",
                    "",
                    new BigDecimal("100001"),
                    BigDecimal.ONE,
                    List.of(),
                    List.of(),
                    "ERROR")));
    var result = run(request);
    assertEquals("COMPLETED", result.get("status"), result.toString());
    assertEquals(
        2,
        scalar(
            "SELECT COUNT(*) FROM ID_CHILD c JOIN ID_PARENT p ON p.ID=c.PARENT_ID WHERE p.ID>=50001"
                + " AND c.ID>=100001"));
  }

  @Test
  @Order(6)
  void sourceCollisionErrorsByDefaultInsteadOfSkipping() throws Exception {
    try (var c = oracle.open(target, "");
        var s = c.createStatement()) {
      s.executeUpdate("INSERT INTO PERF_ROWS VALUES(1,'OTHER','UNRELATED',0,NULL)");
    }
    var result =
        run(
            new TransferService.Request(
                source.id(), target.id(), "", "", owner, "PERF_ROWS", "", 2, List.of()));
    assertEquals("FAILED", result.get("status"));
    assertEquals(0L, result.get("inserted"));
    assertTrue(result.get("message").toString().contains("conflict"));
    assertEquals(1, scalar("SELECT COUNT(*) FROM PERF_ROWS"));
  }

  @Test
  @Order(7)
  void concurrentMaximumCollisionRollsBackOwnEarlierInsert() throws Exception {
    java.util.concurrent.atomic.AtomicInteger inserts =
        new java.util.concurrent.atomic.AtomicInteger();
    OracleService racing =
        new OracleService(store) {
          @Override
          Connection open(ConnectionProfile profile, String password) throws SQLException {
            Connection c = super.open(profile, password);
            if (!profile.id().equals(target.id())) return c;
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class[] {Connection.class},
                    (proxy, method, args) -> {
                      try {
                        Object value = method.invoke(c, args);
                        if (method.getName().equals("prepareStatement")
                            && args[0]
                                .toString()
                                .startsWith("INSERT INTO \"" + targetOwner + "\".\"PERF_ROWS\"")) {
                          PreparedStatement statement = (PreparedStatement) value;
                          return Proxy.newProxyInstance(
                              PreparedStatement.class.getClassLoader(),
                              new Class[] {PreparedStatement.class},
                              (p, m, a) -> {
                                try {
                                  if (m.getName().equals("executeUpdate")
                                      && inserts.incrementAndGet() == 2) {
                                    try (var other = oracle.open(target, "");
                                        var s = other.createStatement()) {
                                      s.executeUpdate(
                                          "INSERT INTO PERF_ROWS"
                                              + " VALUES(2,'OTHER','CONCURRENT',0,NULL)");
                                    }
                                  }
                                  return m.invoke(statement, a);
                                } catch (InvocationTargetException e) {
                                  throw e.getCause();
                                }
                              });
                        }
                        return value;
                      } catch (InvocationTargetException e) {
                        throw e.getCause();
                      }
                    });
          }
        };
    var transfers = new TransferService(store, racing);
    try {
      var p =
          transfers.preview(
              new TransferService.Request(
                  source.id(),
                  target.id(),
                  "",
                  "",
                  owner,
                  "PERF_ROWS",
                  "",
                  2,
                  List.of(),
                  Map.of("PERF_ROWS", policy("MAX", "ID", ""))));
      String id = transfers.start(p.token()).get("id").toString();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
      Map<String, Object> result;
      do {
        Thread.sleep(20);
        result = transfers.job(id);
      } while (Set.of("RUNNING", "QUEUED").contains(result.get("status"))
          && System.nanoTime() < deadline);
      assertEquals("FAILED", result.get("status"), result.toString());
      assertEquals(0, scalar("SELECT COUNT(*) FROM PERF_ROWS WHERE ID=1"));
      assertEquals(1, scalar("SELECT COUNT(*) FROM PERF_ROWS WHERE ID=2 AND STATE='OTHER'"));
    } finally {
      transfers.shutdown();
    }
  }

  @AfterAll
  static void close() throws Exception {
    TransferLiveTest.close();
    var report=Map.of("timestamp",java.time.Instant.now().toString(),"sourceSchema",owner,"targetSchema",targetOwner,"passed",outcomes.stream().filter(r->r.get("status").equals("PASS")).count(),"failed",outcomes.stream().filter(r->r.get("status").equals("FAIL")).count(),"checks",outcomes);
    new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("..","oracle-key-policy-tests.json").toFile(),report);
  }
}
