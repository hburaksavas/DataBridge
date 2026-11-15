package com.databridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class ErrorGuidanceTest {
  @ParameterizedTest
  @ValueSource(ints = {1,54,60,1017,28000,28001,12514,12505,12541,12154,12170,17002,
      3113,17008,942,1031,2291,2292,1400,12899,1438,1722,1861,8177,1013,1555,1466,1653,1536,2289,99999,0})
  void oracleFailuresIncludeUsefulStepsWithoutLeakingDriverText(int code) {
    var failure = ErrorGuidance.sql(new SQLException("password=SECRET; SQL SELECT PRIVATE_VALUE", "", code));
    assertEquals(code, failure.oracleCode());
    assertFalse(failure.steps().isEmpty());
    assertFalse(failure.body().toString().contains("SECRET"));
    assertFalse(failure.text().contains("PRIVATE_VALUE"));
    assertTrue(failure.text().contains("Reference:"));
  }

  @Test
  void connectionErrorsIdentifyTheProfileWithoutExposingTheJdbcDescriptor() {
    var failure = ErrorGuidance.sql(new OracleService.ConnectionFailure("Source test",
        new SQLException("jdbc:oracle:SECRET@host", "", 1017)));
    assertTrue(failure.message().contains("Connection: Source test"));
    assertFalse(failure.text().contains("SECRET"));
  }

  @Test
  void batchExceptionUsesTheUnderlyingConstraintCode() {
    var batch = new BatchUpdateException("batch wrapper", "", 17081, new int[0]);
    batch.setNextException(new SQLException("sensitive row value", "", 1));
    assertEquals("ORA-00001", ErrorGuidance.sql(batch).code());
    assertTrue(ErrorGuidance.sql(batch).message().contains("unique"));
  }

  @Test
  void validationAdviceMatchesTheActionRequired() {
    assertTrue(ErrorGuidance.validation("The preview has expired.").steps().getFirst().contains("Start"));
    assertTrue(ErrorGuidance.validation("Targets with enabled triggers are not supported yet: ORDERS").steps().getFirst().contains("trigger"));
    assertTrue(ErrorGuidance.validation("Up to 3 previews can be open.").steps().getFirst().contains("Close unused"));
    assertTrue(ErrorGuidance.validation("Primary key gerekiyor: ORDERS").steps().getFirst().contains("Key strategy"));
    assertTrue(ErrorGuidance.validation("Unknown column or unsupported filter expression: X").steps().getFirst().contains("filter"));
  }

  @RestController
  static class Failures {
    @GetMapping("/test/sql") Object sql() throws SQLException { throw new SQLException("SECRET", "", 12514); }
    @GetMapping("/test/validation") Object validation() { throw new IllegalArgumentException("The preview has expired."); }
    @GetMapping("/test/storage") Object storage() { throw new DataAccessResourceFailureException("SECRET file path"); }
    @GetMapping("/test/unexpected") Object unexpected() { throw new IllegalStateException("SECRET credentials"); }
    @PostMapping("/test/json") Object json(@RequestBody Map<String, Object> body) { return body; }
  }

  @Test
  void allControllersUseTheSameStructuredErrorContract() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new Failures()).setControllerAdvice(new ApiErrors()).build();
    mvc.perform(get("/test/sql")).andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ORA-12514"))
        .andExpect(jsonPath("$.oracleCode").value(12514))
        .andExpect(jsonPath("$.steps[0]").isString());
    mvc.perform(get("/test/validation")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("The preview has expired."))
        .andExpect(jsonPath("$.steps[0]").isString());
    mvc.perform(get("/test/storage")).andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("LOCAL_STORAGE"));
    var unexpected = mvc.perform(get("/test/unexpected")).andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("UNEXPECTED")).andReturn();
    assertFalse(unexpected.getResponse().getContentAsString().contains("SECRET"));
    mvc.perform(post("/test/json").contentType(MediaType.APPLICATION_JSON).content("{bad"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }
}
