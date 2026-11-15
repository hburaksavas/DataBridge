package com.databridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class MetadataScopeTest {
  @Test
  void tableBrowserRejectsUnscopedRequestsBeforeOpeningAConnection() {
    var store = mock(ProfileStore.class);
    var oracle = new OracleService(store);
    for (String schema : new String[] {null, "", "   ", "APP;DROP"}) {
      assertThrows(IllegalArgumentException.class,
          () -> oracle.tables("source", "password", schema));
    }
    verifyNoInteractions(store);
  }
}
