package com.databridge;

import java.sql.SQLException;
import java.util.*;

/** User-facing explanations. Never expose JDBC messages, SQL text or connection secrets. */
final class ErrorGuidance {
  private ErrorGuidance() {}

  record Failure(String code, String message, List<String> steps, Integer oracleCode) {
    Map<String, Object> body() {
      var result = new LinkedHashMap<String, Object>();
      result.put("code", code);
      result.put("message", message);
      result.put("steps", steps);
      if (oracleCode != null) result.put("oracleCode", oracleCode);
      return result;
    }

    String text() {
      return message + "\n" + String.join("\n", steps.stream().map(s -> "• " + s).toList())
          + "\nReference: " + code;
    }
  }

  static Failure sql(SQLException exception) {
    // BatchUpdateException and JDBC wrappers may carry the useful code in a nested exception.
    var pending = new ArrayDeque<Throwable>();
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    pending.add(exception);
    int code = exception.getErrorCode();
    while (!pending.isEmpty()) {
      var current = pending.remove();
      if (!seen.add(current)) continue;
      if (current instanceof SQLException sql) {
        if (known(sql.getErrorCode())) { code = sql.getErrorCode(); break; }
        if (sql.getNextException() != null) pending.add(sql.getNextException());
      }
      if (current.getCause() != null) pending.add(current.getCause());
    }
    String message;
    List<String> steps;
    switch (code) {
      case 1017 -> {
        message = "Oracle rejected the username or password.";
        steps = List.of("Open Connections and verify the username and password, including letter case.", "If the password changed, replace the saved password. Test the connection before selecting Start again.");
      }
      case 28000, 28001, 28002 -> {
        message = code == 28000 ? "The Oracle account is locked." : "The Oracle password has expired or requires attention.";
        steps = List.of("Ask your database administrator to check the account status and password expiry.", "After the account is available, update the password in Connections and test the connection.");
      }
      case 12514, 12505 -> {
        message = "The Oracle listener does not recognize the requested database service or SID.";
        steps = List.of("In Connections, compare Service name or the TNS descriptor with the database connection details.", "Ask your DBA to confirm that the PDB/service is running and registered with the listener, then test the connection.");
      }
      case 12541, 12543, 12154, 12170, 17002 -> {
        message = "The Oracle connection could not be established.";
        steps = List.of("Check the host, port, service name and TNS descriptor in Connections.", "Check VPN/network access and ask your DBA to verify the listener. Test the connection before retrying.");
      }
      case 3113, 3114, 3135, 17008, 17410 -> {
        message = "The Oracle connection was interrupted.";
        steps = List.of("Check network/VPN access and test both connections.", "If a transfer was starting or running, inspect Execution, History and the target data before retrying to avoid duplicate writes.");
      }
      case 942, 1031 -> {
        message = "A required table is missing or the connected user lacks permission.";
        steps = List.of("Check the selected table's OWNER.TABLE_NAME and confirm that the table exists in the connected service.", "Ask the owner/DBA to verify SELECT access on the source and the required write access on the target. A schema filter does not grant permissions.", "Reload Source tables and select Start to run validation again.");
      }
      case 1 -> {
        message = "A primary key or unique value conflicts with existing target data.";
        steps = List.of("Select the affected table and review Key strategy and its conflict policy.", "Use an appropriate sequence/identity or business-key mapping for this table; verify that generated keys remain unique.", "Review the target data before creating a new transfer preview.");
      }
      case 2291, 2292 -> {
        message = "The target rejected a relationship because parent and child records do not match.";
        steps = List.of("Check the relationship's parent/child column pairs and key mapping.", "Include the required parent records or confirm that matching parents already exist on the target, then select Start again.");
      }
      case 1400, 12899, 1438, 1722, 1861, 1830 -> {
        message = "A value does not fit the target column's type, length, precision or nullability.";
        steps = List.of("Compare the source and target column definitions on the affected table.", "Check required values, numeric precision, text lengths and date formats. Correct the schema/data or narrow the selection, then select Start again.");
      }
      case 54, 60, 8177 -> {
        message = "Another transaction prevented the transfer from acquiring the required locks or snapshot.";
        steps = List.of("Wait for the other transaction to complete; ask your DBA to inspect persistent blocking.", "Select Start to create a fresh preview when the target is available. Do not terminate other sessions without checking with their owner.");
      }
      case 1013, 1555, 1466 -> {
        message = "The operation timed out, was cancelled, or its source snapshot is no longer usable.";
        steps = List.of("Reduce the root row limit or narrow table filters for large selections.", "Check connection stability and schema changes with your DBA, then select Start for a fresh preview.");
      }
      case 1653, 1654, 1536 -> {
        message = "Oracle could not allocate enough space for the target data or indexes.";
        steps = List.of("Ask your DBA to check the target tablespace and the target user's quota.", "After space is available, select Start to validate the transfer again.");
      }
      case 2289 -> {
        message = "The configured target sequence is missing or inaccessible.";
        steps = List.of("Select the table, open Key strategy and verify the sequence name and owner.", "Ask the sequence owner to verify access for the target user, then select Start again.");
      }
      default -> {
        message = "Oracle could not complete the operation.";
        steps = List.of("Test the source and target connections and review the selected table and key strategy.", "Share the reference code below with your DBA. If a transfer was running, inspect Execution/History and target data before retrying.");
      }
    }
    if (exception instanceof OracleService.ConnectionFailure connection)
      message += "\nConnection: " + connection.connectionName;
    return new Failure(code == 0 ? "ORACLE_JDBC" : String.format(Locale.ROOT, "ORA-%05d", code), message, steps, code);
  }

  private static boolean known(int code) {
    return Set.of(1017,28000,28001,28002,12514,12505,12541,12543,12154,12170,17002,
        3113,3114,3135,17008,17410,942,1031,1,2291,2292,1400,12899,1438,1722,1861,1830,
        54,60,8177,1013,1555,1466,1653,1654,1536,2289).contains(code);
  }

  static Failure validation(String message) {
    message = Objects.requireNonNullElse(message, "The request could not be validated.");
    String text = message.toLowerCase(Locale.ROOT);
    List<String> steps;
    if (text.contains("same database/schema"))
      steps = List.of("Choose a target connection whose actual database or schema differs from the source table owner.", "Two connection profiles pointing to the same database and schema cannot be used as source and target.");
    else if (text.contains("3 previews"))
      steps = List.of("Close unused preview windows to release their snapshots, or wait for them to expire.", "Then select Start for the scenario you want to transfer.");
    else if (text.contains("limit") || text.contains("too complex") || text.contains("up to"))
      steps = List.of("Reduce the root Row limit, narrow table filters or split the scenario into smaller scopes.", "Remove unnecessary tables and relationships, then select Start to check the smaller selection.");
    else if (text.contains("preview") || text.contains("snapshot"))
      steps = List.of("Close this window and select Start to prepare a fresh preview.", "Review the new row counts and key strategies, then confirm with Start transfer.");
    else if (text.contains("trigger"))
      steps = List.of("The target table has enabled triggers that this transfer mode does not support.", "Use a compatible test target or ask your DBA to review its trigger behavior before changing the target configuration.");
    else if (text.contains("primary key") || text.contains("key strategy") || text.contains("sequence") || text.contains("identity") || text.contains("business key"))
      steps = List.of("Select the named table and open Key strategy. Check the key columns, sequence/identity and conflict policy.", "Confirm that source and target key definitions are compatible, then select Start again.");
    else if (text.contains("filter") || text.contains("condition"))
      steps = List.of("Check the root/table filter and use actual column names from Properties.", "Use comparisons, AND/OR, IN, LIKE or IS NULL; do not enter a SELECT statement or SQL functions. Select Start after correcting it.");
    else if (text.contains("match") || text.contains("data type") || text.contains("column"))
      steps = List.of("Compare the named table's source and target column definitions, types, primary keys and foreign keys.", "Review column pairs in the relationship editor and key strategies, then select Start again.");
    else if (text.contains("relationship") || text.contains("schema") || text.contains("accessible") || text.contains("system table") || text.contains("cyclic"))
      steps = List.of("Reload Source tables and check OWNER.TABLE_NAME, table access and relationship direction.", "Keep one source owner per scenario and connect each selected table to the root without cycles. Oracle system tables are excluded.");
    else if (text.contains("password") || text.contains("username") || text.contains("host") || text.contains("service") || text.contains("connection"))
      steps = List.of("Open Connections and complete the host, port, service, username and password fields.", "Test the connection, then return to the transfer and select the intended source and target.");
    else if (text.contains("target") || text.contains("role"))
      steps = List.of("Choose a writable TEST/UAT/DEV target with the NORMAL role, separate from the source schema.", "Check the target connection settings, then select Start again.");
    else if (text.contains("one transfer") || text.contains("active transfer"))
      steps = List.of("Open Actions > Execution or History and check the current transfer.", "Wait for it to finish or cancel it before starting another transfer.");
    else if (text.contains("scenario") || text.contains("name"))
      steps = List.of("Enter a unique scenario name and select a root table before saving.", "For imports, use a DataBridge scenario JSON file and keep the original file for comparison.");
    else
      steps = List.of("Check the highlighted settings and the named table or field.", "Correct the selection or input, then retry the operation. For transfers, Start creates a new validation preview.");
    return new Failure("VALIDATION", message, steps, null);
  }
}
