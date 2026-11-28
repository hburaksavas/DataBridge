package com.databridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sqlite.Function;

class SchemaCompatibilityTest {
  @Test
  void previewReportsLobAndCrossSchemaForeignKeyTogether() throws Exception {
    var store = mock(ProfileStore.class);
    var oracle = mock(OracleService.class);
    var service = new TransferService(store, oracle);
    try (var source = catalog("SRC"); var target = catalog("DST")) {
      for (String id : List.of("SRC", "DST")) {
        var profile = mock(ConnectionProfile.class);
        when(store.get(id)).thenReturn(profile);
        when(profile.environment()).thenReturn("TEST");
        when(profile.role()).thenReturn("NORMAL");
        when(oracle.open(profile, "")).thenReturn(id.equals("SRC") ? source : target);
      }
      try (var sql = source.createStatement()) {
        sql.execute("INSERT INTO all_constraints VALUES ('SRC','DOC','R','FK_CLIENT','CRM','PK_CLIENT','ENABLED','VALIDATED')");
        sql.execute("INSERT INTO all_cons_columns VALUES ('SRC','FK_CLIENT','CLIENT_ID',1)");
      }
      var request = new TransferService.Request("SRC","DST","","","SRC","DOC","",1,List.of());
      var error = assertThrows(SchemaCompatibilityException.class, () -> service.preview(request));
      var body = new ApiErrors().schemaCompatibility(error).getBody();
      assertEquals("SCHEMA_COMPATIBILITY", body.get("code"));
      String message = body.get("message").toString();
      assertTrue(message.contains("Source SRC.DOC.CONTENT has unsupported data type BLOB"));
      assertTrue(message.contains("SRC.DOC / FK_CLIENT references another schema (CRM."));
      assertTrue(source.isClosed());
      assertTrue(target.isClosed());
    } finally {
      service.shutdown();
    }
  }

  private static Connection catalog(String owner) throws SQLException {
    Connection db = DriverManager.getConnection("jdbc:sqlite::memory:");
    Function.create(db, "SYS_CONTEXT", new Function() {
      protected void xFunc() throws SQLException { result(owner); }
    });
    try (var sql = db.createStatement()) {
      sql.execute("CREATE TABLE dual(dummy)");
      sql.execute("INSERT INTO dual VALUES (1)");
      sql.execute("CREATE TABLE all_tab_cols(owner,table_name,column_name,data_type,virtual_column,data_length,data_precision,data_scale,char_used,char_length,hidden_column,column_id)");
      sql.execute("INSERT INTO all_tab_cols VALUES ('" + owner + "','DOC','CONTENT','BLOB','NO',0,NULL,NULL,NULL,NULL,'NO',1)");
      sql.execute("CREATE TABLE all_constraints(owner,table_name,constraint_type,constraint_name,r_owner,r_constraint_name,status,validated)");
      sql.execute("CREATE TABLE all_cons_columns(owner,constraint_name,column_name,position)");
      sql.execute("CREATE TABLE all_triggers(table_owner,table_name,status)");
      sql.execute("CREATE TABLE all_tab_identity_cols(owner,table_name,column_name,generation_type)");
    }
    return db;
  }
}
