package com.databridge;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in test against the provisioned local Oracle PDB; never uses production credentials. */
@EnabledIfEnvironmentVariable(named = "DATABRIDGE_LIVE_ORACLE", matches = "1")
class OracleLiveTest {
  private ConnectionProfile profile() {
    return new ConnectionProfile(
        null,
        "Live Oracle test",
        "Yerel Oracle 26ai",
        "DEV",
        "DBRIDGE_SRC",
        "BASIC",
        "localhost",
        1521,
        "databridge_test",
        "",
        "",
        "NORMAL",
        "#3467dc",
        true,
        false,
        "TEST");
  }

  @Test
  void realOracleConnectionAndFixtureCounts() throws Exception {
    var service = new OracleService(null);
    String password = System.getenv("DATABRIDGE_ORACLE_TEST_PASSWORD");
    var result = service.test(profile(), password);
    assertEquals(true, result.get("ok"));
    assertEquals("DBRIDGE_SRC", result.get("user"));
    try (var connection = service.open(profile(), password);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select count(*) from KREDI")) {
      assertTrue(rows.next());
      assertEquals(3, rows.getInt(1));
    }
  }

  @Test
  void tableBrowserExcludesSystemOwnersAndSupportsOptionalFilter() throws Exception {
    var service = new OracleService(null);
    try (var c = service.open(profile(), System.getenv("DATABRIDGE_ORACLE_TEST_PASSWORD"))) {
      service.readOnly(c);
      var all = service.accessibleTables(c, null);
      assertFalse(all.isEmpty());
      assertEquals(all, service.accessibleTables(c, " "));
      var own = service.accessibleTables(c, "dbridge_src");
      assertFalse(own.isEmpty());
      assertTrue(own.stream().allMatch(r -> r.get("OWNER").equals("DBRIDGE_SRC")));
      assertTrue(all.containsAll(own));
      assertTrue(service.accessibleTables(c, "SYS").isEmpty());
      assertTrue(service.accessibleTables(c, "SYSTEM").isEmpty());
      assertThrows(IllegalArgumentException.class,
          () -> service.requireApplicationTable(c, "SYS", "DUAL"));
      try (var stmt = c.createStatement();
          var rows = stmt.executeQuery("SELECT username FROM all_users WHERE oracle_maintained='Y'")) {
        while (rows.next()) {
          String systemOwner = rows.getString(1);
          assertTrue(all.stream().noneMatch(r -> r.get("OWNER").equals(systemOwner)));
        }
      }
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "DATABRIDGE_ORACLE_TARGET_PASSWORD", matches = ".+")
  void grantedTableIsVisibleWithItsRealOwner() throws Exception {
    var store = org.mockito.Mockito.mock(ProfileStore.class);
    org.mockito.Mockito.when(store.get("source")).thenReturn(profile());
    var service = new OracleService(store);
    var target = new ConnectionProfile(null, "Grant test", "Local", "DEV", "DBRIDGE_TGT",
        "BASIC", "localhost", 1521, "databridge_test", "", "", "NORMAL", "#3467dc",
        false, false, "TEST");
    String table = "GRANT_PROBE_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    try (var targetConnection = service.open(target, System.getenv("DATABRIDGE_ORACLE_TARGET_PASSWORD"));
        var stmt = targetConnection.createStatement()) {
      stmt.execute("CREATE TABLE " + table + " (ID NUMBER PRIMARY KEY)");
      try {
        stmt.execute("GRANT SELECT ON " + table + " TO DBRIDGE_SRC");
        try (var source = service.open(profile(), System.getenv("DATABRIDGE_ORACLE_TEST_PASSWORD"))) {
          var all = service.accessibleTables(source, "");
          assertTrue(all.stream().anyMatch(r -> r.get("OWNER").equals("DBRIDGE_TGT") && r.get("TABLE_NAME").equals(table)));
          assertTrue(service.accessibleTables(source, "DBRIDGE_TGT").stream().anyMatch(r -> r.get("TABLE_NAME").equals(table)));
          assertTrue(service.accessibleTables(source, "DBRIDGE_SRC").stream().noneMatch(r -> r.get("TABLE_NAME").equals(table)));
          service.requireApplicationTable(source, "DBRIDGE_TGT", table);
          String password = System.getenv("DATABRIDGE_ORACLE_TEST_PASSWORD");
          assertTrue(service.tables("source", password, null).stream()
              .anyMatch(r -> r.get("OWNER").equals("DBRIDGE_TGT") && r.get("TABLE_NAME").equals(table)));
          assertEquals("DBRIDGE_TGT", service.discover("source", password, "DBRIDGE_TGT", table).get("owner"));
          assertThrows(IllegalArgumentException.class,
              () -> service.discover("source", password, "SYS", "DUAL"));
          try (var query = source.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM DBRIDGE_TGT." + table)) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
          }
        }
      } finally {
        stmt.execute("DROP TABLE " + table + " PURGE");
      }
    }
  }

  @Test
  void applicationReadOnlyTransactionActuallyRejectsWrites() throws Exception {
    var service = new OracleService(null);
    try (var connection =
        service.open(profile(), System.getenv("DATABRIDGE_ORACLE_TEST_PASSWORD"))) {
      service.readOnly(connection);
      try (var statement = connection.createStatement()) {
        SQLException rejected =
            assertThrows(
                SQLException.class,
                () ->
                    statement.executeUpdate(
                        "insert into KREDI_HAREKET values(-999,'READONLY-PROBE','KREDI',0)"));
        assertEquals(1456, rejected.getErrorCode());
      } finally {
        connection.rollback();
      }
    }
  }
}
