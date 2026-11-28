package com.databridge;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TransferService {
  public record Pair(String child, String parent) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record Relation(
      @JsonProperty("CHILD_OWNER") String childOwner,
      @JsonProperty("CHILD_TABLE") String childTable,
      @JsonProperty("PARENT_OWNER") String parentOwner,
      @JsonProperty("PARENT_TABLE") String parentTable,
      @JsonProperty("CONSTRAINT_NAME") String constraint,
      @JsonProperty("DIRECTION") String direction,
      @JsonProperty("COLUMN_PAIRS") List<Pair> pairs,
      @JsonProperty("SOURCE") String source) {
    public Relation(
        String childOwner,
        String childTable,
        String parentOwner,
        String parentTable,
        String constraint,
        String direction,
        List<Pair> pairs) {
      this(childOwner, childTable, parentOwner, parentTable, constraint, direction, pairs, "FK");
    }
  }

  public record Request(
      String sourceId,
      String targetId,
      String sourcePassword,
      String targetPassword,
      String owner,
      String table,
      String condition,
      int limit,
      List<Relation> relations,
      Map<String, KeyPolicy> keyPolicies,
      Map<String, String> tableFilters) {
    public Request(
        String sourceId,
        String targetId,
        String sourcePassword,
        String targetPassword,
        String owner,
        String table,
        String condition,
        int limit,
        List<Relation> relations,
        Map<String, KeyPolicy> keyPolicies) {
      this(
          sourceId,
          targetId,
          sourcePassword,
          targetPassword,
          owner,
          table,
          condition,
          limit,
          relations,
          keyPolicies,
          Map.of());
    }

    public Request(
        String sourceId,
        String targetId,
        String sourcePassword,
        String targetPassword,
        String owner,
        String table,
        String condition,
        int limit,
        List<Relation> relations) {
      this(
          sourceId,
          targetId,
          sourcePassword,
          targetPassword,
          owner,
          table,
          condition,
          limit,
          relations,
          Map.of());
    }

    public Request {
      Map<String, KeyPolicy> clean = new LinkedHashMap<>();
      if (keyPolicies != null) {
        if (keyPolicies.size() > 25)
          throw new IllegalArgumentException("Up to 25 table strategies are supported.");
        keyPolicies.forEach(
            (name, policy) -> {
              if (policy == null)
                throw new IllegalArgumentException("Invalid key strategy.");
              clean.put(OracleService.identifier(name), policy.normalized());
            });
      }
      keyPolicies = Map.copyOf(clean);
      Map<String, String> filters = new LinkedHashMap<>();
      if (tableFilters != null) {
        if (tableFilters.size() > 25)
          throw new IllegalArgumentException("Up to 25 table filters are supported.");
        tableFilters.forEach(
            (name, filter) ->
                filters.put(OracleService.identifier(name), filter == null ? "" : filter));
      }
      tableFilters = Map.copyOf(filters);
    }
  }

  public record TableSummary(
      String table, long selected, List<String> primaryKey, KeyPolicy keyPolicy) {}

  public record Preview(
      String token,
      String expiresAt,
      String source,
      String target,
      List<TableSummary> tables,
      long total,
      List<String> warnings) {}

  public record RunRequest(String token) {}

  private record Column(String name, String type, String shape) {}

  private record Query(String sql, List<Object> binds) {}

  private record DataTable(
      String name,
      List<Column> columns,
      List<String> pk,
      Query selection,
      long count,
      KeyTransferEngine.Spec keySpec) {}

  private record Edge(String from, String to, Relation relation) {}

  private static class Plan implements AutoCloseable {
    final Connection source, target;
    final List<DataTable> tables;
    final String sourceOwner, targetOwner, sourceName, targetName;
    final Instant expires = Instant.now().plusSeconds(300);

    Plan(
        Connection s,
        Connection t,
        List<DataTable> tables,
        String sourceOwner,
        String owner,
        String sourceName,
        String targetName) {
      source = s;
      target = t;
      this.tables = tables;
      this.sourceOwner = sourceOwner;
      targetOwner = owner;
      this.sourceName = sourceName;
      this.targetName = targetName;
    }

    public void close() {
      try {
        source.close();
      } catch (Exception ignored) {
      }
      try {
        target.close();
      } catch (Exception ignored) {
      }
    }
  }

  private static class Job {
    final String id = UUID.randomUUID().toString();
    final AtomicBoolean cancelled = new AtomicBoolean();
    final Map<String, Object> report = new LinkedHashMap<>();
    volatile Statement active;

    synchronized Map<String, Object> snapshot() {
      return new LinkedHashMap<>(report);
    }

    synchronized void put(String key, Object value) {
      report.put(key, value);
    }

    void cancel() {
      cancelled.set(true);
      try {
        if (active != null) active.cancel();
      } catch (SQLException ignored) {
      }
    }
  }

  private final ProfileStore store;
  private final OracleService oracle;
  private final Map<String, Plan> plans = new ConcurrentHashMap<>();
  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService sweeper =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "transfer-plan-expiry");
            t.setDaemon(true);
            return t;
          });

  public TransferService(ProfileStore store, OracleService oracle) {
    this.store = store;
    this.oracle = oracle;
    sweeper.scheduleAtFixedRate(
        () ->
            plans.forEach(
                (key, plan) -> {
                  if (Instant.now().isAfter(plan.expires) && plans.remove(key, plan)) plan.close();
                }),
        15,
        15,
        TimeUnit.SECONDS);
  }

  public synchronized Preview preview(Request req) throws SQLException {
    if (plans.size() >= 3)
      throw new IllegalArgumentException(
          "Up to 3 previews can be open. Close an earlier preview or wait 5 minutes.");
    String owner = id(req.owner), root = id(req.table);
    if (req.limit < 1 || req.limit > 1_000_000)
      throw new IllegalArgumentException("The row limit must be between 1 and 1,000,000.");
    if (req.relations == null || req.relations.size() > 24)
      throw new IllegalArgumentException("Up to 24 relationships are supported in this version.");
    var src = store.get(req.sourceId);
    var dst = store.get(req.targetId);
    if (dst.readOnly() || dst.environment().equals("PROD") || !dst.role().equals("NORMAL"))
      throw new IllegalArgumentException(
          "The target must be writable, in TEST/UAT/DEV, and use the NORMAL role.");
    if (!src.role().equals("NORMAL"))
      throw new IllegalArgumentException(
          "Source transfers require the NORMAL role; SYS privileges are not used.");
    Connection source = oracle.open(src, req.sourcePassword), target = null;
    try {
      target = oracle.open(dst, req.targetPassword);
      oracle.readOnly(source);
      oracle.readOnly(target);
      String targetOwner = one(target, "SELECT SYS_CONTEXT('USERENV','SESSION_USER') FROM dual");
      id(targetOwner);
      if (identity(source).equals(identity(target)) && owner.equals(targetOwner))
        throw new IllegalArgumentException("Source and target cannot use the same database/schema.");
      List<Edge> edges = new ArrayList<>();
      Set<String> nodes = new LinkedHashSet<>();
      nodes.add(root);
      Set<String> findings = new LinkedHashSet<>();
      for (Relation raw : req.relations) {
        if (raw == null) throw new IllegalArgumentException("Invalid relationship.");
        if (owner.equals(id(raw.childOwner))) nodes.add(id(raw.childTable));
        if (owner.equals(id(raw.parentOwner))) nodes.add(id(raw.parentTable));
        Relation r;
        try {
          r = validateRelation(source, owner, raw);
        } catch (IllegalArgumentException ex) {
          findings.add("Selected relationship " + id(raw.childOwner) + "." + id(raw.childTable)
              + " / " + id(raw.constraint) + ": " + ex.getMessage());
          continue;
        }
        String from = r.direction.equals("PARENT") ? r.childTable : r.parentTable,
            to = r.direction.equals("PARENT") ? r.parentTable : r.childTable;
        if (from.equals(to))
          findings.add("Selected relationship " + r.constraint + " returns to " + from + ".");
        edges.add(new Edge(from, to, r));
      }
      Map<String, List<Column>> columns = new LinkedHashMap<>();
      Map<String, List<String>> keys = new LinkedHashMap<>();
      Map<String, KeyTransferEngine.Spec> keySpecs = new LinkedHashMap<>();
      if (!nodes.containsAll(req.tableFilters.keySet()))
        findings.add("A filtered table must be connected to the root table.");
      if (!nodes.containsAll(req.keyPolicies.keySet()))
        findings.add("A strategy table must be in the selected scope.");
      for (String table : nodes) {
        try {
          oracle.requireApplicationTable(source, owner, table);
          var cols = columns(source, owner, table, message -> findings.add("Source " + message));
          if (cols.isEmpty())
            throw new IllegalArgumentException("Source table is not accessible: " + table);
          var pk = primaryKey(source, owner, table);
          if (pk.isEmpty()) findings.add("Source " + owner + "." + table + " requires an enabled primary key.");
          columns.put(table, cols);
          keys.put(table, pk);
          KeyPolicy policy = req.keyPolicies.getOrDefault(table, KeyPolicy.defaults());
          oracle.requireApplicationTable(target, targetOwner, table);
          checkTarget(target, targetOwner, table, cols, pk, policy,
              message -> findings.add("Target " + message));
          checkForeignKeys(source, target, owner, targetOwner, table, findings::add);
          if (pk.isEmpty()) continue;
          KeyTransferEngine.Spec spec =
              new KeyTransferEngine.Spec(
                  table,
                  cols.stream().map(Column::name).toList(),
                  pk,
                  cols.stream().collect(Collectors.toMap(Column::name, Column::type)),
                  combinedForeignKeys(source, owner, table, edges),
                  policy,
                  0);
          keySpecs.put(table, spec);
          KeyTransferEngine.validate(target, targetOwner, spec);
        } catch (IllegalArgumentException ex) {
          findings.add(table + ": " + ex.getMessage());
        }
      }
      List<String> order = List.of();
      try {
        order = insertionOrder(target, targetOwner, nodes, edges);
      } catch (IllegalArgumentException ex) {
        findings.add(ex.getMessage());
      }
      if (!findings.isEmpty()) throw new SchemaCompatibilityException(findings);
      var filter =
          SelectionFilter.compile(
              req.condition,
              columns.get(root).stream().map(Column::name).collect(Collectors.toSet()));
      var rootExtra =
          SelectionFilter.compile(
              req.tableFilters.getOrDefault(root, ""),
              columns.get(root).stream().map(Column::name).collect(Collectors.toSet()));
      List<Object> rootBinds = new ArrayList<>(filter.parameters());
      rootBinds.addAll(rootExtra.parameters());
      Map<String, Query> selections = new LinkedHashMap<>();
      String projection = projection(columns.get(root), "t");
      selections.put(
          root,
          new Query(
              "SELECT "
                  + projection
                  + " FROM "
                  + qualified(owner, root)
                  + " t WHERE "
                  + "("
                  + filter.sql()
                  + ") AND ("
                  + rootExtra.sql()
                  + ")"
                  + " ORDER BY "
                  + keys.get(root).stream().map(k -> "t." + q(k)).collect(Collectors.joining(","))
                  + " FETCH FIRST "
                  + req.limit
                  + " ROWS ONLY",
              rootBinds));
      Set<String> pending = new LinkedHashSet<>(nodes);
      pending.remove(root);
      while (!pending.isEmpty()) {
        boolean advanced = false;
        for (String table : new ArrayList<>(pending)) {
          var incoming = edges.stream().filter(e -> e.to.equals(table)).toList();
          if (incoming.isEmpty())
            throw new IllegalArgumentException(
                "Selected relationship is not reachable from the root: " + table);
          if (incoming.stream().anyMatch(e -> !selections.containsKey(e.from))) continue;
          List<Object> binds = new ArrayList<>();
          List<String> predicates = new ArrayList<>();
          for (Edge edge : incoming) {
            Query anchor = selections.get(edge.from);
            binds.addAll(anchor.binds);
            Relation r = edge.relation;
            String match =
                r.pairs.stream()
                    .map(
                        pair ->
                            r.direction.equals("PARENT")
                                ? "t." + q(pair.parent) + "=a." + q(pair.child)
                                : "t." + q(pair.child) + "=a." + q(pair.parent))
                    .collect(Collectors.joining(" AND "));
            predicates.add("EXISTS (SELECT 1 FROM (" + anchor.sql + ") a WHERE " + match + ")");
          }
          String selectionSql =
              "SELECT "
                  + projection(columns.get(table), "t")
                  + " FROM "
                  + qualified(owner, table)
                  + " t WHERE "
                  + "("
                  + String.join(" OR ", predicates)
                  + ") AND ("
                  + SelectionFilter.compile(
                          req.tableFilters.getOrDefault(table, ""),
                          columns.get(table).stream().map(Column::name).collect(Collectors.toSet()))
                      .sql()
                  + ")"
                  + " ORDER BY "
                  + keys.get(table).stream().map(k -> "t." + q(k)).collect(Collectors.joining(","));
          binds.addAll(
              SelectionFilter.compile(
                      req.tableFilters.getOrDefault(table, ""),
                      columns.get(table).stream().map(Column::name).collect(Collectors.toSet()))
                  .parameters());
          if (selectionSql.length() > 200000 || binds.size() > 5000)
            throw new IllegalArgumentException(
                "Selection paths are too complex. Split the scenario into smaller scopes.");
          selections.put(table, new Query(selectionSql, binds));
          pending.remove(table);
          advanced = true;
        }
        if (!advanced)
          throw new IllegalArgumentException(
              "Selection relationships contain a cycle or are disconnected from the root.");
      }
      if (edges.stream().anyMatch(e -> e.to.equals(root)))
        throw new IllegalArgumentException(
            "Selection paths returning to the root table are not supported.");
      List<DataTable> data = new ArrayList<>();
      List<TableSummary> summary = new ArrayList<>();
      long total = 0;
      for (String table : order) {
        Query query = selections.get(table);
        long count =
            count(source, new Query("SELECT COUNT(*) FROM (" + query.sql + ")", query.binds));
        total += count;
        if (total > 5_000_000)
          throw new IllegalArgumentException(
              "The total scope is limited to 5 million rows in this version.");
        var base = keySpecs.get(table);
        var spec =
            new KeyTransferEngine.Spec(
                base.table(),
                base.columns(),
                base.pk(),
                base.types(),
                base.foreignKeys(),
                base.policy(),
                count);
        data.add(new DataTable(table, columns.get(table), keys.get(table), query, count, spec));
        summary.add(new TableSummary(table, count, keys.get(table), base.policy()));
      }
      String token = UUID.randomUUID().toString();
      Plan plan = new Plan(source, target, data, owner, targetOwner, src.name(), dst.name());
      plans.put(token, plan);
      return new Preview(
          token,
          plan.expires.toString(),
          src.name(),
          dst.name(),
          summary,
          total,
          List.of(
              "Source keys are preserved by default; a conflict rolls back the transfer. Skipping requires"
                  + " an explicit SKIP strategy.",
              "Generated keys are mapped to related records. Consumed sequence/identity values"
                  + " are not restored on rollback.",
              "MAX+1 takes no additional allocation lock. Concurrent key conflicts trigger"
                  + " a rollback.",
              "The source preview uses a consistent snapshot retained for 5 minutes. Target changes"
                  + " are evaluated when the transfer runs.",
              "All target writes share one transaction and are rolled back on error"
                  + " or cancellation.",
              "Unselected parent records must exist in the target. Oracle foreign key constraints"
                  + " enforce this requirement.",
              "Masking is not implemented yet. Ensure the target environment is appropriate"
                  + " for the data being transferred."));
    } catch (Exception e) {
      try {
        source.close();
      } catch (Exception ignored) {
      }
      if (target != null)
        try {
          target.close();
        } catch (Exception ignored) {
        }
      throw e;
    }
  }

  public void discard(String token) {
    Plan p = plans.remove(token);
    if (p != null) p.close();
  }

  public synchronized Map<String, Object> start(String token) {
    if (jobs.values().stream()
        .anyMatch(j -> Set.of("RUNNING", "QUEUED").contains(j.snapshot().get("status"))))
      throw new IllegalArgumentException("Only one transfer can run at a time.");
    Plan plan = plans.remove(token);
    if (plan == null)
      throw new IllegalArgumentException("Preview not found or already used. Preview again.");
    if (Instant.now().isAfter(plan.expires)) {
      plan.close();
      throw new IllegalArgumentException("The preview has expired.");
    }
    Job job = new Job();
    job.put("id", job.id);
    job.put("status", "QUEUED");
    job.put("source", plan.sourceName);
    job.put("target", plan.targetName);
    job.put("startedAt", Instant.now().toString());
    job.put("total", plan.tables.stream().mapToLong(DataTable::count).sum());
    job.put("processed", 0L);
    job.put("inserted", 0L);
    job.put("skipped", 0L);
    job.put("table", "");
    jobs.put(job.id, job);
    try {
      persist(job);
      executor.submit(() -> execute(plan, job));
    } catch (RuntimeException e) {
      jobs.remove(job.id);
      plan.close();
      throw e;
    }
    return job.snapshot();
  }

  public List<Map<String, Object>> history() {
    return store.transferHistory().stream()
        .map(
            r -> {
              Job active = jobs.get(r.get("id"));
              if (active != null) return active.snapshot();
              if (Set.of("RUNNING", "QUEUED").contains(r.get("status"))) {
                r.put("status", "UNKNOWN");
                r.put(
                    "message",
                    "Interrupted in an earlier application session. Check the target data and"
                        + " preview again.");
              }
              return r;
            })
        .toList();
  }

  public Map<String, Object> job(String id) {
    Job j = jobs.get(id);
    return j == null
        ? store.transferHistory().stream()
            .filter(r -> id.equals(r.get("id")))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Transfer not found."))
        : j.snapshot();
  }

  public void cancel(String id) {
    Job job = jobs.get(id);
    if (job == null) throw new IllegalArgumentException("No active transfer found.");
    job.cancel();
  }

  private void execute(Plan plan, Job job) {
    long processed = 0, inserted = 0, skipped = 0;
    List<Map<String, Object>> tables = new ArrayList<>();
    boolean committed = false;
    boolean commitAttempted = false;
    KeyTransferEngine keyEngine = null;
    try {
      plan.target.rollback();
      plan.target.setAutoCommit(false);
      for (DataTable table : plan.tables) {
        checkCancelled(job);
        try (var lock = plan.target.createStatement()) {
          lock.setQueryTimeout(10);
          lock.execute(
              "LOCK TABLE "
                  + qualified(plan.targetOwner, table.name)
                  + " IN ROW EXCLUSIVE MODE NOWAIT");
        }
        checkTarget(
            plan.target,
            plan.targetOwner,
            table.name,
            table.columns,
            table.pk,
            table.keySpec.policy(), TransferService::reject);
        KeyTransferEngine.validate(plan.target, plan.targetOwner, table.keySpec);
        checkForeignKeys(plan.source, plan.target, plan.sourceOwner, plan.targetOwner, table.name, TransferService::reject);
      }
      job.put("status", "RUNNING");
      persist(job);
      boolean needsMapping =
          plan.tables.stream()
              .anyMatch(
                  t ->
                      !t.keySpec.policy().mode().equals("KEEP")
                          || t.keySpec.foreignKeys().stream()
                              .anyMatch(
                                  fk ->
                                      plan.tables.stream()
                                          .anyMatch(
                                              parent ->
                                                  parent.name.equals(fk.parent())
                                                      && !parent.pk.equals(fk.parentColumns()))));
      if (needsMapping)
        keyEngine =
            new KeyTransferEngine(
                plan.target,
                plan.targetOwner,
                plan.tables.stream().map(DataTable::keySpec).toList(),
                statement -> job.active = statement);
      for (DataTable table : plan.tables) {
        checkCancelled(job);
        job.put("table", table.name);
        long tableInserted = 0, tableSkipped = 0, tableProcessed = 0;
        if (keyEngine != null) {
          try (var read = prepare(plan.source, table.selection)) {
            read.setFetchSize(Math.min(250, Math.max(1, 4000 / table.columns.size())));
            job.active = read;
            try (var rows = read.executeQuery()) {
              while (rows.next()) {
                checkCancelled(job);
                boolean added =
                    keyEngine.row(
                        table.name,
                        KeyTransferEngine.read(
                            rows, table.keySpec.columns(), table.keySpec.types()));
                tableProcessed++;
                if (added) tableInserted++;
                else tableSkipped++;
                if (tableProcessed % 250 == 0) {
                  job.put("processed", processed + tableProcessed);
                  job.put("inserted", inserted + tableInserted);
                  job.put("skipped", skipped + tableSkipped);
                  persist(job);
                }
                job.active = read;
              }
            }
          }
        } else {
          int batchSize = Math.min(500, Math.max(1, 4000 / table.columns.size()));
          try (var read = prepare(plan.source, table.selection);
              var write =
                  plan.target.prepareStatement(
                      table.keySpec.policy().onConflict().equals("SKIP")
                          ? mergeSql(plan.targetOwner, table)
                          : insertSql(plan.targetOwner, table))) {
            read.setFetchSize(batchSize);
            write.setQueryTimeout(60);
            job.active = read;
            try (var rs = read.executeQuery()) {
              int batch = 0;
              while (rs.next()) {
                checkCancelled(job);
                for (int i = 0; i < table.columns.size(); i++)
                  bindValue(write, i + 1, rs, i + 1, table.columns.get(i));
                write.addBatch();
                batch++;
                if (batch == batchSize) {
                  job.active = write;
                  long adds = executeBatch(write);
                  tableInserted += adds;
                  tableSkipped += batch - adds;
                  tableProcessed += batch;
                  batch = 0;
                  job.put("processed", processed + tableProcessed);
                  job.put("inserted", inserted + tableInserted);
                  job.put("skipped", skipped + tableSkipped);
                  persist(job);
                  job.active = read;
                }
              }
              if (batch > 0) {
                job.active = write;
                long adds = executeBatch(write);
                tableInserted += adds;
                tableSkipped += batch - adds;
                tableProcessed += batch;
              }
            }
          }
        }
        processed += tableProcessed;
        inserted += tableInserted;
        skipped += tableSkipped;
        tables.add(
            Map.of(
                "table",
                table.name,
                "processed",
                tableProcessed,
                "inserted",
                tableInserted,
                "skipped",
                tableSkipped));
        job.put("processed", processed);
        job.put("inserted", inserted);
        job.put("skipped", skipped);
        persist(job);
      }
      checkCancelled(job);
      commitAttempted = true;
      plan.target.commit();
      committed = true;
      job.put("status", "COMPLETED");
      job.put("committed", true);
      job.put("tables", tables);
      job.put("message", "Transfer completed.");
      job.put(
          "keyPolicies",
          plan.tables.stream().collect(Collectors.toMap(DataTable::name, t -> t.keySpec.policy())));
    } catch (Exception e) {
      if (e instanceof SQLException sql) job.put("oracleCode", sql.getErrorCode());
      boolean rolledBack = false;
      try {
        plan.target.rollback();
        rolledBack = true;
      } catch (SQLException ignored) {
      }
      boolean uncertain = commitAttempted || !rolledBack;
      job.put("status", uncertain ? "UNKNOWN" : job.cancelled.get() ? "CANCELLED" : "FAILED");
      job.put("committed", uncertain ? null : false);
      job.put("attemptedInserted", inserted);
      job.put("inserted", 0L);
      job.put("skipped", 0L);
      var failure = uncertain
          ? new ErrorGuidance.Failure("TRANSFER_OUTCOME_UNKNOWN",
              "The final transaction result could not be verified.",
              List.of("Do not start another transfer until the target data has been reconciled.",
                  "Check Execution/History and ask your DBA to verify whether the target transaction committed.",
                  "Compare the affected rows and key values before preparing a new transfer."), null)
          : e instanceof SQLException sql ? ErrorGuidance.sql(sql)
          : ErrorGuidance.validation(e instanceof IllegalArgumentException ? e.getMessage()
              : "The transfer could not complete. Review the affected table and target constraints.");
      job.put("message", job.cancelled.get() && !uncertain
          ? "Cancelled. Target writes were rolled back. Review the selection before starting again."
          : (uncertain ? "" : "Transfer failed. Target writes were rolled back.\n") + failure.text());
      job.put("failure", failure.body());
    } finally {
      job.active = null;
      if (keyEngine != null) keyEngine.close();
      plan.close();
      job.put("finishedAt", Instant.now().toString());
      persist(job);
    }
  }

  private static long executeBatch(PreparedStatement statement) throws SQLException {
    long count = 0;
    for (int result : statement.executeBatch()) {
      if (result == Statement.SUCCESS_NO_INFO)
        throw new SQLException("The batch result could not be confirmed. The transaction will be rolled back.");
      if (result < 0) throw new SQLException("Batch insert failed.");
      count += result;
    }
    return count;
  }

  private void persist(Job j) {
    store.saveTransfer(j.snapshot());
  }

  private static void checkCancelled(Job j) {
    if (j.cancelled.get()) throw new CancellationException();
  }

  private static void bindValue(
      PreparedStatement s, int index, ResultSet rs, int col, Column metadata) throws SQLException {
    String type = metadata.type;
    switch (type) {
      case "NUMBER", "FLOAT" -> s.setBigDecimal(index, rs.getBigDecimal(col));
      case "DATE", "TIMESTAMP" -> s.setTimestamp(index, rs.getTimestamp(col));
      case "RAW" -> s.setBytes(index, rs.getBytes(col));
      case "BINARY_FLOAT" -> {
        float value = rs.getFloat(col);
        if (rs.wasNull()) s.setNull(index, Types.REAL);
        else s.setFloat(index, value);
      }
      case "BINARY_DOUBLE" -> {
        double value = rs.getDouble(col);
        if (rs.wasNull()) s.setNull(index, Types.DOUBLE);
        else s.setDouble(index, value);
      }
      case "NVARCHAR2", "NCHAR" -> s.setNString(index, rs.getNString(col));
      default -> s.setString(index, rs.getString(col));
    }
  }

  private static String insertSql(String owner, DataTable table) {
    return "INSERT INTO "
        + qualified(owner, table.name)
        + " ("
        + table.columns.stream().map(c -> q(c.name)).collect(Collectors.joining(","))
        + ") VALUES ("
        + String.join(",", Collections.nCopies(table.columns.size(), "?"))
        + ")";
  }

  private static String mergeSql(String owner, DataTable table) {
    String
        aliases =
            table.columns.stream().map(c -> "? " + q(c.name)).collect(Collectors.joining(",")),
        match =
            table.pk.stream()
                .map(k -> "t." + q(k) + "=s." + q(k))
                .collect(Collectors.joining(" AND ")),
        cols = table.columns.stream().map(c -> q(c.name)).collect(Collectors.joining(",")),
        values = table.columns.stream().map(c -> "s." + q(c.name)).collect(Collectors.joining(","));
    return "MERGE INTO "
        + qualified(owner, table.name)
        + " t USING (SELECT "
        + aliases
        + " FROM dual) s ON ("
        + match
        + ") WHEN NOT MATCHED THEN INSERT ("
        + cols
        + ") VALUES ("
        + values
        + ")";
  }

  private static String id(String name) {
    return OracleService.identifier(name);
  }

  private static String q(String name) {
    return "\"" + id(name) + "\"";
  }

  private static String qualified(String owner, String table) {
    return q(owner) + "." + q(table);
  }

  private static String projection(List<Column> cols, String alias) {
    return cols.stream().map(c -> alias + "." + q(c.name)).collect(Collectors.joining(","));
  }

  private static PreparedStatement prepare(Connection c, Query query) throws SQLException {
    var s = c.prepareStatement(query.sql);
    s.setQueryTimeout(60);
    try {
      for (int i = 0; i < query.binds.size(); i++) s.setObject(i + 1, query.binds.get(i));
      return s;
    } catch (Exception e) {
      s.close();
      throw e;
    }
  }

  private static long count(Connection c, Query query) throws SQLException {
    try (var s = prepare(c, query);
        var r = s.executeQuery()) {
      r.next();
      return r.getLong(1);
    }
  }

  private static String one(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      r.next();
      return r.getString(1);
    }
  }

  private static String identity(Connection c) throws SQLException {
    return one(
        c,
        "SELECT"
            + " SYS_CONTEXT('USERENV','DB_UNIQUE_NAME')||':'||SYS_CONTEXT('USERENV','CON_NAME')||':'||SYS_CONTEXT('USERENV','SERVER_HOST')"
            + " FROM dual");
  }

  private static List<Column> columns(Connection c, String owner, String table)
      throws SQLException {
    return columns(c, owner, table, TransferService::reject);
  }

  private static void reject(String message) {
    throw new IllegalArgumentException(message);
  }

  private static List<Column> columns(Connection c, String owner, String table, Consumer<String> issue)
      throws SQLException {
    List<Column> cols = new ArrayList<>();
    try (var s =
        c.prepareStatement(
            "SELECT"
                + " column_name,data_type,virtual_column,data_length,data_precision,data_scale,char_used,char_length"
                + " FROM all_tab_cols WHERE owner=? AND table_name=? AND hidden_column='NO' ORDER"
                + " BY column_id")) {
      s.setString(1, owner);
      s.setString(2, table);
      try (var rs = s.executeQuery()) {
        while (rs.next()) {
          if (rs.getString(3).equals("YES"))
            issue.accept(owner + "." + table + "." + rs.getString(1) + " is a virtual column.");
          String type = rs.getString(2).replaceAll("\\(\\d+\\)", "");
          if (!Set.of(
                  "NUMBER",
                  "FLOAT",
                  "VARCHAR2",
                  "NVARCHAR2",
                  "CHAR",
                  "NCHAR",
                  "DATE",
                  "TIMESTAMP",
                  "RAW",
                  "BINARY_FLOAT",
                  "BINARY_DOUBLE")
              .contains(type))
            issue.accept(owner + "." + table + "." + rs.getString(1) + " has unsupported data type " + type + ".");
          cols.add(
              new Column(
                  rs.getString(1),
                  type,
                  java.util.stream.IntStream.rangeClosed(4, 8)
                      .mapToObj(
                          i -> {
                            try {
                              return Objects.toString(rs.getString(i), "-");
                            } catch (SQLException ex) {
                              throw new IllegalStateException(ex);
                            }
                          })
                      .collect(Collectors.joining("|"))));
        }
      }
    }
    return cols;
  }

  private static List<String> primaryKey(Connection c, String owner, String table)
      throws SQLException {
    List<String> keys = new ArrayList<>();
    try (var s =
        c.prepareStatement(
            "SELECT cc.column_name FROM all_constraints k JOIN all_cons_columns cc ON"
                + " cc.owner=k.owner AND cc.constraint_name=k.constraint_name WHERE k.owner=? AND"
                + " k.table_name=? AND k.constraint_type='P' AND k.status='ENABLED' ORDER BY"
                + " cc.position")) {
      s.setString(1, owner);
      s.setString(2, table);
      try (var r = s.executeQuery()) {
        while (r.next()) keys.add(r.getString(1));
      }
    }
    return keys;
  }

  private static void checkTarget(
      Connection c,
      String owner,
      String table,
      List<Column> source,
      List<String> keys,
      KeyPolicy policy, Consumer<String> issue)
      throws SQLException {
    var target = columns(c, owner, table, issue);
    if (!source.equals(target) || !keys.equals(primaryKey(c, owner, table)))
      issue.accept(
          "Source and target columns, types and primary keys must match: " + table);
    if (count(
            c,
            new Query(
                "SELECT COUNT(*) FROM all_triggers WHERE table_owner=? AND table_name=? AND"
                    + " status='ENABLED'",
                List.of(owner, table)))
        > 0)
      issue.accept(
          "Targets with enabled triggers are not supported yet: " + table);
    if (count(
            c,
            new Query(
                "SELECT COUNT(*) FROM all_tab_identity_cols WHERE owner=? AND table_name=? AND"
                    + " generation_type='ALWAYS' AND column_name<>?",
                List.of(owner, table, policy.mode().equals("IDENTITY") ? policy.column() : " ")))
        > 0)
      issue.accept(
          "GENERATED ALWAYS identity is not supported for this strategy: " + table);
  }

  private static void checkForeignKeys(
      Connection source, Connection target, String sourceOwner, String targetOwner, String table, Consumer<String> issue)
      throws SQLException {
    if (!foreignKeyShapes(source, sourceOwner, table, message -> issue.accept("Source " + message))
        .equals(foreignKeyShapes(target, targetOwner, table, message -> issue.accept("Target " + message))))
      issue.accept("Source and target foreign key structures must match: " + table);
  }

  private static List<String> foreignKeyShapes(Connection connection, String owner, String table, Consumer<String> issue)
      throws SQLException {
    Map<String, StringBuilder> shapes = new TreeMap<>();
    try (var statement =
        connection.prepareStatement(
            "SELECT"
                + " fk.constraint_name,fk.r_owner,pk.table_name,fc.column_name,pc.column_name,fk.status,fk.validated"
                + " FROM all_constraints fk LEFT JOIN all_constraints pk ON pk.owner=fk.r_owner AND"
                + " pk.constraint_name=fk.r_constraint_name JOIN all_cons_columns fc ON"
                + " fc.owner=fk.owner AND fc.constraint_name=fk.constraint_name LEFT JOIN"
                + " all_cons_columns pc ON pc.owner=pk.owner AND"
                + " pc.constraint_name=pk.constraint_name AND pc.position=fc.position WHERE"
                + " fk.owner=? AND fk.table_name=? AND fk.constraint_type='R' ORDER BY"
                + " fk.constraint_name,fc.position")) {
      statement.setString(1, owner);
      statement.setString(2, table);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          String constraint = owner + "." + table + " / " + rows.getString(1);
          if (!owner.equals(rows.getString(2)))
            issue.accept(constraint + " references another schema (" + rows.getString(2)
                + "." + Objects.toString(rows.getString(3), "(inaccessible parent)") + ").");
          if (!"ENABLED".equals(rows.getString(6)) || !"VALIDATED".equals(rows.getString(7)))
            issue.accept(constraint + " is " + rows.getString(6) + " / " + rows.getString(7) + ".");
          shapes
              .computeIfAbsent(rows.getString(1), k -> new StringBuilder())
              .append(rows.getString(3))
              .append(':')
              .append(rows.getString(4))
              .append('=')
              .append(rows.getString(5))
              .append(';');
        }
      }
    }
    return shapes.values().stream().map(StringBuilder::toString).sorted().toList();
  }

  private static List<KeyTransferEngine.ForeignKey> combinedForeignKeys(
      Connection c, String owner, String table, List<Edge> edges) throws SQLException {
    List<KeyTransferEngine.ForeignKey> result =
        new ArrayList<>(KeyTransferEngine.foreignKeys(c, owner, table));
    for (Edge edge : edges) {
      Relation r = edge.relation;
      if (!r.source.equals("MANUAL") || !r.childTable.equals(table)) continue;
      var manual =
          new KeyTransferEngine.ForeignKey(
              r.parentTable,
              r.pairs.stream().map(Pair::child).toList(),
              r.pairs.stream().map(Pair::parent).toList());
      for (var existing : result)
        if (!Collections.disjoint(existing.childColumns(), manual.childColumns())
            && !existing.equals(manual))
          throw new IllegalArgumentException(
              "Conflicting relationship mappings for the same column: " + table);
      if (!result.contains(manual)) result.add(manual);
    }
    return List.copyOf(result);
  }

  private static Relation validateRelation(Connection c, String owner, Relation raw)
      throws SQLException {
    if (raw == null || raw.pairs == null || raw.pairs.isEmpty() || raw.pairs.size() > 32)
      throw new IllegalArgumentException("Invalid relationship.");
    Relation r =
        new Relation(
            id(raw.childOwner),
            id(raw.childTable),
            id(raw.parentOwner),
            id(raw.parentTable),
            id(raw.constraint),
            raw.direction,
            raw.pairs.stream().map(p -> new Pair(id(p.child), id(p.parent))).toList(),
            raw.source == null ? "FK" : raw.source);
    if (!owner.equals(r.childOwner)
        || !owner.equals(r.parentOwner)
        || !Set.of("PARENT", "CHILD").contains(r.direction))
      throw new IllegalArgumentException(
          "Only directed relationships within the same schema are supported in this version.");
    if (r.source.equals("MANUAL")) {
      var child = columns(c, owner, r.childTable);
      var parent = columns(c, owner, r.parentTable);
      Map<String, String>
          childTypes = child.stream().collect(Collectors.toMap(Column::name, Column::type)),
          parentTypes = parent.stream().collect(Collectors.toMap(Column::name, Column::type));
      for (Pair pair : r.pairs) {
        if (!childTypes.containsKey(pair.child)
            || !parentTypes.containsKey(pair.parent)
            || !childTypes.get(pair.child).equals(parentTypes.get(pair.parent)))
          throw new IllegalArgumentException(
              "Custom relationship columns must exist and have matching types.");
      }
      if (r.pairs.stream().map(Pair::child).distinct().count() != r.pairs.size()
          || r.pairs.stream().map(Pair::parent).distinct().count() != r.pairs.size())
        throw new IllegalArgumentException("Columns cannot be repeated in a custom relationship.");
      var spec =
          new KeyTransferEngine.Spec(
              r.parentTable,
              parent.stream().map(Column::name).toList(),
              primaryKey(c, owner, r.parentTable),
              parentTypes,
              List.of(),
              new KeyPolicy(
                      "BUSINESS",
                      "",
                      "",
                      null,
                      null,
                      List.of(),
                      r.pairs.stream().map(Pair::parent).toList(),
                      "ERROR")
                  .normalized(),
              0);
      KeyTransferEngine.validate(c, owner, spec);
      return r;
    }
    if (!r.source.equals("FK"))
      throw new IllegalArgumentException("Relationship source must be FK or MANUAL.");
    List<Pair> actual = new ArrayList<>();
    try (var s =
        c.prepareStatement(
            "SELECT fc.column_name,pc.column_name,pk.owner,pk.table_name FROM all_constraints fk"
                + " JOIN all_constraints pk ON pk.owner=fk.r_owner AND"
                + " pk.constraint_name=fk.r_constraint_name JOIN all_cons_columns fc ON"
                + " fc.owner=fk.owner AND fc.constraint_name=fk.constraint_name JOIN"
                + " all_cons_columns pc ON pc.owner=pk.owner AND"
                + " pc.constraint_name=pk.constraint_name AND pc.position=fc.position WHERE"
                + " fk.owner=? AND fk.table_name=? AND fk.constraint_name=? AND"
                + " fk.constraint_type='R' AND fk.status='ENABLED' AND fk.validated='VALIDATED'"
                + " ORDER BY fc.position")) {
      s.setString(1, owner);
      s.setString(2, r.childTable);
      s.setString(3, r.constraint);
      try (var rows = s.executeQuery()) {
        while (rows.next()) {
          if (!rows.getString(3).equals(r.parentOwner) || !rows.getString(4).equals(r.parentTable))
            throw new IllegalArgumentException("The foreign key target changed. Discover it again.");
          actual.add(new Pair(rows.getString(1), rows.getString(2)));
        }
      }
    }
    if (!actual.equals(r.pairs))
      throw new IllegalArgumentException(
          "Unable to verify the foreign key. Discover it again: " + r.constraint);
    return r;
  }

  private static List<String> insertionOrder(
      Connection c, String owner, Set<String> tables, List<Edge> edges) throws SQLException {
    Map<String, Set<String>> parents = new LinkedHashMap<>();
    for (String table : tables) parents.put(table, new LinkedHashSet<>());
    for (Edge edge : edges)
      if (edge.relation.source.equals("MANUAL"))
        parents.get(edge.relation.childTable).add(edge.relation.parentTable);
    try (var s =
        c.prepareStatement(
            "SELECT fk.table_name,pk.table_name FROM all_constraints fk JOIN all_constraints pk ON"
                + " pk.owner=fk.r_owner AND pk.constraint_name=fk.r_constraint_name WHERE"
                + " fk.owner=? AND pk.owner=? AND fk.constraint_type='R' AND"
                + " fk.status='ENABLED'")) {
      s.setString(1, owner);
      s.setString(2, owner);
      try (var r = s.executeQuery()) {
        while (r.next())
          if (tables.contains(r.getString(1)) && tables.contains(r.getString(2)))
            parents.get(r.getString(1)).add(r.getString(2));
      }
    }
    List<String> order = new ArrayList<>();
    while (order.size() < tables.size()) {
      boolean advanced = false;
      for (String table : tables)
        if (!order.contains(table) && order.containsAll(parents.get(table))) {
          order.add(table);
          advanced = true;
        }
      if (!advanced)
        throw new IllegalArgumentException(
            "Target foreign keys contain a cycle. Tables awaiting insertion: " + tables.stream()
                .filter(t -> !order.contains(t)).collect(Collectors.joining(", ")));
    }
    return order;
  }

  @PreDestroy
  public void shutdown() {
    sweeper.shutdownNow();
    plans.values().forEach(Plan::close);
    plans.clear();
    jobs.values().forEach(Job::cancel);
    executor.close();
  }
}
