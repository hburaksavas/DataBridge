import { useId, useState } from "react";
import { Search, Check, ChevronDown } from "lucide-react";
import type { Scenario } from "./main";

export function ScenarioPicker({
  value,
  selectedId,
  scenarios,
  disabled,
  onChange,
  onSelect,
}: {
  value: string;
  selectedId?: string;
  scenarios: Scenario[];
  disabled: boolean;
  onChange: (value: string) => void;
  onSelect: (id: string) => void;
}) {
  const [showAll, setShowAll] = useState(false);
  const [open, setOpen] = useState(false),
    [active, setActive] = useState(-1);
  const listId = useId();
  const key = (s: string) => s.normalize("NFKC").trim().toLowerCase();
  const matches = scenarios
    .filter((s) => showAll || key(s.name).includes(key(value)))
    .sort((a, b) => a.name.localeCompare(b.name));
  const exact = matches.find((s) => key(s.name) === key(value));
  function choose(s: Scenario) {
    setOpen(false);
    setActive(-1);
    onSelect(s.id!);
  }
  return (
    <div
      className="scenario-picker"
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false);
      }}
    >
      <Search size={14} />
      <input
        role="combobox"
        aria-label="Scenario"
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={
          open && active >= 0 ? listId + "-" + active : undefined
        }
        placeholder="Find or name a scenario…"
        value={value}
        disabled={disabled}
        autoComplete="off"
        onFocus={(e) => {
          e.target.select();
          setShowAll(
            value === scenarios.find((s) => s.id === selectedId)?.name,
          );
          setOpen(true);
          setActive(-1);
        }}
        onChange={(e) => {
          onChange(e.target.value);
          setShowAll(false);
          setOpen(true);
          setActive(-1);
        }}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            e.preventDefault();
            setOpen(false);
          } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
            e.preventDefault();
            setOpen(true);
            setActive((i) =>
              matches.length
                ? (i + (e.key === "ArrowDown" ? 1 : -1) + matches.length) %
                  matches.length
                : -1,
            );
          } else if (e.key === "Enter") {
            e.preventDefault();
            if (open && matches[active]) choose(matches[active]);
            else if (exact) choose(exact);
            else setOpen(false);
          }
        }}
      />
      <button
        type="button"
        title="Show scenarios"
        aria-label="Show scenarios"
        disabled={disabled}
        onPointerDown={(e) => e.preventDefault()}
        onClick={() => {
          setOpen((v) => !v);
          setShowAll(true);
          setActive(-1);
        }}
      >
        <ChevronDown size={13} />
      </button>
      {open && (
        <div className="scenario-suggestions">
          <div className="scenario-suggestions-title">
            {value.trim() ? "Matching scenarios" : "Saved scenarios"}
          </div>
          <div role="listbox" id={listId} aria-label="Saved scenarios">
            {matches.map((s, i) => (
              <button
                type="button"
                role="option"
                id={listId + "-" + i}
                aria-selected={s.id === selectedId}
                className={active === i ? "highlighted" : ""}
                key={s.id}
                onPointerDown={(e) => e.preventDefault()}
                onMouseEnter={() => setActive(i)}
                onClick={() => choose(s)}
              >
                <span>
                  <strong>{s.name}</strong>
                  <small>
                    {s.owner}.{s.table}
                  </small>
                </span>
                {s.id === selectedId && <Check size={13} />}
              </button>
            ))}
          </div>
          {!matches.length && <p>No matching scenarios.</p>}
          {value.trim() && !exact && (
            <div className="scenario-name-hint">
              {selectedId ? "Rename to" : "New scenario:"}{" "}
              <strong>{value.trim()}</strong>
            </div>
          )}
          {exact && exact.id !== selectedId && (
            <div className="scenario-name-hint">
              Select the existing scenario to open it.
            </div>
          )}
        </div>
      )}
    </div>
  );
}
