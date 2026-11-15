import { useState } from "react";
import { KeyRound, Search, ArrowRight } from "lucide-react";
import type { Profile } from "./main";
export type KeyPolicy = {
  mode: string;
  column: string;
  sequence: string;
  start: number;
  increment: number;
  groupColumns: string[];
  businessColumns: string[];
  onConflict: string;
};
export const defaultPolicy = (): KeyPolicy => ({
  mode: "KEEP",
  column: "",
  sequence: "",
  start: 1,
  increment: 1,
  groupColumns: [],
  businessColumns: [],
  onConflict: "ERROR",
});
type Suggestion = {
  column: string;
  mode: string;
  sequence: string;
  confidence: string;
  reason: string;
};
type TableOptions = {
  primaryKey: string[];
  columns: { name: string; type: string; integer: boolean }[];
  identities: Record<string, string>;
  suggestions: Suggestion[];
};
type Options = {
  owner: string;
  sequences: string[];
  tables: Record<string, TableOptions>;
};
type Api = <T>(path: string, body?: unknown, method?: string) => Promise<T>;
export function KeyPolicies({
  tables,
  value,
  onChange,
  profiles,
  api,
}: {
  tables: string[];
  value: Record<string, KeyPolicy>;
  onChange: (v: Record<string, KeyPolicy>) => void;
  profiles: Profile[];
  api: Api;
}) {
  const [target, setTarget] = useState(""),
    [password, setPassword] = useState(""),
    [options, setOptions] = useState<Options | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  function update(table: string, patch: Partial<KeyPolicy>) {
    onChange({
      ...value,
      [table]: { ...defaultPolicy(), ...value[table], ...patch },
    });
  }
  async function suggest() {
    setBusy(true);
    setError("");
    try {
      setOptions(
        await api<Options>("/keys/options", {
          connectionId: target,
          password,
          tables,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Öneriler alınamadı.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel key-policy-panel">
      <div className="panel-heading">
        <KeyRound size={20} />
        <h2>PK ve kayıt eşleştirme kararları</h2>
        <span className="count">Senaryoyla paylaşılır</span>
      </div>
      <div className="form-body">
        <div className="note">
          Varsayılan: kaynak anahtarını koru, çakışmada tüm aktarımı geri al.
          Yeni üretilen anahtarlar bağlı FK’lere otomatik eşlenir.
          Sequence/identity değerleri rollback ile geri dönmez.
        </div>
        <div className="key-suggestion-bar">
          <label className="field">
            <span>Öneriler için hedef bağlantı</span>
            <select
              value={target}
              onChange={(e) => {
                setTarget(e.target.value);
                setOptions(null);
              }}
            >
              <option value="">Hedef seçin</option>
              {profiles
                .filter((p) => p.environment !== "PROD")
                .map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
            </select>
          </label>
          <label className="field">
            <span>Oturum parolası</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Kayıtlı parola varsa boş bırakın"
            />
          </label>
          <button disabled={busy || !target} onClick={suggest}>
            <Search size={15} />
            {busy ? "İnceleniyor…" : "Sequence / identity öner"}
          </button>
        </div>
        {error && <div className="alert error">{error}</div>}
        {tables.map((table) => {
          const p = { ...defaultPolicy(), ...value[table] },
            meta = options?.tables[table];
          return (
            <article className="key-policy-card" key={table}>
              <div className="key-table-title">
                <strong>{table}</strong>
                <span>
                  {meta?.primaryKey.length
                    ? "PK: " + meta.primaryKey.join(", ")
                    : "PK Oracle metadata’dan doğrulanır"}
                </span>
              </div>
              <div className="form-grid">
                <label className="field">
                  <span>Anahtar yöntemi</span>
                  <select
                    value={p.mode}
                    onChange={(e) =>
                      update(table, {
                        mode: e.target.value,
                        column: p.column || meta?.primaryKey[0] || "",
                      })
                    }
                  >
                    <option value="KEEP">
                      Kaynak anahtarını koru / FK’den eşle
                    </option>
                    <option value="SEQUENCE">Hedef sequence ile üret</option>
                    <option value="IDENTITY">Hedef identity ile üret</option>
                    <option value="MAX">MAX + artış ile üret</option>
                    <option value="BUSINESS">
                      İş anahtarıyla mevcut hedef kaydı bul
                    </option>
                  </select>
                </label>
                {p.mode === "KEEP" ? (
                  <label className="field">
                    <span>Kaynak PK hedefte varsa</span>
                    <select
                      value={p.onConflict}
                      onChange={(e) =>
                        update(table, { onConflict: e.target.value })
                      }
                    >
                      <option value="ERROR">Hata ver ve rollback</option>
                      <option value="SKIP">
                        Açık seçim: mevcut kaydı kullan / atla
                      </option>
                    </select>
                  </label>
                ) : p.mode === "BUSINESS" ? (
                  <label className="field">
                    <span>
                      İş anahtarı kolonları · hedefte UNIQUE/PK gerekli
                    </span>
                    <input
                      value={p.businessColumns.join(", ")}
                      onChange={(e) =>
                        update(table, {
                          businessColumns: e.target.value
                            .split(",")
                            .map((v) => v.trim().toUpperCase()),
                        })
                      }
                      placeholder="URUN_KODU"
                    />
                  </label>
                ) : (
                  <label className="field">
                    <span>Üretilecek sayısal PK kolonu</span>
                    <input
                      list={"pk-" + table}
                      value={p.column}
                      onChange={(e) =>
                        update(table, { column: e.target.value.toUpperCase() })
                      }
                      placeholder="ID"
                    />
                    <datalist id={"pk-" + table}>
                      {meta?.primaryKey.map((c) => (
                        <option key={c}>{c}</option>
                      ))}
                    </datalist>
                  </label>
                )}
                {p.mode === "SEQUENCE" && (
                  <label className="field">
                    <span>Hedef sequence adı</span>
                    <input
                      list={"seq-" + table}
                      value={p.sequence}
                      onChange={(e) =>
                        update(table, {
                          sequence: e.target.value.toUpperCase(),
                        })
                      }
                      placeholder={"SEQ_" + table}
                    />
                    <datalist id={"seq-" + table}>
                      {options?.sequences.map((s) => (
                        <option key={s}>{s}</option>
                      ))}
                    </datalist>
                  </label>
                )}
                {p.mode === "MAX" && (
                  <>
                    <label className="field">
                      <span>Boş tablo / grup başlangıcı</span>
                      <input
                        type="number"
                        min={0}
                        value={p.start}
                        onChange={(e) =>
                          update(table, { start: Number(e.target.value) })
                        }
                      />
                    </label>
                    <label className="field">
                      <span>Artış</span>
                      <input
                        type="number"
                        min={1}
                        value={p.increment}
                        onChange={(e) =>
                          update(table, { increment: Number(e.target.value) })
                        }
                      />
                    </label>
                    <label className="field">
                      <span>
                        Grup kolonları · isteğe bağlı diğer PK parçaları
                      </span>
                      <input
                        value={p.groupColumns.join(", ")}
                        onChange={(e) =>
                          update(table, {
                            groupColumns: e.target.value
                              .split(",")
                              .map((v) => v.trim().toUpperCase()),
                          })
                        }
                        placeholder="PLAN_ID"
                      />
                    </label>
                  </>
                )}
              </div>
              {p.mode === "MAX" && (
                <p className="helper">
                  Ek anahtar tahsis kilidi yoktur. Eşzamanlı PK/unique çakışması
                  aktarımın tamamını rollback eder; otomatik tekrar yapılmaz.
                </p>
              )}
              {p.mode === "BUSINESS" && (
                <p className="helper">
                  Mevcut hedef kayıt bulunamazsa aktarım durur. Bulunursa
                  hedefin anahtarları kullanılır; mevcut içerik güncellenmez.
                </p>
              )}
              {p.mode === "KEEP" && p.onConflict === "SKIP" && (
                <p className="helper">
                  PK eşitliğinin aynı iş kaydını gösterdiği varsayılır. Bu
                  seçeneği yalnızca bunu bildiğiniz tablolarda kullanın.
                </p>
              )}
              {meta?.suggestions.length ? (
                <div className="key-suggestions">
                  {meta.suggestions.map((s, i) => (
                    <button
                      key={i}
                      onClick={() =>
                        update(table, {
                          mode: s.mode,
                          column: s.column,
                          sequence: s.mode === "SEQUENCE" ? s.sequence : "",
                        })
                      }
                    >
                      <span>
                        <strong>
                          {s.column} →{" "}
                          {s.mode === "IDENTITY" ? "IDENTITY" : s.sequence}
                        </strong>
                        <small>
                          {s.confidence === "HIGH"
                            ? "Metadata kanıtı"
                            : "İsim önerisi"}{" "}
                          · {s.reason}
                        </small>
                      </span>
                      <ArrowRight size={14} />
                    </button>
                  ))}
                </div>
              ) : null}
            </article>
          );
        })}
        <p className="helper">
          Öneri üretirken NEXTVAL çağrılmaz. Hedef bağlantı ve parola senaryo
          dosyasına kaydedilmez. Yalnızca onayladığınız yöntem ve nesne adları
          paylaşılır.
        </p>
      </div>
    </section>
  );
}
