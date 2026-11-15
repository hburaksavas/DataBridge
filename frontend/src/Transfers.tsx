import { useEffect, useState } from "react";
import {
  ArrowRight,
  Play,
  Eye,
  Square,
  RefreshCw,
  ShieldCheck,
  AlertCircle,
  Check,
  Clock,
  Database,
} from "lucide-react";
import type { Profile, Scenario } from "./main";
import type { KeyPolicy } from "./KeyPolicies";
type Api = <T>(path: string, body?: unknown, method?: string) => Promise<T>;
type Preview = {
  token: string;
  expiresAt: string;
  source: string;
  target: string;
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
  source: string;
  target: string;
  startedAt: string;
  finishedAt?: string;
  total: number;
  processed: number;
  inserted: number;
  skipped: number;
  table: string;
  message?: string;
  committed?: boolean;
};
const active = (job: Job | null) =>
  !!job && ["QUEUED", "RUNNING"].includes(job.status);
const number = (n: number) => new Intl.NumberFormat("tr-TR").format(n);
const statuses: Record<string, string> = {
  QUEUED: "Sırada",
  RUNNING: "Çalışıyor",
  COMPLETED: "Tamamlandı",
  FAILED: "Geri alındı",
  CANCELLED: "İptal edildi",
  UNKNOWN: "Sonucu kontrol edin",
};
export function Transfers({
  profiles,
  scenarios,
  api,
}: {
  profiles: Profile[];
  scenarios: Scenario[];
  api: Api;
}) {
  const [scenarioId, setScenarioId] = useState(""),
    [sourceId, setSourceId] = useState(""),
    [targetId, setTargetId] = useState(""),
    [sourcePassword, setSourcePassword] = useState(""),
    [targetPassword, setTargetPassword] = useState(""),
    [condition, setCondition] = useState(""),
    [limit, setLimit] = useState(100),
    [preview, setPreview] = useState<Preview | null>(null),
    [job, setJob] = useState<Job | null>(null),
    [history, setHistory] = useState<Job[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const scenario = scenarios.find((s) => s.id === scenarioId);
  const refresh = () => api<Job[]>("/transfers").then(setHistory);
  useEffect(() => {
    refresh().catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    const token = preview?.token;
    return () => {
      if (token)
        api("/transfers/preview/" + token, undefined, "DELETE").catch(() => {});
    };
  }, [preview?.token]);
  useEffect(() => {
    if (!active(job)) return;
    let stopped = false;
    const timer = setInterval(() => {
      api<Job>("/transfers/" + job!.id)
        .then((j) => {
          if (stopped) return;
          setJob(j);
          if (!active(j)) refresh().catch(() => {});
        })
        .catch((e) => {
          if (!stopped) setError(e.message);
        });
    }, 1000);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [job?.id, job?.status]);
  function invalidate() {
    setPreview(null);
    setError("");
  }
  function selectScenario(id: string) {
    invalidate();
    setScenarioId(id);
    const s = scenarios.find((s) => s.id === id);
    if (s) {
      setSourceId(s.sourceId || "");
      setCondition(s.condition || "");
      setLimit(s.limit || 100);
    }
  }
  async function perform(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : "İşlem başarısız.");
    } finally {
      setBusy(false);
    }
  }
  async function prepare() {
    await perform(async () => {
      if (!scenario) throw Error("Kaydedilmiş bir senaryo seçin.");
      if (!sourceId || !targetId)
        throw Error("Kaynak ve hedef bağlantısını seçin.");
      setPreview(null);
      const p = await api<Preview>("/transfers/preview", {
        sourceId,
        targetId,
        sourcePassword,
        targetPassword,
        owner: scenario.owner,
        table: scenario.table,
        condition,
        limit,
        relations: scenario.relations,
        keyPolicies: scenario.keyPolicies || {},
      });
      setPreview(p);
    });
  }
  async function start() {
    await perform(async () => {
      if (!preview) return;
      const j = await api<Job>("/transfers", { token: preview.token });
      setJob(j);
      setPreview(null);
      await refresh();
    });
  }
  return (
    <>
      <div className="page-title">
        <div>
          <div className="overline">ORACLE → ORACLE</div>
          <h1>Veri aktarımı</h1>
          <p>
            Senaryo kapsamını önizleyin, test şemanıza tutarlı bir veri kümesi
            aktarın.
          </p>
        </div>
        <button onClick={() => refresh().catch((e) => setError(e.message))}>
          <RefreshCw size={15} />
          Geçmişi yenile
        </button>
      </div>
      {error && (
        <div className="alert error">
          <AlertCircle size={18} />
          {error}
        </div>
      )}
      <div className="transfer-layout">
        <section className="panel">
          <div className="panel-heading">
            <Database size={20} />
            <h2>Aktarım ayarları</h2>
          </div>
          <fieldset
            className="form-body transfer-fields"
            disabled={busy || active(job)}
          >
            <label className="field">
              <span>Senaryo</span>
              <select
                value={scenarioId}
                onChange={(e) => selectScenario(e.target.value)}
              >
                <option value="">Senaryo seçin</option>
                {scenarios.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
            <div className="form-grid">
              <label className="field">
                <span>Kaynak bağlantısı</span>
                <select
                  value={sourceId}
                  onChange={(e) => {
                    invalidate();
                    setSourceId(e.target.value);
                    setSourcePassword("");
                  }}
                >
                  <option value="">Kaynak seçin</option>
                  {profiles.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>Yazılabilir test hedefi</span>
                <select
                  value={targetId}
                  onChange={(e) => {
                    invalidate();
                    setTargetId(e.target.value);
                    setTargetPassword("");
                  }}
                >
                  <option value="">Hedef seçin</option>
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
              <label className="field">
                <span>Kaynak parolası</span>
                <input
                  type="password"
                  value={sourcePassword}
                  onChange={(e) => {
                    invalidate();
                    setSourcePassword(e.target.value);
                  }}
                  placeholder="Kayıtlı parolayı kullan"
                />
              </label>
              <label className="field">
                <span>Hedef parolası</span>
                <input
                  type="password"
                  value={targetPassword}
                  onChange={(e) => {
                    invalidate();
                    setTargetPassword(e.target.value);
                  }}
                  placeholder="Kayıtlı parolayı kullan"
                />
              </label>
            </div>
            <label className="field">
              <span>
                Başlangıç koşulu ·{" "}
                {scenario ? scenario.owner + "." + scenario.table : "—"}
              </span>
              <input
                value={condition}
                onChange={(e) => {
                  invalidate();
                  setCondition(e.target.value);
                }}
                placeholder="DURUM = 'AKTIF'"
              />
            </label>
            <small className="helper">
              Karşılaştırma, AND/OR/NOT, IN, LIKE, IS NULL ve DATE 'YYYY-MM-DD'
              desteklenir. Değerler bind parametresi olarak kullanılır.
            </small>
            <label className="field">
              <span>En fazla başlangıç kaydı</span>
              <input
                type="number"
                min={1}
                max={1000000}
                value={limit}
                onChange={(e) => {
                  invalidate();
                  setLimit(Number(e.target.value));
                }}
              />
            </label>
            <div className="read-only">
              <ShieldCheck size={18} />
              <span>
                PK davranışı senaryodaki politikaya bağlıdır. Kaynak salt
                okunur; tüm hedef yazmaları tek transaction içindedir.
              </span>
            </div>
            <button
              className="primary"
              disabled={!scenario || !targetId}
              onClick={prepare}
            >
              <Eye size={16} />
              {busy ? "Kapsam hesaplanıyor…" : "Önizleme oluştur"}
            </button>
          </fieldset>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <Eye size={20} />
            <h2>Aktarım önizlemesi</h2>
            {preview && (
              <span className="count">{number(preview.total)} kayıt</span>
            )}
          </div>
          {!preview ? (
            <div className="empty large">
              <ArrowRight size={37} />
              <h3>Önce kapsamı doğrulayın</h3>
              <p>
                Hedefe yazmadan önce tablo sırası ve gerçek kaynak kayıt
                sayıları gösterilir.
              </p>
            </div>
          ) : (
            <>
              <div className="preview-route">
                <span>{preview.source}</span>
                <ArrowRight size={17} />
                <span>{preview.target}</span>
              </div>
              <table>
                <thead>
                  <tr>
                    <th>Sıra / tablo</th>
                    <th>Seçilen</th>
                    <th>Primary key</th>
                    <th>Karar</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.tables.map((t, i) => (
                    <tr key={t.table}>
                      <td>
                        {i + 1}. {t.table}
                      </td>
                      <td>{number(t.selected)}</td>
                      <td>{t.primaryKey.join(", ")}</td>
                      <td>
                        {t.keyPolicy.mode === "KEEP"
                          ? t.keyPolicy.onConflict === "SKIP"
                            ? "Koru / atla"
                            : "Koru / çakışmada hata"
                          : t.keyPolicy.mode === "SEQUENCE"
                            ? t.keyPolicy.column + " ← " + t.keyPolicy.sequence
                            : t.keyPolicy.mode === "MAX"
                              ? t.keyPolicy.column +
                                " ← MAX + " +
                                t.keyPolicy.increment
                              : t.keyPolicy.mode === "BUSINESS"
                                ? "İş anahtarı: " +
                                  t.keyPolicy.businessColumns.join(", ")
                                : t.keyPolicy.column + " ← IDENTITY"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="form-body">
                <div className="note">
                  {preview.warnings.map((w) => (
                    <p key={w}>{w}</p>
                  ))}
                </div>
                <small className="helper">
                  Snapshot son geçerlilik:{" "}
                  {new Date(preview.expiresAt).toLocaleTimeString("tr-TR")}
                </small>
                <button
                  className="primary"
                  disabled={
                    busy ||
                    active(job) ||
                    preview.total === 0 ||
                    Date.now() > Date.parse(preview.expiresAt)
                  }
                  onClick={start}
                >
                  <Play size={16} />
                  Önizlenen kapsamı aktar
                </button>
                <button disabled={busy} onClick={() => setPreview(null)}>
                  Önizlemeyi kapat
                </button>
              </div>
            </>
          )}
        </section>
      </div>
      {job && (
        <section className="panel transfer-monitor">
          <div className="panel-heading">
            {job.status === "COMPLETED" ? (
              <Check size={20} />
            ) : (
              <Clock size={20} />
            )}
            <h2>{statuses[job.status] || job.status}</h2>
            <span className="count">{job.table}</span>
          </div>
          <div className="form-body">
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
            <div className="job-metrics">
              <span>
                İşlenen{" "}
                <strong>
                  {number(job.processed)} / {number(job.total)}
                </strong>
              </span>
              <span>
                {job.committed ? "Eklenen" : "Geçici ekleme"}{" "}
                <strong>{number(job.inserted)}</strong>
              </span>
              <span>
                Atlanan <strong>{number(job.skipped)}</strong>
              </span>
            </div>
            <p className="helper">
              {active(job)
                ? "Yazmalar henüz commit edilmedi. İptal veya hatada tamamı geri alınır."
                : job.message}
            </p>
            {active(job) && (
              <button
                onClick={() =>
                  perform(async () => {
                    await api("/transfers/" + job.id + "/cancel", {});
                  })
                }
                disabled={busy}
              >
                <Square size={14} />
                İptal et ve geri al
              </button>
            )}
          </div>
        </section>
      )}
      <section className="panel">
        <div className="panel-heading">
          <h2>Aktarım geçmişi</h2>
          <span className="count">Son 100 çalışma</span>
        </div>
        {!history.length ? (
          <div className="empty compact">Henüz aktarım çalıştırılmadı.</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Başlangıç</th>
                <th>Kaynak → Hedef</th>
                <th>Durum</th>
                <th>Eklenen / atlanan</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {history.map((j) => (
                <tr key={j.id}>
                  <td>{new Date(j.startedAt).toLocaleString("tr-TR")}</td>
                  <td>
                    {j.source} → {j.target}
                  </td>
                  <td>{statuses[j.status] || j.status}</td>
                  <td>
                    {number(j.inserted)} / {number(j.skipped)}
                  </td>
                  <td>
                    <button onClick={() => setJob(j)}>Detay</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
