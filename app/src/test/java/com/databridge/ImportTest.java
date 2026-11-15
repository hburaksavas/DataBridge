package com.databridge;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImportTest {
  @Test
  void parsesRacAndMultipleAliasesWithoutLosingDescriptor() {
    var result =
        new TnsParser()
            .parse(
                """
                # Test aliases
                BANK, BANK.WORLD =
                  (DESCRIPTION=(FAILOVER=ON)
                    (ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=node1)(PORT=1521))
                                  (ADDRESS=(PROTOCOL=TCP)(HOST=node2)(PORT=1522)))
                    (CONNECT_DATA=(SERVICE_NAME=BANKDB)))
                IFILE=extra.ora
                """);
    assertEquals(2, result.aliases().size());
    assertEquals("BANK.WORLD", result.aliases().get(1).alias());
    assertTrue(result.aliases().getFirst().descriptor().contains("HOST=node2"));
    assertEquals("BANKDB", result.aliases().getFirst().service());
    assertEquals(1, result.warnings().size());
  }

  @Test
  void rejectsBrokenAndDuplicateTns() {
    assertThrows(
        IllegalArgumentException.class, () -> new TnsParser().parse("BANK=(DESCRIPTION=(HOST=x)"));
    assertThrows(
        IllegalArgumentException.class, () -> new TnsParser().parse("A=(HOST=x)\na=(HOST=y)"));
  }

  @Test
  void quotesAndCommentsAreHandled() {
    var r =
        new TnsParser()
            .parse(
                "A=(DESCRIPTION=(ADDRESS=(HOST=\"a#b\")(PORT=1521)) # comment\n"
                    + "(CONNECT_DATA=(SID=X)))");
    assertEquals("a#b", r.aliases().getFirst().host());
    assertEquals("X", r.aliases().getFirst().service());
  }

  @Test
  void readableIniMetadataDropsPasswordsAndScripts() {
    var r =
        new ConnectionImporter()
            .parse(
                "[Connection1]\nName=Kredi\nUsername=reader\nDatabase=BANK\nPassword=SHOULD_NOT_IMPORT\nFolder=Prod\nConnectAs=1\nInitialization=delete from X"
                    .getBytes(StandardCharsets.UTF_8));
    assertEquals(1, r.profiles().size());
    var p = r.profiles().getFirst();
    assertEquals("reader", p.username());
    assertEquals("SYSDBA", p.role());
    assertFalse(p.passwordSaved());
    assertFalse(p.toString().contains("SHOULD_NOT_IMPORT"));
    assertEquals("Prod", p.folder());
  }

  @Test
  void indexedIniRecordsAreSeparated() {
    var r =
        new ConnectionImporter()
            .parse(
                "[Connections]\nName0=A\nDatabase0=BANK1\nUsername0=u1\nName1=B\nDatabase1=BANK2\nUsername1=u2"
                    .getBytes(StandardCharsets.UTF_8));
    assertEquals(2, r.profiles().size());
  }

  @Test
  void rejectsUnrecognizedExportsAndXmlExternalEntities() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionImporter().parse("opaque encrypted blob".getBytes()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectionImporter()
                .parse(
                    "<!DOCTYPE a [<!ENTITY e SYSTEM 'file:///secret'>]><connections><connection><Database>&e;</Database></connection></connections>"
                        .getBytes()));
  }

  @Test
  void decodesUtf16Bom() {
    byte[] text = "[Connection]\nName=Üretim\nDatabase=BANK".getBytes(StandardCharsets.UTF_16LE);
    byte[] data = new byte[text.length + 2];
    data[0] = (byte) 255;
    data[1] = (byte) 254;
    System.arraycopy(text, 0, data, 2, text.length);
    assertEquals("Üretim", new ConnectionImporter().parse(data).profiles().getFirst().name());
  }
}
