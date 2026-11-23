import { useEffect, useMemo, useRef, useState } from "react";
import {
  Plus,
  Save,
  Play,
  Upload,
  Download,
  Search,
  RefreshCw,
  Network,
  Link2,
  X,
  Trash2,
  Flag,
  KeyRound,
  Table2,
  Square,
  ChevronDown,
  History,
  Check,
  AlertCircle,
  Wand2,
} from "lucide-react";
import type { Profile, Scenario, Relation } from "./main";
import {
  Diagram,
  arrange,
  connected,
  orient,
  edgeKey,
  type DiagramData,
} from "./Diagram";
import { ScenarioPicker } from "./ScenarioPicker";
import { ErrorDetails } from "./ErrorDetails";
import { defaultPolicy, type KeyPolicy } from "./keyPolicy";
type Api = <T>(path: string, body?: unknown, method?: string) => Promise<T>;
type Meta = {
  owner: string;
  table: string;
  primaryKey: string[];
  columns: { COLUMN_NAME: string; DATA_TYPE: string; NULLABLE: string }[];
  relations: Relation[];
  warnings: string[];
};
type Draft = Scenario & {
  graph: DiagramData;
  tableFilters: Record<string, string>;
  keyPolicies: Record<string, KeyPolicy>;
};
type Preview = {
  token: string;
  expiresAt: string;
  total: number;
  tables: {
    table: string;
    selected: number;
    primaryKey: string[];
    keyPolicy: KeyPolicy;
  }[];
  warnings: string[];
};
type Job = {
  id: string;
  status: string;
  total: number;
  processed: number;
  inserted: number;
  skipped: number;
  table: string;
  message?: string;
  startedAt: string;
  source: string;
  target: string;
};
type Suggestion = {
  column: string;
  mode: string;
  sequence: string;
  reason: string;
  confidence: string;
};
const blank = (): Draft => ({
  name: "",
  owner: "",
  table: "",
  condition: "",
  limit: 1,
  relations: [],
  graph: { tables: [], positions: {} },
  keyPolicies: {},
  tableFilters: {},
});
const active = (j: Job | null) =>
  !!j && ["RUNNING", "QUEUED"].includes(j.status);
const labels: Record<string, string> = {
  RUNNING: "Running",
  QUEUED: "Queued",
  COMPLETED: "Completed",
  FAILED: "Rolled back",
  CANCELLED: "Cancelled",
  UNKNOWN: "Review required",
};
const fmt = (n: number) => new Intl.NumberFormat("en-GB").format(n);
function normalize(s: Scenario): Draft {
  const tables = [
    ...new Set(
      [
        ...(s.graph?.tables || []),
        s.table,
        ...s.relations.flatMap((r) => [r.PARENT_TABLE, r.CHILD_TABLE]),
      ].filter(Boolean),
    ),
  ];
  const positions = {
    ...arrange(s.table, tables, s.relations),
    ...s.graph?.positions,
  };
  return {
    id: s.id,
    name: s.name,
    sourceId: s.sourceId,
    owner: s.owner,
    table: s.table,
    condition: s.condition || "",
    limit: s.limit,
    version: 3,
    relations: s.relations.map((r) => ({
      CHILD_OWNER: r.CHILD_OWNER,
      CHILD_TABLE: r.CHILD_TABLE,
      PARENT_OWNER: r.PARENT_OWNER,
      PARENT_TABLE: r.PARENT_TABLE,
      CONSTRAINT_NAME: r.CONSTRAINT_NAME,
      DIRECTION: r.DIRECTION,
      SOURCE: r.SOURCE || "FK",
      STATUS: r.STATUS,
      VALIDATED: r.VALIDATED,
      COLUMN_PAIRS: r.COLUMN_PAIRS.map((p) => ({
        child: p.child,
        parent: p.parent,
      })),
    })),
    graph: { tables, positions },
    keyPolicies: Object.fromEntries(
      Object.entries(s.keyPolicies || {}).map(([table, p]) => [
        table,
        {
          mode: p.mode,
          column: p.column,
          sequence: p.sequence,
          start: p.start,
          increment: p.increment,
          groupColumns: p.groupColumns || [],
          businessColumns: p.businessColumns || [],
          onConflict: p.onConflict || "ERROR",
        },
      ]),
    ),
    tableFilters: s.tableFilters || {},
  };
}
export function Workbench({
  profiles,
  scenarios,
  api,
  onSaved,
}: {
  profiles: Profile[];
  scenarios: Scenario[];
  api: Api;
  onSaved: () => Promise<void>;
}) {
  const [draft, setDraft] = useState<Draft>(blank),
    [targetId, setTargetId] = useState(""),
    [tables, setTables] = useState<string[]>([]),
    [schemaFilter, setSchemaFilter] = useState(""),
    [search, setSearch] = useState(""),
    [meta, setMeta] = useState<Record<string, Meta>>({}),
    [selected, setSelected] = useState(""),
    [selectedEdge, setSelectedEdge] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [dirty, setDirty] = useState(false),
    [fitToken, setFitToken] = useState(0),
    [inspectorTab, setInspectorTab] = useState("table"),
    [addOpen, setAddOpen] = useState(false),
    [addName, setAddName] = useState(""),
    [linkOpen, setLinkOpen] = useState(false),
    [parent, setParent] = useState(""),
    [child, setChild] = useState(""),
    [pairs, setPairs] = useState<{ parent: string; child: string }[]>([
      { parent: "", child: "" },
    ]),
    [editEdge, setEditEdge] = useState(""),
    [suggestions, setSuggestions] = useState<Suggestion[]>([]),
    [preview, setPreview] = useState<Preview | null>(null),
    [job, setJob] = useState<Job | null>(null),
    [history, setHistory] = useState<Job[]>([]),
    [bottom, setBottom] = useState(""),
    [nodeFilter, setNodeFilter] = useState("");
  const tableCache = useRef(new Map<string, { at: number; rows: string[] }>());
  const metadataCache = useRef(new Map<string, { at: number; value: Meta }>());
  const cacheTtl = 5 * 60 * 1000;
  const [tableScroll, setTableScroll] = useState(0);
  const tableListRef = useRef<HTMLDivElement>(null);
  const filteredTables = useMemo(
    () => tables.filter((t) => t.includes(search.toUpperCase())),
    [tables, search],
  );
  const firstTable = Math.max(0, Math.floor(tableScroll / 36) - 5);
  useEffect(() => {
    setTables([]);
    setMeta({});
  }, [draft.sourceId, draft.owner]);
  useEffect(() => {
    setTableScroll(0);
    if (tableListRef.current) tableListRef.current.scrollTop = 0;
  }, [tables, search]);
  const draftRef = useRef(draft);
  draftRef.current = draft;
  const targetRef = useRef(targetId);
  targetRef.current = targetId;
  const revision = useRef(0),
    previewRevision = useRef(-1);
  useEffect(() => {
    if (profiles.length && !draftRef.current.sourceId) {
      const p = profiles.find((p) => p.readOnly) || profiles[0];
      const d = {
        ...draftRef.current,
        sourceId: p.id,
        owner: "",
      };
      draftRef.current = d;
      setDraft(d);
    }
    if (!targetRef.current) {
      const p = profiles.find(
        (p) => !p.readOnly && p.environment !== "PROD" && p.role === "NORMAL",
      );
      if (p) setTargetId(p.id!);
    }
  }, [profiles]);
  useEffect(() => {
    const token = preview?.token;
    return () => {
      if (token)
        api("/transfers/preview/" + token, undefined, "DELETE").catch(() => {});
    };
  }, [preview?.token]);
  useEffect(() => {
    // Closing the review also releases its snapshot, including a late response.
    if (bottom !== "preview" && preview) setPreview(null);
  }, [bottom, preview?.token]);
  useEffect(() => {
    if (!active(job)) return;
    let stopped = false;
    const jobId = job!.id;
    let timer = setTimeout(poll, 800);
    async function poll() {
      try {
        const updated = await api<Job>("/transfers/" + jobId);
        if (stopped) return;
        setJob(updated);
        if (!active(updated)) {
          api<Job[]>("/transfers").then(setHistory).catch(() => {});
          return;
        }
      } catch (e) {
        if (!stopped) setError((e as Error).message);
      }
      if (!stopped) timer = setTimeout(poll, 800);
    }
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [job?.id, job?.status]);
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(""), 4500);
    return () => clearTimeout(timer);
  }, [notice]);
  const [credentialRequest, setCredentialRequest] = useState<{
    id: string;
    name: string;
  } | null>(null);
  const [credentialValue, setCredentialValue] = useState("");
  const credentialDialog = useRef<HTMLDialogElement>(null);
  const sessionPasswords = useRef<Record<string, string>>({});
  const credentialResolver = useRef<((value: string | null) => void) | null>(
    null,
  );
  const pendingCredential = useRef<{
    id: string;
    promise: Promise<string>;
  } | null>(null);
  useEffect(() => {
    if (
      credentialRequest &&
      credentialDialog.current &&
      !credentialDialog.current.open
    )
      credentialDialog.current.showModal();
  }, [credentialRequest]);
  async function passwordFor(id: string): Promise<string> {
    const profile = profiles.find((p) => p.id === id);
    if (profile?.passwordSaved) return "";
    if (sessionPasswords.current[id]) return sessionPasswords.current[id];
    if (pendingCredential.current) {
      if (pendingCredential.current.id === id)
        return pendingCredential.current.promise;
      await pendingCredential.current.promise;
      return passwordFor(id);
    }
    const promise = new Promise<string>((resolve, reject) => {
      credentialResolver.current = (value) =>
        value
          ? resolve(value)
          : reject(Error("Connection authentication cancelled."));
      setCredentialValue("");
      setCredentialRequest({ id, name: profile?.name || "Oracle connection" });
    }).finally(() => {
      pendingCredential.current = null;
    });
    pendingCredential.current = { id, promise };
    return promise;
  }
  function finishCredentials(value: string | null) {
    if (value && credentialRequest)
      sessionPasswords.current[credentialRequest.id] = value;
    credentialResolver.current?.(value);
    credentialResolver.current = null;
    setCredentialRequest(null);
    setCredentialValue("");
  }
  const [scenarioName, setScenarioName] = useState(draft.name);
  useEffect(() => {
    setScenarioName(draft.name);
  }, [draft.name, draft.id]);
  const actionMenu = useRef<HTMLDetailsElement>(null);
  const closeActionMenu = () => {
    if (actionMenu.current) actionMenu.current.open = false;
  };
  useEffect(() => {
    const outside = (e: PointerEvent) => {
      if (!actionMenu.current?.contains(e.target as Node)) closeActionMenu();
    };
    const escape = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeActionMenu();
    };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("keydown", escape);
    };
  }, []);
  const transferDialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = transferDialog.current;
    if (bottom && dialog && !dialog.open) dialog.showModal();
  }, [bottom]);
  function apply(change: Partial<Draft> | ((d: Draft) => Draft)) {
    revision.current++;
    setPreview(null);
    setError("");
    const next =
      typeof change === "function"
        ? change(draftRef.current)
        : { ...draftRef.current, ...change };
    draftRef.current = next;
    setDraft(next);
    setDirty(true);
  }
  async function perform(fn: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await fn();
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Unable to complete the operation.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function describe(
    table: string,
    owner = draftRef.current.owner,
    refresh = false,
  ) {
    const before = draftRef.current;
    if (!before.sourceId || !owner)
      throw Error(
        "Select a source connection and enter a table as OWNER.TABLE_NAME.",
      );
    const key = JSON.stringify([
      profiles.find((p) => p.id === before.sourceId),
      owner,
      table,
    ]);
    const cached = metadataCache.current.get(key);
    if (!refresh && cached && Date.now() - cached.at < cacheTtl) {
      setMeta((m) => ({ ...m, [table]: cached.value }));
      return cached.value;
    }
    const info = await api<Meta>(
      "/connections/" + before.sourceId + "/discover",
      {
        schema: owner,
        table,
        password: await passwordFor(before.sourceId),
      },
    );
    if (
      before.sourceId !== draftRef.current.sourceId ||
      before.owner !== draftRef.current.owner
    )
      throw Error("Source changed. Please try again.");
    if (metadataCache.current.size >= 250)
      metadataCache.current.delete(metadataCache.current.keys().next().value!);
    metadataCache.current.set(key, { at: Date.now(), value: info });
    setMeta((m) => ({ ...m, [table]: info }));
    return info;
  }
  async function loadTables(refresh = false) {
    await perform(async () => {
      if (!draft.sourceId) throw Error("Select a source connection.");
      const sourceId = draft.sourceId;
      const schema = schemaFilter.trim().toUpperCase();
      if (!schema) throw Error("Enter a schema to load its tables.");
      const key = JSON.stringify([
        profiles.find((p) => p.id === sourceId),
        schema,
      ]);
      const cached = tableCache.current.get(key);
      if (!refresh && cached && Date.now() - cached.at < cacheTtl) {
        setTables(cached.rows);
        return;
      }
      const rows = await api<{ OWNER: string; TABLE_NAME: string }[]>(
        "/connections/" + draft.sourceId + "/tables",
        { schema, password: await passwordFor(sourceId) },
      );
      if (sourceId !== draftRef.current.sourceId) return;
      const names = rows.map((t) => `${t.OWNER}.${t.TABLE_NAME}`);
      if (tableCache.current.size >= 20)
        tableCache.current.delete(tableCache.current.keys().next().value!);
      tableCache.current.set(key, { at: Date.now(), rows: names });
      setTables(names);
      if (!rows.length) setNotice("No accessible application tables found.");
    });
  }
  async function addTable(table: string) {
    await perform(async () => {
      table = table.trim().toUpperCase();
      const d = draftRef.current;
      const parts = table.split(".");
      if (parts.length > 2) throw Error("Enter a table as OWNER.TABLE_NAME.");
      const owner =
        parts.length === 2 ? parts[0] : schemaFilter.trim() || d.owner;
      table = parts.length === 2 ? parts[1] : parts[0];
      if (d.graph.tables.length && owner !== d.owner)
        throw Error(
          `This scenario uses ${d.owner}. Create a new scenario for tables in ${owner}.`,
        );
      const info = await describe(table, owner);
      if (d.graph.tables.includes(table)) {
        setSelected(table);
        setAddOpen(false);
        return;
      }
      const names = [...d.graph.tables, table];
      const root = d.table || table;
      apply({
        ...d,
        owner: info.owner,
        table: root,
        graph: {
          tables: names,
          positions: {
            ...d.graph.positions,
            [table]: {
              x: 60 + ((names.length - 1) % 4) * 245,
              y: 60 + Math.floor((names.length - 1) / 4) * 150,
            },
          },
        },
      });
      setSelected(table);
      setSelectedEdge("");
      setInspectorTab("table");
      setAddOpen(false);
      setAddName("");
      setFitToken((n) => n + 1);
      if (!info.primaryKey.length)
        setNotice("Table added. A primary key is required for transfer.");
    });
  }
  async function explore(table = selected) {
    await perform(async () => {
      if (!table) throw Error("Select a table to discover.");
      const info = await describe(table);
      const d = draftRef.current;
      const known = new Set(d.relations.map(edgeKey));
      const additions = info.relations.filter(
        (r) =>
          r.CHILD_OWNER === d.owner &&
          r.PARENT_OWNER === d.owner &&
          !known.has(edgeKey(r)),
      );
      const relations = [...d.relations, ...additions];
      const names = [
        ...new Set([
          ...d.graph.tables,
          ...additions.flatMap((r) => [r.PARENT_TABLE, r.CHILD_TABLE]),
        ]),
      ];
      const layout = arrange(d.table, names, relations);
      apply({
        ...d,
        relations,
        graph: {
          tables: names,
          positions: { ...layout, ...d.graph.positions },
        },
      });
      setFitToken((n) => n + 1);
      setNotice(
        additions.length +
          " FK relationships added. Select any unwanted link to remove it.",
      );
    });
  }
  useEffect(() => {
    if (!selected || !draft.sourceId || !draft.owner || meta[selected]) return;
    void describe(selected).catch((e) =>
      setError(e instanceof Error ? e.message : "Unable to load columns."),
    );
  }, [selected, draft.sourceId, draft.owner, meta[selected]]);
  function selectTable(table: string) {
    setSelected(table);
    setSelectedEdge("");
    setInspectorTab("table");
    setSuggestions([]);
  }
  function loadScenario(id: string) {
    if (dirty && !confirm("Discard unsaved changes?")) return;
    if (!id) {
      const d = { ...blank(), sourceId: draft.sourceId };
      revision.current++;
      setDraft(d);
      setScenarioName(d.name);
      draftRef.current = d;
      setDirty(false);
      setPreview(null);
      setSelected("");
      setSelectedEdge("");
      setMeta({});
      return;
    }
    const s = scenarios.find((s) => s.id === id);
    if (!s) return;
    const d = normalize(s);
    revision.current++;
    draftRef.current = d;
    setDraft(d);
    setScenarioName(d.name);
    setDirty(false);
    setMeta({});
    setPreview(null);
    setSelected(d.table);
    setSelectedEdge("");
    setFitToken((n) => n + 1);
    setNotice(
      "Scenario loaded. Edit and save, or select Start to review the transfer.",
    );
  }
  async function save() {
    await perform(async () => {
      if (!scenarioName.trim() || !draft.table)
        throw Error("A scenario name and root table are required.");
      const saved = await api<Scenario>("/scenarios", {
        ...draft,
        name: scenarioName.trim(),
        relations: orient(draft.table, draft.relations),
      });
      const d = normalize(saved);
      draftRef.current = d;
      setDraft(d);
      setDirty(false);
      await onSaved();
      setNotice("Diagram and scenario saved.");
    });
  }
  function removeNode(table: string) {
    const d = draftRef.current;
    const names = d.graph.tables.filter((n) => n !== table),
      relations = d.relations.filter(
        (r) => r.CHILD_TABLE !== table && r.PARENT_TABLE !== table,
      );
    const policies = { ...d.keyPolicies };
    delete policies[table];
    const filters = { ...d.tableFilters };
    delete filters[table];
    apply({
      ...d,
      table: d.table === table ? "" : d.table,
      relations,
      keyPolicies: policies,
      tableFilters: filters,
      graph: {
        tables: names,
        positions: Object.fromEntries(
          Object.entries(d.graph.positions).filter(([t]) => t !== table),
        ),
      },
    });
    setSelected("");
  }
  async function openLink(p: string, c: string, existing?: Relation) {
    setParent(p);
    setChild(c);
    setPairs(
      existing
        ? existing.COLUMN_PAIRS.map((x) => ({ ...x }))
        : [{ parent: meta[p]?.primaryKey?.[0] || "", child: "" }],
    );
    setEditEdge(existing ? edgeKey(existing) : "");
    setNodeFilter(draft.tableFilters[c] || "");
    setLinkOpen(true);
    await perform(async () => {
      for (const table of [...new Set([p, c].filter(Boolean))])
        if (!meta[table]) await describe(table);
    });
  }
  function confirmLink() {
    if (!parent || !child || parent === child) {
      setError("Select two different tables.");
      return;
    }
    if (pairs.some((p) => !p.parent || !p.child)) {
      setError("Complete every column mapping.");
      return;
    }
    const relation: Relation = {
      PARENT_OWNER: draft.owner,
      PARENT_TABLE: parent,
      CHILD_OWNER: draft.owner,
      CHILD_TABLE: child,
      CONSTRAINT_NAME: editEdge
        ? draft.relations.find((r) => edgeKey(r) === editEdge)!.CONSTRAINT_NAME
        : "M_" +
          crypto.randomUUID().replaceAll("-", "").slice(0, 16).toUpperCase(),
      DIRECTION: "CHILD",
      SOURCE: "MANUAL",
      STATUS: "ENABLED",
      VALIDATED: "USER_DEFINED",
      COLUMN_PAIRS: pairs,
    };
    apply({
      ...draft,
      relations: [
        ...draft.relations.filter((r) => edgeKey(r) !== editEdge),
        relation,
      ],
      tableFilters: { ...draft.tableFilters, [child]: nodeFilter },
    });
    setLinkOpen(false);
    setSelectedEdge(edgeKey(relation));
    setSelected("");
    setFitToken((n) => n + 1);
  }
  async function prepare() {
    await perform(async () => {
      if (!draft.table || !draft.sourceId || !targetId)
        throw Error("Select a root table, source and target.");
      const reachable = connected(draft.table, draft.relations);
      const isolated = draft.graph.tables.filter((t) => !reachable.has(t));
      if (isolated.length)
        throw Error(
          "Tables disconnected from the root: " + isolated.join(", "),
        );
      const snapshot = revision.current;
      setPreview(null);
      setBottom("preview");
      const p = await api<Preview>("/transfers/preview", {
        ...draft,
        sourceId: draft.sourceId,
        targetId,
        sourcePassword: await passwordFor(draft.sourceId),
        targetPassword: await passwordFor(targetId),
        relations: orient(draft.table, draft.relations),
      });
      if (snapshot !== revision.current) {
        await api("/transfers/preview/" + p.token, undefined, "DELETE");
        throw Error("The design changed. Select Start to review it again.");
      }
      previewRevision.current = snapshot;
      setPreview(p);
    });
  }
  async function start() {
    await perform(async () => {
      if (!preview || previewRevision.current !== revision.current)
        throw Error("A current preview is required.");
      const j = await api<Job>("/transfers", { token: preview.token });
      setJob(j);
      setPreview(null);
      setBottom("run");
    });
  }
  async function keySuggestions() {
    await perform(async () => {
      if (!targetId || !selected)
        throw Error("Select a target connection and table first.");
      const chosen = selected,
        target = targetId;
      const result = await api<{
        tables: Record<string, { suggestions: Suggestion[] }>;
      }>("/keys/options", {
        connectionId: target,
        password: await passwordFor(target),
        tables: [chosen],
      });
      if (target === targetRef.current && chosen === selected)
        setSuggestions(result.tables[chosen]?.suggestions || []);
    });
  }
  function setPolicy(patch: Partial<KeyPolicy>) {
    apply((d) => ({
      ...d,
      keyPolicies: {
        ...d.keyPolicies,
        [selected]: {
          ...defaultPolicy(),
          ...d.keyPolicies[selected],
          ...patch,
        },
      },
    }));
  }
  async function importFile(file: File) {
    await perform(async () => {
      if (file.size > 1500000) throw Error("Scenario file is too large.");
      const raw = JSON.parse(await file.text());
      if (
        ![1, 2, 3].includes(raw.version) ||
        !raw.name ||
        !raw.table ||
        !Array.isArray(raw.relations)
      )
        throw Error("Choose a valid scenario JSON file.");
      const d = normalize({ ...raw, id: undefined, sourceId: draft.sourceId });
      apply(d);
      setMeta({});
      setSelected(d.table);
      setFitToken((n) => n + 1);
      setNotice("Scenario imported into the scenario editor. Save to keep it.");
    });
  }
  function exportFile() {
    const {
      sourceId: _,
      id: __,
      ...s
    } = { ...draft, name: scenarioName.trim() };
    const blob = new Blob(
      [
        JSON.stringify(
          { ...s, version: 3, relations: orient(s.table, s.relations) },
          null,
          2,
        ),
      ],
      { type: "application/json" },
    );
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "databridge-diagram.json";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }
  const edge = draft.relations.find((r) => edgeKey(r) === selectedEdge),
    info = meta[selected],
    policy = { ...defaultPolicy(), ...draft.keyPolicies[selected] };
  const blocked = busy || active(job);
  const rootMissing = !draft.table;
  const nameKey = (name: string) => name.normalize("NFKC").trim().toLowerCase();
  const duplicateName = scenarios.some(
    (s) => s.id !== draft.id && nameKey(s.name) === nameKey(scenarioName),
  );
  return (
    <div className="workbench">
      <div className="workbench-toolbar">
        <div className="scenario-controls">
          <ScenarioPicker
            value={scenarioName}
            selectedId={draft.id}
            scenarios={scenarios}
            disabled={active(job)}
            onChange={setScenarioName}
            onSelect={loadScenario}
          />
          <button
            title="New scenario"
            onClick={() => loadScenario("")}
            disabled={blocked}
          >
            <Plus size={14} />
          </button>
          <button
            title={
              dirty || scenarioName !== draft.name
                ? "Save scenario · Unsaved changes"
                : "Save scenario"
            }
            aria-label="Save scenario"
            className={
              dirty || scenarioName !== draft.name
                ? "scenario-save has-changes"
                : "scenario-save"
            }
            onClick={save}
            disabled={
              blocked || rootMissing || !scenarioName.trim() || duplicateName
            }
          >
            <Save size={14} />
          </button>
        </div>
        <span className="toolbar-spacer" />
        <label className="button icon-only" title="Import scenario">
          <Upload size={14} />
          <input
            hidden
            type="file"
            accept=".json"
            disabled={blocked}
            onChange={(e) => {
              if (e.target.files?.[0]) importFile(e.target.files[0]);
              e.target.value = "";
            }}
          />
        </label>
        <button
          title="Export scenario"
          onClick={exportFile}
          disabled={rootMissing}
        >
          <Download size={14} />
        </button>
        <details className="transfer-actions" ref={actionMenu}>
          <summary>
            Actions <ChevronDown size={13} />
          </summary>
          <div className="transfer-actions-menu">
            <button
              onClick={() => {
                closeActionMenu();
                setBottom("run");
              }}
            >
              <Play size={14} />
              Execution {job && "· " + labels[job.status]}
            </button>
            <button
              onClick={() => {
                closeActionMenu();
                setBottom("history");
                api<Job[]>("/transfers")
                  .then(setHistory)
                  .catch((e) => setError(e.message));
              }}
            >
              <History size={14} />
              History
            </button>
          </div>
        </details>
      </div>
      <div className="source-strip">
        <div className="source-controls">
          <label>
            Source
            <select
              aria-label="Source connection"
              value={draft.sourceId || ""}
              disabled={active(job)}
              onChange={(e) => {
                const p = profiles.find((p) => p.id === e.target.value);
                apply({
                  ...draft,
                  sourceId: p?.id,
                });
                setMeta({});
                setTables([]);
              }}
            >
              <option value="">Select</option>
              {profiles.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <span className="strip-arrow">→</span>
          <label>
            Target
            <select
              aria-label="Target connection"
              value={targetId}
              disabled={active(job)}
              onChange={(e) => {
                revision.current++;
                setPreview(null);
                setTargetId(e.target.value);
                setSuggestions([]);
              }}
            >
              <option value="">Select</option>
              {profiles
                .filter(
                  (p) =>
                    !p.readOnly &&
                    p.environment !== "PROD" &&
                    p.role === "NORMAL",
                )
                .map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
            </select>
          </label>
        </div>
        <button
          className="primary"
          onClick={prepare}
          disabled={blocked || rootMissing || !draft.sourceId || !targetId}
        >
          <Play size={14} />
          Start
        </button>
      </div>
      {error && (
        <div className="workbench-alert error" role="alert">
          <AlertCircle size={14} />
          <ErrorDetails message={error} />
          <button onClick={() => setError("")}>
            <X size={13} />
          </button>
        </div>
      )}
      {notice && (
        <div className="workbench-alert success" role="status">
          <Check size={14} />
          {notice}
        </div>
      )}
      <div className="designer-grid">
        <aside className="table-browser">
          <div className="small-panel-title">
            <Table2 size={14} />
            <strong>Source tables</strong>
            <button
              title="Add table by name"
              onClick={() => setAddOpen(true)}
              disabled={blocked}
            >
              <Plus size={14} />
            </button>
          </div>
          <div
            className="table-browser-toolbar"
            role="group"
            aria-label="Source table controls"
          >
            <label>
              Schema filter
              <input
                aria-label="Source schema filter"
                placeholder="Schema name (required)"
                value={schemaFilter}
                onChange={(e) => {
                  setSchemaFilter(e.target.value.toUpperCase());
                  setTables([]);
                }}
                disabled={blocked}
              />
            </label>
            <button
              disabled={blocked || !draft.sourceId || !schemaFilter.trim()}
              onClick={() => loadTables()}
            >
              <RefreshCw size={13} />
              Load tables
            </button>
            <button
              disabled={blocked || !draft.sourceId || !schemaFilter.trim()}
              onClick={() => loadTables(true)}
              title="Reload tables from database"
            >
              Refresh
            </button>
          </div>
          <div className="table-search">
            <Search size={13} />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search tables…"
            />
          </div>
          <div
            className="table-browser-list"
            ref={tableListRef}
            style={{ height: 432, flex: "0 1 432px", overflowY: "auto" }}
            onScroll={(e) => setTableScroll(e.currentTarget.scrollTop)}
          >
            {!tables.length ? (
              <p>Load tables or add one by name.</p>
            ) : (
              <div
                style={{
                  height: filteredTables.length * 36,
                  position: "relative",
                }}
              >
                {filteredTables
                  .slice(firstTable, firstTable + 24)
                  .map((t, index) => (
                    <button
                      key={t}
                      style={{
                        position: "absolute",
                        top: (firstTable + index) * 36,
                        height: 33,
                        width: "100%",
                        boxSizing: "border-box",
                        margin: 0,
                      }}
                      disabled={blocked}
                      className={
                        draft.graph.tables.some(
                          (name) => `${draft.owner}.${name}` === t,
                        )
                          ? "on-canvas"
                          : ""
                      }
                      onClick={() =>
                        draft.graph.tables.some(
                          (name) => `${draft.owner}.${name}` === t,
                        )
                          ? selectTable(t.slice(t.indexOf(".") + 1))
                          : addTable(t)
                      }
                      title={t}
                    >
                      <Table2 size={12} />
                      <span>{t}</span>
                      {draft.graph.tables.some(
                        (name) => `${draft.owner}.${name}` === t,
                      ) ? (
                        <Check size={12} />
                      ) : (
                        <Plus size={12} />
                      )}
                    </button>
                  ))}
              </div>
            )}
            {!!tables.length && !filteredTables.length && (
              <p>No matching tables.</p>
            )}
          </div>
          <button
            className="table-add"
            onClick={() => setAddOpen(true)}
            disabled={blocked}
          >
            <Plus size={13} />
            Add table by name
          </button>
        </aside>
        <Diagram
          disabled={blocked}
          graph={draft.graph}
          relations={draft.relations}
          root={draft.table}
          selected={selected}
          selectedEdge={selectedEdge}
          meta={meta}
          onSelect={selectTable}
          onEdge={(id) => {
            setSelectedEdge(id);
            setSelected("");
          }}
          onMove={(t, p) => {
            if (!active(job))
              apply((d) => ({
                ...d,
                graph: {
                  ...d.graph,
                  positions: { ...d.graph.positions, [t]: p },
                },
              }));
          }}
          onLink={(p, c) => {
            if (!blocked) openLink(p, c);
          }}
          onAdd={() => setAddOpen(true)}
          onArrange={() => {
            apply((d) => ({
              ...d,
              graph: {
                ...d.graph,
                positions: arrange(d.table, d.graph.tables, d.relations),
              },
            }));
            setFitToken((n) => n + 1);
          }}
          fitToken={fitToken}
        />
        <aside className="graph-inspector">
          <div className="small-panel-title">
            <strong>{edge ? "Relationship" : selected || "Properties"}</strong>
            {(edge || selected) && (
              <button
                title="Clear selection"
                onClick={() => {
                  setSelected("");
                  setSelectedEdge("");
                }}
              >
                <X size={14} />
              </button>
            )}
          </div>
          {edge ? (
            <div className="inspector-body">
              <span
                className={
                  "relation-kind " + (edge.SOURCE === "MANUAL" ? "manual" : "")
                }
              >
                {edge.SOURCE === "MANUAL"
                  ? "Custom relationship"
                  : "Oracle foreign key"}
              </span>
              <label>
                Parent table<strong>{edge.PARENT_TABLE}</strong>
              </label>
              <label>
                Child table<strong>{edge.CHILD_TABLE}</strong>
              </label>
              <div className="relation-pairs">
                {edge.COLUMN_PAIRS.map((p, i) => (
                  <code key={i}>
                    {p.parent} → {p.child}
                  </code>
                ))}
              </div>
              <small>
                This link defines data selection and key mapping for this
                scenario. It does not create a database constraint.
              </small>
              {edge.SOURCE === "MANUAL" && (
                <button
                  disabled={blocked}
                  onClick={() =>
                    openLink(edge.PARENT_TABLE, edge.CHILD_TABLE, edge)
                  }
                >
                  <Link2 size={13} />
                  Edit relationship
                </button>
              )}
              <button
                disabled={blocked}
                className="danger"
                onClick={() => {
                  apply({
                    relations: draft.relations.filter(
                      (r) => edgeKey(r) !== selectedEdge,
                    ),
                  });
                  setSelectedEdge("");
                }}
              >
                <Trash2 size={13} />
                Remove link
              </button>
            </div>
          ) : selected ? (
            <>
              <div className="inspector-tabs">
                {[
                  ["table", "Table"],
                  ["key", "Key strategy"],
                ].map(([id, label]) => (
                  <button
                    key={id}
                    className={inspectorTab === id ? "active" : ""}
                    onClick={() => setInspectorTab(id)}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <fieldset className="inspector-body" disabled={active(job)}>
                {inspectorTab === "table" ? (
                  <>
                    <div className="node-commands">
                      <button
                        onClick={() => {
                          apply({ table: selected });
                          setFitToken((n) => n + 1);
                        }}
                        disabled={draft.table === selected}
                      >
                        <Flag size={13} />
                        {draft.table === selected
                          ? "Root table"
                          : "Set as root"}
                      </button>
                      <button onClick={() => explore()} disabled={blocked}>
                        <Network size={13} />
                        Discover FKs
                      </button>
                    </div>
                    <label>
                      {draft.table === selected
                        ? "Root filter"
                        : "Additional table filter"}
                      <textarea
                        rows={3}
                        aria-label={selected + " filter"}
                        value={
                          draft.table === selected
                            ? draft.condition
                            : draft.tableFilters[selected] || ""
                        }
                        onChange={(e) =>
                          draft.table === selected
                            ? apply({ condition: e.target.value })
                            : apply({
                                tableFilters: {
                                  ...draft.tableFilters,
                                  [selected]: e.target.value,
                                },
                              })
                        }
                        placeholder="DURUM = 'AKTIF'"
                      />
                    </label>
                    {draft.table === selected && (
                      <label>
                        Row limit
                        <input
                          type="number"
                          min={1}
                          max={1000000}
                          value={draft.limit}
                          onChange={(e) =>
                            apply({ limit: Number(e.target.value) })
                          }
                        />
                      </label>
                    )}
                    <div className="small-columns">
                      <div className="small-panel-title">
                        <strong>Columns</strong>
                        <button
                          title="Refresh columns"
                          onClick={() =>
                            perform(async () => {
                              await describe(
                                selected,
                                draftRef.current.owner,
                                true,
                              );
                            })
                          }
                        >
                          <RefreshCw size={12} />
                        </button>
                      </div>
                      {info?.columns.map((c) => (
                        <div key={c.COLUMN_NAME}>
                          <span>
                            {info.primaryKey?.includes(c.COLUMN_NAME) && (
                              <KeyRound size={10} />
                            )}{" "}
                            {c.COLUMN_NAME}
                          </span>
                          <small>{c.DATA_TYPE}</small>
                        </div>
                      )) || (
                        <p className="column-loading" role="status">
                          Loading columns…
                        </p>
                      )}
                    </div>
                    <button
                      onClick={() => openLink(selected, "")}
                      disabled={blocked || draft.graph.tables.length < 2}
                    >
                      <Link2 size={13} />
                      Create custom relationship
                    </button>
                    <button
                      className="danger"
                      onClick={() => removeNode(selected)}
                      disabled={blocked}
                    >
                      <Trash2 size={13} />
                      Remove table
                    </button>
                  </>
                ) : (
                  <>
                    <label>
                      Key strategy
                      <select
                        value={policy.mode}
                        onChange={(e) =>
                          setPolicy({
                            mode: e.target.value,
                            column:
                              policy.column || info?.primaryKey?.[0] || "",
                          })
                        }
                      >
                        <option value="KEEP">Keep / map from parent</option>
                        <option value="SEQUENCE">Sequence</option>
                        <option value="IDENTITY">Identity</option>
                        <option value="MAX">MAX + increment</option>
                        <option value="BUSINESS">Match by business key</option>
                      </select>
                    </label>
                    {policy.mode === "KEEP" ? (
                      <label>
                        On conflict
                        <select
                          value={policy.onConflict}
                          onChange={(e) =>
                            setPolicy({ onConflict: e.target.value })
                          }
                        >
                          <option value="ERROR">Fail and roll back</option>
                          <option value="SKIP">Skip / reuse existing</option>
                        </select>
                      </label>
                    ) : policy.mode === "BUSINESS" ? (
                      <label>
                        Business key columns
                        <input
                          value={policy.businessColumns.join(", ")}
                          onChange={(e) =>
                            setPolicy({
                              businessColumns: e.target.value
                                .toUpperCase()
                                .split(",")
                                .map((v) => v.trim()),
                            })
                          }
                          placeholder="URUN_KODU"
                        />
                      </label>
                    ) : (
                      <label>
                        Generated PK column
                        <input
                          value={policy.column}
                          onChange={(e) =>
                            setPolicy({ column: e.target.value.toUpperCase() })
                          }
                        />
                      </label>
                    )}
                    {policy.mode === "SEQUENCE" && (
                      <label>
                        Sequence name
                        <input
                          value={policy.sequence}
                          onChange={(e) =>
                            setPolicy({
                              sequence: e.target.value.toUpperCase(),
                            })
                          }
                          placeholder={"SEQ_" + selected}
                        />
                      </label>
                    )}
                    {policy.mode === "MAX" && (
                      <>
                        <div className="inline-fields">
                          <label>
                            Start value
                            <input
                              type="number"
                              value={policy.start}
                              onChange={(e) =>
                                setPolicy({ start: Number(e.target.value) })
                              }
                            />
                          </label>
                          <label>
                            Increment
                            <input
                              type="number"
                              min={1}
                              value={policy.increment}
                              onChange={(e) =>
                                setPolicy({ increment: Number(e.target.value) })
                              }
                            />
                          </label>
                        </div>
                        <label>
                          Grouping columns
                          <input
                            value={policy.groupColumns.join(", ")}
                            onChange={(e) =>
                              setPolicy({
                                groupColumns: e.target.value
                                  .toUpperCase()
                                  .split(",")
                                  .map((v) => v.trim()),
                              })
                            }
                            placeholder="PLAN_ID"
                          />
                        </label>
                        <small>
                          No additional allocation lock. A conflict rolls back
                          all writes.
                        </small>
                      </>
                    )}
                    <button
                      onClick={keySuggestions}
                      disabled={busy || !targetId}
                    >
                      <Wand2 size={13} />
                      Suggest from target
                    </button>
                    {suggestions.map((s, i) => (
                      <button
                        className="compact-suggestion"
                        key={i}
                        onClick={() =>
                          setPolicy({
                            mode: s.mode,
                            column: s.column,
                            sequence: s.mode === "SEQUENCE" ? s.sequence : "",
                          })
                        }
                      >
                        <strong>
                          {s.column} →{" "}
                          {s.mode === "IDENTITY" ? "Identity" : s.sequence}
                        </strong>
                        <small>{s.reason}</small>
                      </button>
                    ))}
                    <small>
                      Generated keys are mapped through selected relationships.
                      Sequence and identity values are not restored on rollback.
                    </small>
                  </>
                )}
              </fieldset>
            </>
          ) : (
            <div className="inspector-empty">
              <Network size={25} />
              <p>Select a table or relationship.</p>
              <small>
                Drag table headers to arrange them. Use the connection ports to
                link tables.
              </small>
            </div>
          )}
        </aside>
      </div>
      {bottom && (
        <dialog
          ref={transferDialog}
          className="transfer-modal"
          aria-labelledby="transfer-modal-title"
          onCancel={() => setBottom("")}
          onClick={(e) => {
            if (e.target === e.currentTarget) setBottom("");
          }}
        >
          <div className="transfer-modal-surface">
            <div className="modal-title">
              <h2 id="transfer-modal-title">
                {bottom === "preview"
                  ? "Transfer preview"
                  : bottom === "run"
                    ? "Execution"
                    : "Transfer history"}
              </h2>
              <button aria-label="Close dialog" onClick={() => setBottom("")}>
                <X size={16} />
              </button>
            </div>
            <div className="workbench-drawer">
              {error && (
                <div className="workbench-alert error" role="alert">
                  <ErrorDetails message={error} />
                </div>
              )}
              {bottom === "preview" ? (
                preview ? (
                  <>
                    <table>
                      <thead>
                        <tr>
                          <th>Insert order</th>
                          <th>Rows</th>
                          <th>PK</th>
                          <th>Strategy</th>
                        </tr>
                      </thead>
                      <tbody>
                        {preview.tables.map((t, i) => (
                          <tr key={t.table}>
                            <td>
                              {i + 1}. {t.table}
                            </td>
                            <td>{fmt(t.selected)}</td>
                            <td>{t.primaryKey.join(", ")}</td>
                            <td>
                              {t.keyPolicy.mode}
                              {t.keyPolicy.mode === "SEQUENCE"
                                ? " · " + t.keyPolicy.sequence
                                : ""}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <details>
                      <summary>
                        Transfer notes · Snapshot expires at{" "}
                        {new Date(preview.expiresAt).toLocaleTimeString(
                          "en-GB",
                        )}{" "}
                      </summary>
                      {preview.warnings.map((w) => (
                        <p key={w}>{w}</p>
                      ))}
                    </details>
                  </>
                ) : (
                  <p role="status" aria-live="polite">
                    {busy
                      ? "Preparing transfer preview…"
                      : "Unable to prepare the transfer. Review the error and try again."}
                  </p>
                )
              ) : bottom === "run" ? (
                job ? (
                  <div className="compact-run">
                    <div>
                      <strong>{labels[job.status]}</strong>
                      <span>{job.table}</span>
                      <span>
                        {fmt(job.processed)} / {fmt(job.total)} processed
                      </span>
                      <span>
                        {fmt(job.inserted)} inserted · {fmt(job.skipped)}{" "}
                        skipped
                      </span>
                      {active(job) && (
                        <button
                          onClick={() =>
                            perform(async () => {
                              await api("/transfers/" + job.id + "/cancel", {});
                            })
                          }
                        >
                          <Square size={12} />
                          Cancel and roll back
                        </button>
                      )}
                    </div>
                    <div className="progress-track">
                      <div
                        style={{
                          width:
                            (job.total
                              ? Math.min(100, (job.processed / job.total) * 100)
                              : 0) + "%",
                        }}
                      />
                    </div>
                    <div className="job-message">
                      <ErrorDetails
                        message={
                          job.message ||
                          (active(job)
                            ? "Writes have not been committed yet."
                            : "Check the execution status and target data.")
                        }
                      />
                      {job.table && (
                        <span className="error-context">
                          Table: {job.table}
                        </span>
                      )}
                    </div>
                  </div>
                ) : (
                  <p>No transfer has been started for this scenario.</p>
                )
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Source → Target</th>
                      <th>Status</th>
                      <th>Inserted / Skipped</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.map((j) => (
                      <tr
                        key={j.id}
                        onClick={() => {
                          setJob(j);
                          setBottom("run");
                        }}
                      >
                        <td>{new Date(j.startedAt).toLocaleString("en-GB")}</td>
                        <td>
                          {j.source} → {j.target}
                        </td>
                        <td>{labels[j.status]}</td>
                        <td>
                          {j.inserted} / {j.skipped}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <footer>
              {bottom === "run" && active(job) && (
                <small>
                  The transfer continues when this dialog is closed.
                </small>
              )}
              <button onClick={() => setBottom("")}>Close</button>
              {bottom === "preview" && error && !busy && (
                <button disabled={active(job)} onClick={prepare}>
                  <RefreshCw size={13} />
                  Try again
                </button>
              )}
              {bottom === "preview" && preview && (
                <button
                  className="primary"
                  disabled={
                    blocked ||
                    !!error ||
                    !preview.total ||
                    previewRevision.current !== revision.current
                  }
                  onClick={start}
                >
                  <Play size={13} />
                  Start transfer
                </button>
              )}
            </footer>
          </div>
        </dialog>
      )}
      {credentialRequest && (
        <dialog
          className="transfer-modal credential-dialog"
          ref={credentialDialog}
          aria-labelledby="credential-title"
          onCancel={() => finishCredentials(null)}
        >
          <form
            className="transfer-modal-surface"
            onSubmit={(e) => {
              e.preventDefault();
              if (credentialValue) finishCredentials(credentialValue);
            }}
          >
            <div className="modal-title">
              <h2 id="credential-title">Connection authentication</h2>
              <button
                type="button"
                aria-label="Close authentication"
                onClick={() => finishCredentials(null)}
              >
                <X size={15} />
              </button>
            </div>
            <div className="dialog-fields">
              <label>
                {credentialRequest.name}
                <input
                  autoFocus
                  type="password"
                  aria-label="Connection password"
                  value={credentialValue}
                  onChange={(e) => setCredentialValue(e.target.value)}
                  autoComplete="off"
                />
              </label>
              <small>
                No saved password is available. This password is used only for
                this application session.
              </small>
            </div>
            <footer>
              <button type="button" onClick={() => finishCredentials(null)}>
                Cancel
              </button>
              <button
                type="submit"
                className="primary"
                disabled={!credentialValue}
              >
                Continue
              </button>
            </footer>
          </form>
        </dialog>
      )}
      {addOpen && (
        <div className="modal-backdrop">
          <section className="compact-dialog">
            <div className="modal-title">
              <h2>Add table</h2>
              <button onClick={() => setAddOpen(false)}>
                <X size={14} />
              </button>
            </div>
            <div className="dialog-fields">
              <label>
                Table name
                <input
                  list="source-table-names"
                  autoFocus
                  value={addName}
                  onChange={(e) => setAddName(e.target.value.toUpperCase())}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") addTable(addName);
                  }}
                  placeholder="OWNER.TABLE_NAME"
                />
                <datalist id="source-table-names">
                  {filteredTables.slice(0, 100).map((t) => (
                    <option key={t}>{t}</option>
                  ))}
                </datalist>
              </label>
              <small>
                Enter OWNER.TABLE_NAME, or a table name when a schema filter or
                scenario owner is available.
              </small>
            </div>
            <footer>
              <button onClick={() => setAddOpen(false)}>Cancel</button>
              <button
                className="primary"
                disabled={busy || !addName}
                onClick={() => addTable(addName)}
              >
                <Plus size={13} />
                Add
              </button>
            </footer>
          </section>
        </div>
      )}
      {linkOpen && (
        <div className="modal-backdrop">
          <section
            className="compact-dialog relation-dialog"
            role="dialog"
            aria-modal="true"
          >
            <div className="modal-title">
              <h2>Define relationship</h2>
              <button onClick={() => setLinkOpen(false)}>
                <X size={14} />
              </button>
            </div>
            <div className="dialog-fields">
              <div className="inline-fields">
                <label>
                  Parent table (PK/UNIQUE)
                  <select
                    aria-label="Relationship parent table"
                    value={parent}
                    onChange={(e) => {
                      const t = e.target.value;
                      setParent(t);
                      perform(async () => {
                        await describe(t);
                      });
                    }}
                  >
                    <option value="">Select</option>
                    {draft.graph.tables.map((t) => (
                      <option key={t}>{t}</option>
                    ))}
                  </select>
                </label>
                <label>
                  Child table
                  <select
                    aria-label="Relationship child table"
                    value={child}
                    onChange={(e) => {
                      const t = e.target.value;
                      setChild(t);
                      perform(async () => {
                        await describe(t);
                      });
                      setNodeFilter(draft.tableFilters[t] || "");
                    }}
                  >
                    <option value="">Select</option>
                    {draft.graph.tables
                      .filter((t) => t !== parent)
                      .map((t) => (
                        <option key={t}>{t}</option>
                      ))}
                  </select>
                </label>
              </div>
              <div className="pair-titles">
                <span>Parent column</span>
                <span>Child column</span>
              </div>
              {pairs.map((p, i) => (
                <div className="pair-fields" key={i}>
                  <select
                    aria-label={"Parent column " + (i + 1)}
                    value={p.parent}
                    onChange={(e) =>
                      setPairs((rows) =>
                        rows.map((v, n) =>
                          n === i ? { ...v, parent: e.target.value } : v,
                        ),
                      )
                    }
                  >
                    <option value="">Select column</option>
                    {meta[parent]?.columns.map((c) => (
                      <option key={c.COLUMN_NAME}>{c.COLUMN_NAME}</option>
                    ))}
                  </select>
                  <span>=</span>
                  <select
                    aria-label={"Child column " + (i + 1)}
                    value={p.child}
                    onChange={(e) =>
                      setPairs((rows) =>
                        rows.map((v, n) =>
                          n === i ? { ...v, child: e.target.value } : v,
                        ),
                      )
                    }
                  >
                    <option value="">Select column</option>
                    {meta[child]?.columns.map((c) => (
                      <option key={c.COLUMN_NAME}>{c.COLUMN_NAME}</option>
                    ))}
                  </select>
                  <button
                    disabled={pairs.length === 1}
                    onClick={() =>
                      setPairs((rows) => rows.filter((_, n) => n !== i))
                    }
                  >
                    <X size={12} />
                  </button>
                </div>
              ))}
              <button
                className="text-button"
                onClick={() =>
                  setPairs((rows) => [...rows, { parent: "", child: "" }])
                }
              >
                <Plus size={12} />
                Add column pair
              </button>
              <label>
                Additional child table filter
                <textarea
                  aria-label="Additional relationship filter"
                  rows={2}
                  value={nodeFilter}
                  onChange={(e) => setNodeFilter(e.target.value)}
                  placeholder="REFERANS_TIPI = 'KREDI'"
                />
              </label>
              <div className="note">
                Map parent PK/UNIQUE columns to child columns using equality.
                This creates no database constraint. The relationship controls
                selection, insert order and generated key mapping.
              </div>
              {error && (
                <div className="workbench-alert error">
                  <ErrorDetails message={error} />
                </div>
              )}
            </div>
            <footer>
              <button onClick={() => setLinkOpen(false)}>Cancel</button>
              <button
                className="primary"
                onClick={confirmLink}
                disabled={busy || !parent || !child}
              >
                <Link2 size={13} />
                Add relationship
              </button>
            </footer>
          </section>
        </div>
      )}
    </div>
  );
}
