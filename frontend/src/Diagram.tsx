import { useEffect, useRef, useState } from "react";
import {
  Database,
  KeyRound,
  Plus,
  Scan,
  ZoomIn,
  ZoomOut,
  Link2,
  GripVertical,
} from "lucide-react";
import type { Relation } from "./main";
export type Point = { x: number; y: number };
export type DiagramData = {
  tables: string[];
  positions: Record<string, Point>;
};
export function edgeKey(r: Relation) {
  return r.CHILD_OWNER + "." + r.CONSTRAINT_NAME;
}
export function orient(root: string, relations: Relation[]): Relation[] {
  const distance: Record<string, number> = { [root]: 0 };
  const queue = [root];
  while (queue.length) {
    const n = queue.shift()!;
    for (const r of relations) {
      const other =
        r.PARENT_TABLE === n
          ? r.CHILD_TABLE
          : r.CHILD_TABLE === n
            ? r.PARENT_TABLE
            : "";
      if (other && distance[other] === undefined) {
        distance[other] = distance[n] + 1;
        queue.push(other);
      }
    }
  }
  return relations.map((r) => ({
    ...r,
    DIRECTION:
      (distance[r.PARENT_TABLE] ?? 999) <= (distance[r.CHILD_TABLE] ?? 999)
        ? "CHILD"
        : "PARENT",
  }));
}
export function connected(root: string, relations: Relation[]) {
  const found = new Set<string>();
  if (!root) return found;
  const queue = [root];
  while (queue.length) {
    const n = queue.shift()!;
    if (found.has(n)) continue;
    found.add(n);
    for (const r of relations) {
      if (r.PARENT_TABLE === n && !found.has(r.CHILD_TABLE))
        queue.push(r.CHILD_TABLE);
      if (r.CHILD_TABLE === n && !found.has(r.PARENT_TABLE))
        queue.push(r.PARENT_TABLE);
    }
  }
  return found;
}
export function arrange(
  root: string,
  tables: string[],
  relations: Relation[],
): Record<string, Point> {
  const levels: Record<string, number> = { [root]: 0 };
  for (let i = 0; i < tables.length; i++)
    for (const r of relations) {
      if (
        levels[r.PARENT_TABLE] !== undefined &&
        levels[r.CHILD_TABLE] === undefined
      )
        levels[r.CHILD_TABLE] = levels[r.PARENT_TABLE] + 1;
      if (
        levels[r.CHILD_TABLE] !== undefined &&
        levels[r.PARENT_TABLE] === undefined
      )
        levels[r.PARENT_TABLE] = levels[r.CHILD_TABLE] - 1;
    }
  const min = Math.min(0, ...Object.values(levels));
  const rows: Record<number, number> = {};
  const result: Record<string, Point> = {};
  tables.forEach((t) => {
    const level = levels[t] ?? Math.max(...Object.values(levels), 0) + 1;
    const row = rows[level] || 0;
    rows[level] = row + 1;
    result[t] = { x: 40 + (level - min) * 245, y: 60 + row * 150 };
  });
  return result;
}
export function Diagram({
  disabled = false,
  graph,
  relations,
  root,
  selected,
  selectedEdge,
  meta,
  onSelect,
  onEdge,
  onMove,
  onLink,
  onAdd,
  onArrange,
  fitToken,
}: {
  disabled?: boolean;
  graph: DiagramData;
  relations: Relation[];
  root: string;
  selected: string;
  selectedEdge: string;
  meta: Record<string, { primaryKey?: string[]; columns?: unknown[] }>;
  onSelect: (table: string) => void;
  onEdge: (id: string) => void;
  onMove: (table: string, p: Point) => void;
  onLink: (parent: string, child: string) => void;
  onAdd: () => void;
  onArrange: () => void;
  fitToken: number;
}) {
  const viewport = useRef<HTMLDivElement>(null),
    drag = useRef<{
      table?: string;
      clientX: number;
      clientY: number;
      start: Point;
    } | null>(null);
  const [pan, setPan] = useState<Point>({ x: 20, y: 15 }),
    [zoom, setZoom] = useState(0.85),
    [linkStart, setLinkStart] = useState(""),
    [linkPoint, setLinkPoint] = useState<Point | null>(null),
    [linkTarget, setLinkTarget] = useState("");
  const linkGesture = useRef<{
    parent: string;
    pointerId: number | null;
    origin: Point;
    moved: boolean;
  } | null>(null);
  function clearLink() {
    const gesture = linkGesture.current;
    linkGesture.current = null;
    if (
      gesture?.pointerId != null &&
      viewport.current?.hasPointerCapture(gesture.pointerId)
    )
      viewport.current.releasePointerCapture(gesture.pointerId);
    setLinkStart("");
    setLinkPoint(null);
    setLinkTarget("");
  }
  function finishLink(child: string) {
    const parent = linkGesture.current?.parent;
    clearLink();
    if (!disabled && parent && child && parent !== child) onLink(parent, child);
  }
  function tableAt(x: number, y: number) {
    const node = document
      .elementFromPoint(x, y)
      ?.closest<HTMLElement>(".diagram-node");
    return node && viewport.current?.contains(node)
      ? node.dataset.table || ""
      : "";
  }
  function canvasPoint(x: number, y: number): Point {
    const rect = viewport.current!.getBoundingClientRect();
    return {
      x: (x - rect.left - pan.x) / zoom,
      y: (y - rect.top - pan.y) / zoom,
    };
  }
  useEffect(() => {
    const cancel = (e: KeyboardEvent) => {
      if (e.key === "Escape") clearLink();
    };
    window.addEventListener("keydown", cancel);
    window.addEventListener("blur", clearLink);
    return () => {
      window.removeEventListener("keydown", cancel);
      window.removeEventListener("blur", clearLink);
    };
  }, []);
  useEffect(() => {
    clearLink();
    drag.current = null;
  }, [disabled, graph.tables.join("\0")]);
  const scope = connected(root, relations);
  function fit() {
    const box = viewport.current;
    if (!box || !graph.tables.length) return;
    const points = graph.tables.map(
      (t, i) => graph.positions[t] || { x: 40 + i * 230, y: 60 },
    );
    const minX = Math.min(...points.map((p) => p.x)),
      minY = Math.min(...points.map((p) => p.y)),
      maxX = Math.max(...points.map((p) => p.x)) + 190,
      maxY = Math.max(...points.map((p) => p.y)) + 92;
    const scale = Math.min(
      1.05,
      Math.max(
        0.2,
        Math.min(
          (box.clientWidth - 55) / (maxX - minX),
          (box.clientHeight - 80) / (maxY - minY),
        ),
      ),
    );
    setZoom(scale);
    setPan({ x: 25 - minX * scale, y: 40 - minY * scale });
  }
  useEffect(() => {
    fit();
  }, [fitToken]);
  function startDrag(e: React.PointerEvent, table: string) {
    if (
      disabled ||
      linkGesture.current ||
      e.button !== 0 ||
      (e.target as HTMLElement).closest("button")
    )
      return;
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = {
      table,
      clientX: e.clientX,
      clientY: e.clientY,
      start: graph.positions[table] || { x: 40, y: 60 },
    };
    onSelect(table);
  }
  function move(e: React.PointerEvent) {
    const gesture = linkGesture.current;
    if (gesture) {
      if (gesture.pointerId !== null && gesture.pointerId !== e.pointerId)
        return;
      if (
        Math.hypot(e.clientX - gesture.origin.x, e.clientY - gesture.origin.y) >
        4
      )
        gesture.moved = true;
      setLinkPoint(canvasPoint(e.clientX, e.clientY));
      const target = tableAt(e.clientX, e.clientY);
      setLinkTarget(target !== gesture.parent ? target : "");
      return;
    }
    const d = drag.current;
    if (!d) return;
    if (d.table)
      onMove(d.table, {
        x: Math.min(
          3500,
          Math.max(0, d.start.x + (e.clientX - d.clientX) / zoom),
        ),
        y: Math.min(
          2200,
          Math.max(0, d.start.y + (e.clientY - d.clientY) / zoom),
        ),
      });
    else
      setPan({
        x: d.start.x + e.clientX - d.clientX,
        y: d.start.y + e.clientY - d.clientY,
      });
  }
  return (
    <div className="diagram-wrap">
      <div className="diagram-actions">
        <span>
          <span className="legend-fk" />
          FK <span className="legend-manual" />
          Custom relationship
        </span>
        <div>
          <button title="Add table" onClick={onAdd}>
            <Plus size={14} />
          </button>
          <button title="Auto layout" onClick={onArrange}>
            <GripVertical size={14} />
          </button>
          <button title="Fit to view" onClick={fit}>
            <Scan size={14} />
          </button>
          <button
            title="Zoom out"
            onClick={() => setZoom((z) => Math.max(0.2, z - 0.1))}
          >
            <ZoomOut size={14} />
          </button>
          <span>{Math.round(zoom * 100)}%</span>
          <button
            title="Zoom in"
            onClick={() => setZoom((z) => Math.min(1.8, z + 0.1))}
          >
            <ZoomIn size={14} />
          </button>
        </div>
      </div>
      <div
        className={"diagram-viewport " + (linkStart ? "is-linking" : "")}
        ref={viewport}
        onPointerDown={(e) => {
          if (
            linkGesture.current ||
            e.button !== 0 ||
            (e.target as HTMLElement).closest(".diagram-node, button")
          )
            return;
          e.currentTarget.setPointerCapture(e.pointerId);
          drag.current = { clientX: e.clientX, clientY: e.clientY, start: pan };
        }}
        onPointerMove={move}
        onPointerUp={(e) => {
          drag.current = null;
          const gesture = linkGesture.current;
          if (!gesture || gesture.pointerId !== e.pointerId) return;
          const target = tableAt(e.clientX, e.clientY);
          if (target && target !== gesture.parent) finishLink(target);
          else if (gesture.moved) clearLink();
          else {
            gesture.pointerId = null;
            if (e.currentTarget.hasPointerCapture(e.pointerId))
              e.currentTarget.releasePointerCapture(e.pointerId);
          }
        }}
        onPointerCancel={() => {
          drag.current = null;
          clearLink();
        }}
        onLostPointerCapture={(e) => {
          drag.current = null;
          if (linkGesture.current?.pointerId === e.pointerId) clearLink();
        }}
      >
        {!graph.tables.length ? (
          <div className="diagram-empty">
            <Database size={36} />
            <h3>Design your transfer</h3>
            <p>
              Add a table to begin. Discover foreign keys or connect tables to
              define your own relationships.
            </p>
            <button onClick={onAdd}>
              <Plus size={14} />
              Add your first table
            </button>
          </div>
        ) : (
          <div
            className="diagram-stage"
            style={{
              transform: `translate(${pan.x}px,${pan.y}px) scale(${zoom})`,
            }}
          >
            <svg className="diagram-lines" width="4000" height="2600">
              <defs>
                <marker
                  id="graph-arrow"
                  viewBox="0 0 10 10"
                  refX="9"
                  refY="5"
                  markerWidth="7"
                  markerHeight="7"
                  orient="auto-start-reverse"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#8398b7" />
                </marker>
                <marker
                  id="graph-draft-arrow"
                  viewBox="0 0 10 10"
                  refX="9"
                  refY="5"
                  markerWidth="7"
                  markerHeight="7"
                  orient="auto"
                >
                  <path d="M 0 0 L 10 5 L 0 10 z" fill="#8c6bc0" />
                </marker>
              </defs>
              {linkStart &&
                linkPoint &&
                (() => {
                  const index = graph.tables.indexOf(linkStart);
                  const p = graph.positions[linkStart] || {
                    x: 40 + index * 230,
                    y: 60,
                  };
                  const from = { x: p.x + 190, y: p.y + 43 };
                  const targetIndex = graph.tables.indexOf(linkTarget);
                  const target = graph.positions[linkTarget] || {
                    x: 40 + targetIndex * 230,
                    y: 60,
                  };
                  const to = linkTarget
                    ? { x: target.x, y: target.y + 43 }
                    : linkPoint;
                  const curve = Math.max(65, Math.abs(to.x - from.x) / 2);
                  return (
                    <path
                      className="draft-link"
                      d={`M ${from.x} ${from.y} C ${from.x + curve} ${from.y}, ${to.x - curve} ${to.y}, ${to.x} ${to.y}`}
                      markerEnd="url(#graph-draft-arrow)"
                    />
                  );
                })()}
              {relations.map((r) => {
                const a = graph.positions[r.PARENT_TABLE],
                  b = graph.positions[r.CHILD_TABLE];
                if (!a || !b) return null;
                const from = { x: a.x + 190, y: a.y + 43 },
                  to = { x: b.x, y: b.y + 43 };
                const curve = Math.max(65, Math.abs(to.x - from.x) / 2);
                const d = `M ${from.x} ${from.y} C ${from.x + curve} ${from.y}, ${to.x - curve} ${to.y}, ${to.x} ${to.y}`;
                return (
                  <g
                    key={edgeKey(r)}
                    className={
                      "graph-edge " +
                      (r.SOURCE === "MANUAL" ? "manual " : "") +
                      (selectedEdge === edgeKey(r) ? "active" : "")
                    }
                    onPointerDown={(e) => e.stopPropagation()}
                    onClick={(e) => {
                      e.stopPropagation();
                      onEdge(edgeKey(r));
                    }}
                  >
                    <path d={d} className="edge-hit" />
                    <path
                      d={d}
                      className="edge-line"
                      markerEnd="url(#graph-arrow)"
                    />
                    <text
                      x={(from.x + to.x) / 2}
                      y={(from.y + to.y) / 2 - 10}
                      textAnchor="middle"
                    >
                      {r.COLUMN_PAIRS.length > 1
                        ? r.COLUMN_PAIRS.length + " columns"
                        : r.COLUMN_PAIRS[0]?.parent +
                          " → " +
                          r.COLUMN_PAIRS[0]?.child}
                    </text>
                  </g>
                );
              })}
            </svg>
            {graph.tables.map((table, i) => {
              const p = graph.positions[table] || { x: 40 + i * 230, y: 60 };
              const info = meta[table];
              return (
                <article
                  key={table}
                  data-table={table}
                  className={
                    "diagram-node " +
                    (linkTarget === table ? "link-target " : "") +
                    (selected === table ? "selected " : "") +
                    (root === table ? "root-node " : "") +
                    (!scope.has(table) ? "unconnected" : "")
                  }
                  style={{ left: p.x, top: p.y }}
                  onClick={(e) => {
                    e.stopPropagation();
                    if (
                      linkGesture.current &&
                      linkGesture.current.parent !== table
                    )
                      finishLink(table);
                    else onSelect(table);
                  }}
                >
                  <button
                    className="node-port input-port"
                    aria-label={table + " input port"}
                    title="Connect the parent table's output port here"
                    onPointerDown={(e) => e.stopPropagation()}
                    disabled={disabled}
                    onClick={(e) => {
                      e.stopPropagation();
                      if (
                        linkGesture.current?.parent !== table &&
                        linkGesture.current
                      )
                        finishLink(table);
                    }}
                  />
                  <header
                    onPointerDown={(e) => startDrag(e, table)}
                    onPointerMove={move}
                    onPointerUp={() => {
                      drag.current = null;
                    }}
                  >
                    <Database size={15} />
                    <strong>{table}</strong>
                    {root === table && <b>ROOT</b>}
                  </header>
                  <div className="node-info">
                    <span>
                      <KeyRound size={12} />
                      {info?.primaryKey?.join(", ") || "Inspect columns"}
                    </span>
                    <small>
                      {!scope.has(table)
                        ? "Connection required"
                        : info?.columns?.length
                          ? info.columns.length + " columns"
                          : "Table"}
                    </small>
                  </div>
                  <button
                    className={
                      "node-port output-port " +
                      (linkStart === table ? "armed" : "")
                    }
                    aria-label={table + " output port"}
                    title="Drag to another table to define a relationship"
                    disabled={disabled}
                    onPointerDown={(e) => {
                      e.stopPropagation();
                      if (disabled || e.button !== 0) return;
                      e.preventDefault();
                      clearLink();
                      drag.current = null;
                      linkGesture.current = {
                        parent: table,
                        pointerId: e.pointerId,
                        origin: { x: e.clientX, y: e.clientY },
                        moved: false,
                      };
                      setLinkStart(table);
                      setLinkPoint(canvasPoint(e.clientX, e.clientY));
                      viewport.current?.setPointerCapture(e.pointerId);
                    }}
                    onClick={(e) => {
                      e.stopPropagation();
                      if (e.detail === 0 && !disabled) {
                        clearLink();
                        linkGesture.current = {
                          parent: table,
                          pointerId: null,
                          origin: { x: 0, y: 0 },
                          moved: false,
                        };
                        setLinkStart(table);
                      }
                    }}
                  />
                </article>
              );
            })}
          </div>
        )}
      </div>
      {linkStart && (
        <div className="link-hint">
          <Link2 size={13} />
          {linkStart}: drag to a child table, or select its input port. Esc to
          cancel.
          <button onClick={clearLink}>Cancel</button>
        </div>
      )}
      <div className="diagram-footer">
        <span>
          {graph.tables.length} tables · {relations.length} relationships
        </span>
        <span>
          Drag headers to move · Drag canvas to pan · Drag ports to connect
        </span>
      </div>
    </div>
  );
}
