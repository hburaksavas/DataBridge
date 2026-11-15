import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  Database,
  Workflow,
  LayoutDashboard,
  Settings,
  Plus,
  Folder,
  ChevronRight,
  ChevronDown,
  Upload,
  Check,
  ShieldCheck,
  PlugZap,
  Search,
  ArrowRight,
  RefreshCw,
  Trash2,
  Save,
  FileCode2,
  Download,
  Network,
  AlertCircle,
  X,
  HardDrive,
  LockKeyhole,
  Server,
  Table2,
} from "lucide-react";
import "./style.css";
import "./soft.css";
import { Workbench } from "./Workbench";
import { ErrorDetails } from "./ErrorDetails";
import { failureMessage, networkFailure } from "./errors";
import { KeyPolicies, type KeyPolicy } from "./KeyPolicies";

export type Profile = {
  id?: string;
  name: string;
  folder: string;
  environment: string;
  username: string;
  mode: string;
  host: string;
  port: number;
  service: string;
  database: string;
  descriptor: string;
  role: string;
  color: string;
  readOnly: boolean;
  passwordSaved: boolean;
  origin: string;
};
type Pair = { child: string; parent: string };
export type Relation = {
  CHILD_OWNER: string;
  CHILD_TABLE: string;
  PARENT_OWNER: string;
  PARENT_TABLE: string;
  CONSTRAINT_NAME: string;
  DIRECTION: string;
  SOURCE: string;
  STATUS: string;
  VALIDATED: string;
  COLUMN_PAIRS: Pair[];
};
type Discovery = {
  owner: string;
  table: string;
  columns: Record<string, unknown>[];
  relations: Relation[];
  warnings: string[];
};
export type Scenario = {
  id?: string;
  name: string;
  owner: string;
  table: string;
  condition: string;
  limit: number;
  relations: Relation[];
  sourceId?: string;
  version?: number;
  keyPolicies?: Record<string, KeyPolicy>;
  graph?: import("./Diagram").DiagramData;
  tableFilters?: Record<string, string>;
};
type Alias = {
  alias: string;
  descriptor: string;
  host: string;
  port: number;
  service: string;
};
const token =
  new URLSearchParams(location.hash.slice(1)).get("token") ||
  sessionStorage.getItem("databridge.token") ||
  "";
if (token) sessionStorage.setItem("databridge.token", token);
history.replaceState(null, "", location.pathname);
async function api<T>(
  path: string,
  body?: unknown,
  method?: string,
): Promise<T> {
  const form = body instanceof FormData;
  const res = await fetch("/api" + path, {
    method: method || (body !== undefined ? "POST" : "GET"),
    headers: {
      Authorization: "Bearer " + token,
      ...(!form && body !== undefined
        ? { "Content-Type": "application/json" }
        : {}),
    },
    body: body === undefined ? undefined : form ? body : JSON.stringify(body),
  }).catch(() => {
    throw Error(networkFailure);
  });
  if (!res.ok) {
    let payload = {};
    try {
      payload = await res.json();
    } catch {}
    throw Error(
      failureMessage(
        payload && typeof payload === "object" ? payload : {},
        res.status,
      ),
    );
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
const fresh = (): Profile => ({
  name: "",
  folder: "Genel",
  environment: "TEST",
  username: "",
  mode: "BASIC",
  host: "",
  port: 1521,
  service: "",
  database: "",
  descriptor: "",
  role: "NORMAL",
  color: "#3467dc",
  readOnly: false,
  passwordSaved: false,
  origin: "MANUAL",
});
function App() {
  const [page, setPage] = useState("transfers"),
    [profiles, setProfiles] = useState<Profile[]>([]),
    [draft, setDraft] = useState<Profile>(fresh()),
    [password, setPassword] = useState(""),
    [remember, setRemember] = useState(false),
    [search, setSearch] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [toast, setToast] = useState(""),
    [test, setTest] = useState<Record<string, unknown> | null>(null),
    [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [importType, setImportType] = useState(""),
    [importRows, setImportRows] = useState<Profile[]>([]),
    [chosen, setChosen] = useState<number[]>([]),
    [warnings, setWarnings] = useState<string[]>([]),
    [importEnv, setImportEnv] = useState("TEST"),
    [importFolder, setImportFolder] = useState("Imported"),
    [locations, setLocations] = useState<{ path: string; exists: boolean }[]>(
      [],
    );
  const [sourceId, setSourceId] = useState(""),
    [schema, setSchema] = useState(""),
    [sourcePassword, setSourcePassword] = useState(""),
    [tables, setTables] = useState<Record<string, unknown>[]>([]),
    [table, setTable] = useState(""),
    [discovery, setDiscovery] = useState<Discovery | null>(null),
    [included, setIncluded] = useState<string[]>([]),
    [scenarioName, setScenarioName] = useState(""),
    [condition, setCondition] = useState(""),
    [limit, setLimit] = useState(100),
    [scenarioId, setScenarioId] = useState<string | undefined>();
  const [settings, setSettings] = useState<Record<string, unknown>>({});
  const [keyPolicies, setKeyPolicies] = useState<Record<string, KeyPolicy>>({});
  async function reload() {
    const [p, s] = await Promise.all([
      api<Profile[]>("/connections"),
      api<Scenario[]>("/scenarios"),
    ]);
    setProfiles(p);
    setScenarios(s);
  }
  useEffect(() => {
    reload().catch((e) => setError(e.message));
    api<Record<string, unknown>>("/status")
      .then(setSettings)
      .catch(() => {});
    api<typeof locations>("/tns/locations")
      .then(setLocations)
      .catch(() => {});
  }, []);
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(""), 5000);
    return () => clearTimeout(t);
  }, [toast]);
  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Operation failed.");
    } finally {
      setBusy(false);
    }
  }
  function change<K extends keyof Profile>(key: K, value: Profile[K]) {
    setDraft((d) => ({
      ...d,
      [key]: value,
      ...(key === "environment" && value === "PROD" ? { readOnly: true } : {}),
    }));
    setTest(null);
  }
  function select(p: Profile) {
    setDraft({ ...p });
    setPassword("");
    setRemember(p.passwordSaved);
    setTest(null);
    setError("");
  }
  function newProfile() {
    setDraft(fresh());
    setPassword("");
    setRemember(false);
    setTest(null);
    setPage("connections");
  }
  async function save() {
    await run(async () => {
      const p = await api<Profile>("/connections", {
        profile: draft,
        password,
        rememberPassword: remember,
        clearPassword: false,
      });
      select(p);
      await reload();
      setToast("Connection saved.");
    });
  }
  async function probe() {
    await run(async () => {
      const result = await api<Record<string, unknown>>("/connections/test", {
        profile: draft,
        password,
      });
      setTest(result);
      setToast("Oracle connection verified.");
    });
  }
  async function remove() {
    if (
      !draft.id ||
      !confirm(
        "Delete the “" +
          draft.name +
          "” connection? Database records will remain unchanged.",
      )
    )
      return;
    await run(async () => {
      await api("/connections/" + draft.id, undefined, "DELETE");
      newProfile();
      await reload();
      setToast("Connection deleted.");
    });
  }
  function openImport(type: string) {
    setImportType(type);
    setImportRows([]);
    setChosen([]);
    setWarnings([]);
    setImportFolder(type === "tns" ? "TNS" : "PLSQL Developer");
    setError("");
  }
  async function readImport(file: File) {
    await run(async () => {
      const form = new FormData();
      form.append("file", file);
      if (importType === "tns") {
        const r = await api<{ aliases: Alias[]; warnings: string[] }>(
          "/imports/tns",
          form,
        );
        setImportRows(
          r.aliases.map((a) => ({
            ...fresh(),
            name: a.alias,
            database: a.alias,
            mode: "TNS",
            host: a.host,
            port: a.port,
            service: a.service,
            descriptor: a.descriptor,
            origin: "TNS",
          })),
        );
        setChosen(r.aliases.map((_, i) => i));
        setWarnings(r.warnings);
      } else {
        const r = await api<{ profiles: Profile[]; warnings: string[] }>(
          "/imports/plsql",
          form,
        );
        const resolved = r.profiles.map((p) => {
          if (p.descriptor) return p;
          const known = profiles.find(
            (k) =>
              k.database.toLowerCase() === p.database.toLowerCase() &&
              k.descriptor,
          );
          return known
            ? {
                ...p,
                descriptor: known.descriptor,
                host: known.host,
                port: known.port,
                service: known.service,
              }
            : p;
        });
        setImportRows(resolved);
        setChosen(resolved.flatMap((p, i) => (p.descriptor ? [i] : [])));
        setWarnings(r.warnings);
      }
    });
  }
  async function commitImport() {
    await run(async () => {
      const selected = chosen.map((i) => ({
        ...importRows[i],
        id: undefined,
        environment: importEnv,
        folder: importFolder || importRows[i].folder,
        readOnly: importEnv === "PROD",
      }));
      if (!selected.length)
        throw Error("Select at least one resolved connection.");
      const saved = await api<Profile[]>("/imports/commit", selected);
      await reload();
      setImportType("");
      if (saved[0]) select(saved[0]);
      setToast(
        saved.length +
          " connections saved. Complete the username and password.",
      );
    });
  }
  async function loadTables() {
    await run(async () => {
      if (!sourceId) throw Error("Select a source connection.");
      const rows = await api<Record<string, unknown>[]>(
        "/connections/" + sourceId + "/tables",
        { password: sourcePassword, schema },
      );
      setTables(rows);
      setTable("");
      setDiscovery(null);
      if (!rows.length) setToast("No accessible tables found in this schema.");
    });
  }
  async function discover() {
    await run(async () => {
      if (!table) throw Error("Select a root table.");
      const row = tables.find((t) => t.TABLE_NAME === table);
      const r = await api<Discovery>("/connections/" + sourceId + "/discover", {
        password: sourcePassword,
        schema: row?.OWNER || schema,
        table,
      });
      setDiscovery(r);
      setIncluded(
        r.relations
          .filter((r) => r.STATUS === "ENABLED" && r.VALIDATED === "VALIDATED")
          .map((r) => r.CHILD_OWNER + "." + r.CONSTRAINT_NAME),
      );
      if (!scenarioName) setScenarioName(table + " and related data");
    });
  }
  async function saveScenario() {
    await run(async () => {
      if (!discovery) throw Error("Discover schema relationships first.");
      if (!Number.isInteger(limit) || limit < 1)
        throw Error("The row limit must be a positive integer.");
      for (const p of Object.values(keyPolicies)) {
        if (
          p.mode === "MAX" &&
          (!Number.isSafeInteger(p.start) || !Number.isSafeInteger(p.increment))
        )
          throw Error("MAX start and increment must be safe integers.");
      }
      await api("/scenarios", {
        id: scenarioId,
        name: scenarioName,
        owner: discovery.owner,
        table: discovery.table,
        condition,
        limit,
        sourceId,
        keyPolicies: Object.fromEntries(
          Object.entries(keyPolicies).filter(
            ([t]) =>
              t === discovery.table ||
              discovery.relations.some(
                (r) =>
                  included.includes(r.CHILD_OWNER + "." + r.CONSTRAINT_NAME) &&
                  (r.CHILD_TABLE === t || r.PARENT_TABLE === t),
              ),
          ),
        ),
        relations: discovery.relations.filter((r) =>
          included.includes(r.CHILD_OWNER + "." + r.CONSTRAINT_NAME),
        ),
      });
      await reload();
      setToast("Scenario saved.");
    });
  }
  async function expandRelation(relation: Relation) {
    await run(async () => {
      if (!discovery || relation.DIRECTION !== "CHILD") return;
      const result = await api<Discovery>(
        "/connections/" + sourceId + "/discover",
        {
          password: sourcePassword,
          schema: relation.CHILD_OWNER,
          table: relation.CHILD_TABLE,
        },
      );
      const known = new Set(
        discovery.relations.map((r) => r.CHILD_OWNER + "." + r.CONSTRAINT_NAME),
      );
      const additional = result.relations.filter(
        (r) =>
          r.DIRECTION === "CHILD" &&
          !known.has(r.CHILD_OWNER + "." + r.CONSTRAINT_NAME),
      );
      setDiscovery({
        ...discovery,
        relations: [...discovery.relations, ...additional],
      });
      setToast(
        additional.length +
          " child relationships found. Select those to include.",
      );
    });
  }
  function exportScenario(s: Scenario) {
    const { sourceId: _, ...shared } = s;
    const blob = new Blob([JSON.stringify(shared, null, 2)], {
      type: "application/json",
    });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "databridge-scenario.json";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }
  async function importScenario(file: File) {
    await run(async () => {
      if (file.size > 1000000)
        throw Error("Scenario files must be 1 MB or smaller.");
      const s = JSON.parse(await file.text());
      if (
        ![1, 2].includes(s.version) ||
        !s.name ||
        !s.owner ||
        !s.table ||
        !Array.isArray(s.relations)
      )
        throw Error("Choose a valid v1/v2 scenario file.");
      await api("/scenarios", { ...s, id: undefined, sourceId: undefined });
      await reload();
      setToast("Scenario imported. Source connection was not shared.");
    });
  }
  function editScenario(s: Scenario) {
    setScenarioId(s.id);
    setKeyPolicies(s.keyPolicies || {});
    setScenarioName(s.name);
    setCondition(s.condition || "");
    setLimit(s.limit || 100);
    setSourceId(s.sourceId || "");
    setSchema(s.owner);
    setTable(s.table);
    setTables([{ OWNER: s.owner, TABLE_NAME: s.table }]);
    setDiscovery({
      owner: s.owner,
      table: s.table,
      relations: s.relations,
      columns: [],
      warnings: ["Saved scenario loaded. Rediscover to refresh the schema."],
    });
    setIncluded(
      s.relations.map((r) => r.CHILD_OWNER + "." + r.CONSTRAINT_NAME),
    );
  }
  const folders = [...new Set(profiles.map((p) => p.folder))];
  const visible = profiles.filter((p) =>
    (p.name + " " + p.username + " " + p.database + " " + p.folder)
      .toLocaleLowerCase("tr")
      .includes(search.toLocaleLowerCase("tr")),
  );
  const navs = [
    { id: "transfers", name: "Data Transfer", icon: Network },
    { id: "connections", name: "Connections", icon: Database },
  ];
  return (
    <div className="shell">
      <header className="app-topbar">
        <div className="topbar-brand">
          <img src="/databridge.svg" alt="" width="32" height="32" />
          <div>
            <strong>DataBridge</strong>
            <span>Oracle data workspace</span>
          </div>
        </div>
        <nav className="topbar-nav" aria-label="Main navigation">
          {navs.map((n) => (
            <button
              key={n.id}
              className={page === n.id ? "active" : ""}
              aria-current={page === n.id ? "page" : undefined}
              onClick={() => {
                setPage(n.id);
                setError("");
              }}
            >
              <n.icon size={16} />
              {n.name}
              {n.id === "connections" && (
                <span className="nav-count">{profiles.length}</span>
              )}
            </button>
          ))}
        </nav>
      </header>
      <main>
        <div className="content">
          {error && (
            <div className="alert error" role="alert">
              <AlertCircle size={18} />
              <ErrorDetails message={error} />
              <button onClick={() => setError("")}>
                <X size={16} />
              </button>
            </div>
          )}
          {page === "connections" && (
            <div className="connections-page">
              <div className="page-title">
                <div>
                  <h1>Connections</h1>
                </div>
                <button className="primary" onClick={newProfile}>
                  <Plus size={16} />
                  New connection
                </button>
              </div>
              <div className="connection-workspace">
                <section className="connection-list panel">
                  <div className="list-toolbar">
                    <div className="search">
                      <Search size={15} />
                      <input
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        placeholder="Search connections…"
                      />
                    </div>
                  </div>
                  <div className="connection-tree">
                    {!profiles.length ? (
                      <div className="empty">
                        <Database size={36} />
                        <h3>Add your first connection</h3>
                        <p>
                          Configure a connection or import your tnsnames.ora
                          file.
                        </p>
                        <button onClick={() => openImport("tns")}>
                          <Upload size={15} />
                          Add from TNS
                        </button>
                      </div>
                    ) : (
                      folders.map((folder) => (
                        <div key={folder} className="folder-group">
                          <div className="folder-title">
                            <ChevronRight size={13} />
                            <Folder size={16} />
                            {folder}
                            <small>
                              {
                                visible.filter((p) => p.folder === folder)
                                  .length
                              }
                            </small>
                          </div>
                          {visible
                            .filter((p) => p.folder === folder)
                            .map((p) => (
                              <button
                                className={
                                  "connection-item " +
                                  (p.id === draft.id ? "selected" : "")
                                }
                                onClick={() => select(p)}
                                key={p.id}
                              >
                                <span
                                  className="connection-dot"
                                  style={{ background: p.color }}
                                />
                                <div>
                                  <strong>{p.name}</strong>
                                  <small>
                                    {p.username || "No username"}
                                    {p.database
                                      ? " @ " + p.database
                                      : p.service
                                        ? " @ " + p.service
                                        : ""}
                                  </small>
                                </div>
                                <span className={"env env-" + p.environment}>
                                  {p.environment}
                                </span>
                              </button>
                            ))}
                        </div>
                      ))
                    )}
                  </div>
                  <div className="import-actions">
                    <button onClick={() => openImport("tns")}>
                      <Upload size={15} />
                      Import TNS file
                    </button>
                    <button onClick={() => openImport("plsql")}>
                      <FileCode2 size={15} />
                      Import from PL/SQL Developer
                    </button>
                  </div>
                </section>
                <section className="connection-editor panel">
                  <div className="panel-heading">
                    <div className="section-icon">
                      <Database size={20} />
                    </div>
                    <div>
                      <h2>{draft.name || "New Oracle connection"}</h2>
                      <p>
                        {draft.id
                          ? "Edit connection"
                          : "Configure connection details"}
                      </p>
                    </div>
                    {draft.id && (
                      <button
                        className="icon-button danger"
                        title="Delete connection"
                        onClick={remove}
                        disabled={busy}
                      >
                        <Trash2 size={17} />
                      </button>
                    )}
                  </div>
                  <div className="form-body compact-connection-form">
                    <div className="connection-identity-grid">
                      <Field title="Display name">
                        <input
                          value={draft.name}
                          onChange={(e) => change("name", e.target.value)}
                          placeholder="e.g. Production – Credit"
                        />
                      </Field>
                      <Field title="Environment">
                        <select
                          value={draft.environment}
                          onChange={(e) =>
                            change("environment", e.target.value)
                          }
                        >
                          {["TEST", "UAT", "DEV", "PROD"].map((v) => (
                            <option key={v}>{v}</option>
                          ))}
                        </select>
                      </Field>
                    </div>
                    <div className="connection-address">
                      {" "}
                      <div className="segments">
                        {[
                          ["BASIC", "Direct"],
                          ["TNS", "TNS alias"],
                          ["DESCRIPTOR", "Connection descriptor"],
                        ].map(([v, l]) => (
                          <button
                            key={v}
                            className={draft.mode === v ? "selected" : ""}
                            onClick={() => change("mode", v)}
                          >
                            {l}
                          </button>
                        ))}
                      </div>
                      {draft.mode === "BASIC" ? (
                        <div className="form-grid server-grid">
                          <Field title="Host">
                            <input
                              value={draft.host}
                              onChange={(e) => change("host", e.target.value)}
                              placeholder="oracle.example.local"
                            />
                          </Field>
                          <Field title="Port">
                            <input
                              type="number"
                              min="1"
                              max="65535"
                              value={draft.port}
                              onChange={(e) =>
                                change("port", Number(e.target.value))
                              }
                            />
                          </Field>
                          <Field title="Service name">
                            <input
                              value={draft.service}
                              onChange={(e) =>
                                change("service", e.target.value)
                              }
                              placeholder="BANKDB"
                            />
                          </Field>
                        </div>
                      ) : (
                        <>
                          <Field
                            title={
                              draft.mode === "TNS"
                                ? "TNS alias"
                                : "Alias name (optional)"
                            }
                          >
                            <input
                              value={draft.database}
                              onChange={(e) =>
                                change("database", e.target.value)
                              }
                              placeholder="Import a TNS file to select an alias"
                            />
                          </Field>
                          <Field title="Oracle connection descriptor">
                            <textarea
                              rows={4}
                              value={draft.descriptor}
                              onChange={(e) =>
                                change("descriptor", e.target.value)
                              }
                              placeholder="(DESCRIPTION=(ADDRESS=…)(CONNECT_DATA=…))"
                            />
                          </Field>
                          <small className="helper">
                            The resolved TNS descriptor is saved, including RAC
                            and failover addresses.
                          </small>
                        </>
                      )}
                    </div>
                    <div className="connection-auth-grid">
                      <Field title="Username">
                        <input
                          autoComplete="off"
                          value={draft.username}
                          onChange={(e) => change("username", e.target.value)}
                          placeholder="BANK_READONLY"
                        />
                      </Field>
                      <Field title="Password">
                        <input
                          type="password"
                          autoComplete="new-password"
                          value={password}
                          onChange={(e) => setPassword(e.target.value)}
                          placeholder={
                            draft.passwordSaved
                              ? "Saved password will be used"
                              : "Oracle password"
                          }
                        />
                      </Field>
                    </div>
                    <div className="connection-options">
                      {" "}
                      <div className="checkbox-field">
                        <label>
                          <input
                            type="checkbox"
                            checked={remember}
                            onChange={(e) => setRemember(e.target.checked)}
                          />
                          Remember password (Windows protected)
                        </label>
                      </div>{" "}
                      <div className="read-only">
                        <label>
                          <input
                            type="checkbox"
                            checked={
                              draft.readOnly || draft.environment === "PROD"
                            }
                            disabled={draft.environment === "PROD"}
                            onChange={(e) =>
                              change("readOnly", e.target.checked)
                            }
                          />
                          Read-only source
                          {draft.environment === "PROD"
                            ? " · Required for production"
                            : ""}
                        </label>
                      </div>
                    </div>
                    <details className="connection-advanced">
                      <summary>
                        Advanced settings <ChevronDown size={13} />
                      </summary>
                      <div className="connection-advanced-grid">
                        <Field title="Folder">
                          <input
                            list="folders"
                            value={draft.folder}
                            onChange={(e) => change("folder", e.target.value)}
                          />
                          <datalist id="folders">
                            {folders.map((f) => (
                              <option key={f}>{f}</option>
                            ))}
                          </datalist>
                        </Field>
                        <Field title="Connection role">
                          <select
                            value={draft.role}
                            onChange={(e) => change("role", e.target.value)}
                          >
                            <option>NORMAL</option>
                            <option>SYSDBA</option>
                            <option>SYSOPER</option>
                          </select>
                        </Field>
                        <Field title="Accent color">
                          <div className="color-field">
                            <input
                              type="color"
                              value={draft.color}
                              onChange={(e) => change("color", e.target.value)}
                            />
                            <span>{draft.color}</span>
                          </div>
                        </Field>
                      </div>
                    </details>
                    {test && (
                      <div className="alert success">
                        <Check size={17} />
                        <span>
                          Connected · {String(test.user)} @{" "}
                          {String(test.database)} · {String(test.elapsedMs)} ms
                        </span>
                      </div>
                    )}
                  </div>
                  <footer className="editor-footer">
                    <span>
                      <LockKeyhole size={13} /> Passwords are never exported
                    </span>
                    <button onClick={probe} disabled={busy}>
                      <PlugZap size={16} />
                      {busy ? "Processing…" : "Test connection"}
                    </button>
                    <button
                      className="primary"
                      onClick={save}
                      disabled={busy || !draft.name}
                    >
                      <Save size={16} />
                      Save
                    </button>
                  </footer>
                </section>
              </div>
            </div>
          )}
          <div hidden={page !== "transfers"}>
            <Workbench
              profiles={profiles}
              scenarios={scenarios}
              api={api}
              onSaved={reload}
            />
          </div>
        </div>
      </main>
      {toast && (
        <div className="toast" role="status">
          <Check size={17} />
          {toast}
        </div>
      )}
      {importType && (
        <div className="modal-backdrop">
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="import-title"
          >
            <div className="modal-title">
              <div>
                <div className="overline">IMPORT CONNECTIONS</div>
                <h2 id="import-title">
                  {importType === "tns"
                    ? "Import TNS connections"
                    : "PL/SQL Developer connections"}
                </h2>
              </div>
              <button onClick={() => setImportType("")} className="icon-button">
                <X size={20} />
              </button>
            </div>
            <div className="modal-body">
              {importType === "plsql" && (
                <div className="note">
                  Readable INI/XML connection fields are supported. Encrypted
                  exports and saved passwords are excluded. Compatibility
                  depends on the export format of your version.
                </div>
              )}
              <label className="dropzone">
                <Upload size={25} />
                <strong>{busy ? "Reading file…" : "Choose file"}</strong>
                <span>
                  {importType === "tns"
                    ? "tnsnames.ora · Up to 5 MB"
                    : "Connection export file · Up to 5 MB"}
                </span>
                <input
                  type="file"
                  disabled={busy}
                  onChange={(e) => {
                    if (e.target.files?.[0]) readImport(e.target.files[0]);
                    e.target.value = "";
                  }}
                />
              </label>
              {error && (
                <div className="alert error">
                  <AlertCircle size={16} />
                  <ErrorDetails message={error} />
                </div>
              )}
              {warnings.map((w) => (
                <div className="note" key={w}>
                  {w}
                </div>
              ))}
              {importRows.length > 0 && (
                <>
                  <div className="form-grid">
                    <Field title="Destination folder">
                      <input
                        value={importFolder}
                        onChange={(e) => setImportFolder(e.target.value)}
                      />
                    </Field>
                    <Field title="Environment">
                      <select
                        value={importEnv}
                        onChange={(e) => setImportEnv(e.target.value)}
                      >
                        {["TEST", "UAT", "DEV", "PROD"].map((v) => (
                          <option key={v}>{v}</option>
                        ))}
                      </select>
                    </Field>
                  </div>
                  <div className="import-table">
                    <table>
                      <thead>
                        <tr>
                          <th />
                          <th>Connection</th>
                          <th>Database</th>
                          <th>Status</th>
                        </tr>
                      </thead>
                      <tbody>
                        {importRows.map((p, i) => (
                          <tr key={i}>
                            <td>
                              <input
                                type="checkbox"
                                disabled={!p.descriptor}
                                checked={chosen.includes(i)}
                                onChange={(e) =>
                                  setChosen((old) =>
                                    e.target.checked
                                      ? [...old, i]
                                      : old.filter((n) => n !== i),
                                  )
                                }
                              />
                            </td>
                            <td>
                              <strong>{p.name}</strong>
                              <small>
                                {p.username || "Username can be added later"}
                              </small>
                            </td>
                            <td>{p.database || p.service || "Descriptor"}</td>
                            <td>
                              {!p.descriptor
                                ? "Import the TNS alias first"
                                : profiles.some(
                                      (k) =>
                                        k.database === p.database &&
                                        k.username === p.username,
                                    )
                                  ? "Already exists · Creates a copy"
                                  : "Ready"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              )}
            </div>
            <footer>
              <span>{chosen.length} connections selected</span>
              <button onClick={() => setImportType("")}>Cancel</button>
              <button
                className="primary"
                onClick={commitImport}
                disabled={busy || !chosen.length}
              >
                <Check size={16} />
                Add selected
              </button>
            </footer>
          </section>
        </div>
      )}
    </div>
  );
}
function Field({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <label className="field">
      <span>{title}</span>
      {children}
    </label>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
