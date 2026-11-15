package com.databridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DATABRIDGE_TRANSFER_LIVE", matches = "1")
class GraphLiveTest {
  @BeforeAll
  static void setup() throws Exception {
    TransferLiveTest.setup();
    for (var profile : List.of(TransferLiveTest.source, TransferLiveTest.target)) {
      try (var c = TransferLiveTest.oracle.open(profile, "");
          var s = c.createStatement()) {
        s.execute("CREATE TABLE UNIQUE_PARENT(ID NUMBER PRIMARY KEY,CODE VARCHAR2(20) UNIQUE)");
        s.execute("CREATE TABLE UNIQUE_CHILD(ID NUMBER PRIMARY KEY,P_CODE VARCHAR2(20))");
        s.execute(
            "INSERT INTO UNIQUE_PARENT VALUES(1,'"
                + (profile == TransferLiveTest.source ? "OLD" : "EXISTING")
                + "')");
        if (profile == TransferLiveTest.source)
          s.execute("INSERT INTO UNIQUE_CHILD VALUES(1,'OLD')");
        s.execute(
            "CREATE TABLE GRAPH_PARENT(ID NUMBER PRIMARY KEY,CODE VARCHAR2(20),STATE"
                + " VARCHAR2(12))");
        s.execute(
            "CREATE TABLE GRAPH_CHILD(ID NUMBER PRIMARY KEY,PARENT_ID NUMBER,KIND VARCHAR2(12))");
        if (profile == TransferLiveTest.source) {
          s.execute("INSERT INTO GRAPH_PARENT VALUES(1,'A','AKTIF')");
          s.execute("INSERT INTO GRAPH_PARENT VALUES(2,'B','PASIF')");
          s.execute("INSERT INTO GRAPH_CHILD VALUES(11,1,'KREDI')");
          s.execute("INSERT INTO GRAPH_CHILD VALUES(12,1,'OTHER')");
          s.execute("INSERT INTO GRAPH_CHILD VALUES(13,2,'KREDI')");
        } else s.execute("CREATE SEQUENCE SEQ_GRAPH START WITH 90001");
      }
    }
    // Oracle read-only snapshots cannot cross the fixture DDL timestamp.
    Thread.sleep(2100);
  }

  static TransferService.Relation relation(String parentColumn) {
    String o = TransferLiveTest.sourceOwner;
    return new TransferService.Relation(
        o,
        "GRAPH_CHILD",
        o,
        "GRAPH_PARENT",
        "M_GRAPH",
        "CHILD",
        List.of(new TransferService.Pair("PARENT_ID", parentColumn)),
        "MANUAL");
  }

  static TransferService.Request request(
      TransferService.Relation edge, Map<String, String> filters) {
    return new TransferService.Request(
        TransferLiveTest.source.id(),
        TransferLiveTest.target.id(),
        "",
        "",
        TransferLiveTest.sourceOwner,
        "GRAPH_PARENT",
        "STATE='AKTIF'",
        100,
        List.of(edge),
        Map.of(
            "GRAPH_PARENT",
            new KeyPolicy("SEQUENCE", "ID", "SEQ_GRAPH", null, null, null, null, "ERROR")),
        filters);
  }

  @Test
  void manualRelationFiltersAndRemapsNewParentId() throws Exception {
    var service = TransferLiveTest.transfers;
    var p = service.preview(request(relation("ID"), Map.of("GRAPH_CHILD", "KIND='KREDI'")));
    assertEquals(2, p.total());
    assertEquals("GRAPH_PARENT", p.tables().getFirst().table());
    var job = TransferLiveTest.await(service.start(p.token()).get("id").toString());
    assertEquals("COMPLETED", job.get("status"), job.toString());
    assertEquals(2L, job.get("inserted"));
    try (var c = TransferLiveTest.oracle.open(TransferLiveTest.target, "");
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT p.ID,c.ID FROM GRAPH_PARENT p JOIN GRAPH_CHILD c ON c.PARENT_ID=p.ID")) {
      assertTrue(r.next());
      assertTrue(r.getLong(1) >= 90001);
      assertEquals(11, r.getLong(2));
      assertFalse(r.next());
    }
  }

  @Test
  void rejectsWrongTypeAndNonUniqueParent() throws Exception {
    var service = TransferLiveTest.transfers;
    assertThrows(
        IllegalArgumentException.class, () -> service.preview(request(relation("CODE"), Map.of())));
    String o = TransferLiveTest.sourceOwner;
    var edge =
        new TransferService.Relation(
            o,
            "GRAPH_CHILD",
            o,
            "GRAPH_PARENT",
            "M_GRAPH_BAD",
            "CHILD",
            List.of(new TransferService.Pair("KIND", "CODE")),
            "MANUAL");
    assertThrows(IllegalArgumentException.class, () -> service.preview(request(edge, Map.of())));
  }

  @Test
  void rejectsFilterOnDisconnectedTable() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TransferLiveTest.transfers.preview(
                request(relation("ID"), Map.of("KREDI", "DURUM='AKTIF'"))));
  }

  @Test
  void keepSkipRemapsManualUniqueReferenceToExistingTarget() throws Exception {
    String o = TransferLiveTest.sourceOwner;
    var edge =
        new TransferService.Relation(
            o,
            "UNIQUE_CHILD",
            o,
            "UNIQUE_PARENT",
            "M_UNIQUE",
            "CHILD",
            List.of(new TransferService.Pair("P_CODE", "CODE")),
            "MANUAL");
    var req =
        new TransferService.Request(
            TransferLiveTest.source.id(),
            TransferLiveTest.target.id(),
            "",
            "",
            o,
            "UNIQUE_PARENT",
            "",
            100,
            List.of(edge),
            Map.of("UNIQUE_PARENT", new KeyPolicy("KEEP", "", "", null, null, null, null, "SKIP")));
    var p = TransferLiveTest.transfers.preview(req);
    var job =
        TransferLiveTest.await(TransferLiveTest.transfers.start(p.token()).get("id").toString());
    assertEquals("COMPLETED", job.get("status"), job.toString());
    try (var c = TransferLiveTest.oracle.open(TransferLiveTest.target, "");
        var s = c.createStatement();
        var r = s.executeQuery("SELECT P_CODE FROM UNIQUE_CHILD")) {
      assertTrue(r.next());
      assertEquals("EXISTING", r.getString(1));
    }
  }

  @AfterAll
  static void close() {
    TransferLiveTest.transfers.shutdown();
  }
}
