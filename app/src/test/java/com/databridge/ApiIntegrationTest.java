package com.databridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
  static final String AUTH = "Bearer test-token";
  static final Path TEMP;

  static {
    try {
      TEMP = Files.createTempDirectory("databridge-tests-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    System.setProperty("databridge.token", "test-token");
    System.setProperty("databridge.data-dir", TEMP.toString());
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("databridge.data-dir", () -> TEMP.toString());
    r.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEMP.resolve("test.db"));
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired ProfileStore store;

  ConnectionProfile profile(String name) {
    return new ConnectionProfile(
        null,
        name,
        "Prod",
        "PROD",
        "reader",
        "BASIC",
        "localhost",
        1521,
        "BANK",
        "",
        "",
        "NORMAL",
        "#3467dc",
        false,
        false,
        "MANUAL");
  }

  @Test
  void rejectsMissingTokenAndForeignOrigin() throws Exception {
    mvc.perform(get("/api/connections")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/connections")
                .header("Authorization", AUTH)
                .header("Origin", "https://evil.example"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/status").header("Authorization", AUTH)).andExpect(status().isOk());
  }

  @Test
  void persistsProfilesAndForcesProductionReadOnly() throws Exception {
    var p = profile("production");
    String response =
        mvc.perform(
                post("/api/connections")
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("profile", p, "password", "", "rememberPassword", false))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.readOnly").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var saved = mapper.readValue(response, ConnectionProfile.class);
    assertEquals("production", store.get(saved.id()).name());
    mvc.perform(delete("/api/connections/" + saved.id()).header("Authorization", AUTH))
        .andExpect(status().isOk());
  }

  @Test
  void savesWindowsProtectedPasswordWithoutReturningIt() throws Exception {
    var saved = store.save(profile("protected"), "unit-test-only-secret", true, false);
    assertTrue(saved.passwordSaved());
    assertEquals("unit-test-only-secret", store.password(saved.id()));
    String output =
        mvc.perform(get("/api/connections").header("Authorization", AUTH))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(output.contains("unit-test-only-secret"));
    assertFalse(
        new String(
                Files.readAllBytes(TEMP.resolve("test.db")),
                java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains("unit-test-only-secret"));
    store.save(saved, null, false, true);
    assertEquals("", store.password(saved.id()));
  }

  @Test
  void previewsTnsBeforeSaving() throws Exception {
    var file =
        new MockMultipartFile(
            "file",
            "tnsnames.ora",
            "text/plain",
            "BANK=(DESCRIPTION=(ADDRESS=(HOST=db)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=BANK)))"
                .getBytes());
    mvc.perform(multipart("/api/imports/tns").file(file).header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aliases[0].alias").value("BANK"));
  }

  @Test
  void importValidatesAllRowsBeforeWriting() throws Exception {
    int before = store.all().size();
    var invalid =
        new ConnectionProfile(
            null,
            "bad",
            "",
            "TEST",
            "",
            "BASIC",
            "bad/host",
            1521,
            "x",
            "",
            "",
            "NORMAL",
            "",
            false,
            false,
            "");
    mvc.perform(
            post("/api/imports/commit")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(List.of(profile("must-not-save"), invalid))))
        .andExpect(status().isBadRequest());
    assertEquals(before, store.all().size());
  }

  @Test
  void refusesOracleProbeWithoutCredentials() throws Exception {
    mvc.perform(
            post("/api/connections/test")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("profile", profile("no-credentials"), "password", ""))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void commitsTnsDescriptorAndKeepsAliasesIndependent() throws Exception {
    String descriptor =
        "(DESCRIPTION=(ADDRESS=(HOST=node1)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=BANK)))";
    var p =
        new ConnectionProfile(
            null,
            "BANK",
            "TNS",
            "TEST",
            "reader",
            "TNS",
            "node1",
            1521,
            "BANK",
            "BANK",
            descriptor,
            "NORMAL",
            "#3467dc",
            false,
            false,
            "TNS");
    String result =
        mvc.perform(
                post("/api/imports/commit")
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(List.of(p))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].descriptor").value(descriptor))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var saved = mapper.readValue(result, ConnectionProfile[].class)[0];
    assertEquals("jdbc:oracle:thin:@" + descriptor, store.get(saved.id()).jdbcUrl());
  }

  @Test
  void scenarioPersistenceExcludesUnexpectedCredentialFields() throws Exception {
    Map<String, Object> scenario = new LinkedHashMap<>();
    scenario.put("name", "Kredi");
    scenario.put("owner", "BANK");
    scenario.put("table", "KREDI");
    scenario.put("condition", "DURUM = 'AKTIF'");
    scenario.put("limit", 100);
    scenario.put("relations", List.of());
    scenario.put("password", "do-not-store");
    String result =
        mvc.perform(
                post("/api/scenarios")
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(scenario)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(result.contains("do-not-store"));
    assertTrue(store.scenarios().stream().anyMatch(s -> s.get("name").equals("Kredi")));
    scenario.put("limit", 0);
    mvc.perform(
            post("/api/scenarios")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(scenario)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void savesV2KeyPoliciesWithoutUnknownCredentialFields() throws Exception {
    var request =
        Map.of(
            "name",
            "PK policy roundtrip",
            "owner",
            "BANK",
            "table",
            "KREDI",
            "limit",
            10,
            "relations",
            List.of(),
            "keyPolicies",
            Map.of(
                "KREDI",
                Map.of(
                    "mode",
                    "SEQUENCE",
                    "column",
                    "KREDI_ID",
                    "sequence",
                    "SEQ_KREDI",
                    "password",
                    "must-not-store")));
    var response =
        mvc.perform(
                post("/api/scenarios")
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.keyPolicies.KREDI.sequence").value("SEQ_KREDI"))
            .andExpect(jsonPath("$.keyPolicies.KREDI.onConflict").value("ERROR"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains("must-not-store"));
  }

  @Test
  void savesManualDiagramAndFiltersWithoutCredentials() throws Exception {
    var relation =
        Map.of(
            "CHILD_OWNER",
            "BANK",
            "CHILD_TABLE",
            "HAREKET",
            "PARENT_OWNER",
            "BANK",
            "PARENT_TABLE",
            "KREDI",
            "CONSTRAINT_NAME",
            "M_GRAPH",
            "DIRECTION",
            "CHILD",
            "SOURCE",
            "MANUAL",
            "COLUMN_PAIRS",
            List.of(Map.of("child", "REFERANS_ID", "parent", "KREDI_ID")));
    var request =
        Map.of(
            "name",
            "Diagram roundtrip",
            "owner",
            "BANK",
            "table",
            "KREDI",
            "limit",
            100,
            "relations",
            List.of(relation),
            "tableFilters",
            Map.of("HAREKET", "TIP='KREDI'"),
            "graph",
            Map.of(
                "tables",
                List.of("KREDI", "HAREKET"),
                "positions",
                Map.of("KREDI", Map.of("x", 40, "y", 60))),
            "password",
            "must-not-store");
    var response =
        mvc.perform(
                post("/api/scenarios")
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.relations[0].SOURCE").value("MANUAL"))
            .andExpect(jsonPath("$.graph.tables[1]").value("HAREKET"))
            .andExpect(jsonPath("$.graph.positions.KREDI.x").value(40.0))
            .andExpect(jsonPath("$.tableFilters.HAREKET").value("TIP='KREDI'"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains("must-not-store"));
  }

  @Test
  void scenarioNamesAreUniqueAndAnExistingScenarioCanBeUpdated() throws Exception {
    var body =
        new LinkedHashMap<String, Object>(
            Map.of(
                "name",
                "  Scenario Picker Unique Name  ",
                "owner",
                "BANK",
                "table",
                "KREDI",
                "limit",
                10,
                "relations",
                List.of()));
    var response =
        mvc.perform(
                post("/api/scenarios")
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Scenario Picker Unique Name"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var id = mapper.readTree(response).get("id").asText();
    body.put("name", "scenario picker unique name");
    mvc.perform(
            post("/api/scenarios")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
    body.put("id", id);
    mvc.perform(
            post("/api/scenarios")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isOk());
    body.remove("id");
    body.put("name", "Different Scenario Picker Name");
    var other =
        mvc.perform(
                post("/api/scenarios")
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    body.put("id", mapper.readTree(other).get("id").asText());
    body.put("name", "SCENARIO PICKER UNIQUE NAME");
    mvc.perform(
            post("/api/scenarios")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }
}
