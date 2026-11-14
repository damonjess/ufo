import { useEffect, useMemo, useState } from "react";
import Globe from "./Globe";
import rawData from "./data/sightings.json";
import rawVideos from "./data/videos.json";
import rawSocial from "./data/social.json";
import {
  ERAS,
  ERA_COLOR,
  ERA_LABEL,
  SHAPE_LABEL,
  coordLabel,
  embedUrl,
  eraOf,
  formatDate,
  fullPlace,
  shortDate,
  socialToSighting,
  toSighting,
  videoToSighting,
  type RawSighting,
  type Sighting,
  type SocialClip,
  type VideoClip,
} from "./lib/sightings";

const ALL: Sighting[] = (rawData as unknown as RawSighting[]).map(toSighting);
const VIDEOS = rawVideos as unknown as VideoClip[];
const VIDEO_POINTS: Sighting[] = VIDEOS.map(videoToSighting);
const SOCIAL = rawSocial as unknown as SocialClip[];
const SOCIAL_POINTS: Sighting[] = SOCIAL.map(socialToSighting);
const MIN_Y = Math.min(ALL[0]?.y ?? 1910, ...VIDEO_POINTS.map((v) => v.y), ...SOCIAL_POINTS.map((v) => v.y));
const MAX_Y = Math.max(
  ALL[ALL.length - 1]?.y ?? 2023,
  ...VIDEO_POINTS.map((v) => v.y),
  ...SOCIAL_POINTS.map((v) => v.y),
);
const COUNTRIES = new Set(ALL.map((s) => s.co)).size;
const BIN = 5;
const BIN_START = Math.floor(MIN_Y / BIN) * BIN;

const SHAPES = (() => {
  const counts = new Map<string, number>();
  for (const s of ALL) counts.set(s.shape, (counts.get(s.shape) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
})();

const prefersReduced =
  typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

export default function App() {
  const [mode, setMode] = useState<"reports" | "videos" | "social">("reports");
  const [range, setRange] = useState<[number, number]>([MIN_Y, MAX_Y]);
  const [query, setQuery] = useState("");
  const [shape, setShape] = useState("all");
  const [sort, setSort] = useState<"new" | "old">("new");
  const [selected, setSelected] = useState<Sighting | null>(null);
  const [autoRotate, setAutoRotate] = useState(!prefersReduced);
  const [resetKey, setResetKey] = useState(0);
  const [limit, setLimit] = useState(200);

  const q = query.trim().toLowerCase();

  const base = useMemo(() => {
    if (mode === "social") {
      return SOCIAL_POINTS.filter(
        (s) =>
          !q ||
          `${s.city} ${s.co} ${s.summary}`.toLowerCase().includes(q),
      );
    }
    if (mode === "videos") {
      return VIDEO_POINTS.filter(
        (s) =>
          !q ||
          `${s.city} ${s.co} ${s.video?.title ?? ""} ${s.summary}`.toLowerCase().includes(q),
      );
    }
    return ALL.filter(
      (s) =>
        (shape === "all" || s.shape === shape) &&
        (!q ||
          `${s.city} ${s.region} ${s.co} ${s.summary} ${SHAPE_LABEL[s.shape] ?? ""}`
            .toLowerCase()
            .includes(q)),
    );
  }, [mode, q, shape]);

  const filtered = useMemo(
    () =>
      base
        .filter((s) => s.y >= range[0] && s.y <= range[1])
        .sort((a, b) => (sort === "new" ? b.d.localeCompare(a.d) : a.d.localeCompare(b.d))),
    [base, range, sort],
  );

  const globePoints = useMemo(
    () => filtered.filter((s) => Number.isFinite(s.lat) && Number.isFinite(s.lon)),
    [filtered],
  );

  useEffect(() => setLimit(200), [q, shape, range, sort, mode]);
  useEffect(() => setSelected(null), [mode]);

  const bins = useMemo(() => {
    const count = Math.ceil((MAX_Y - BIN_START + 1) / BIN);
    const arr = Array.from({ length: count }, (_, i) => ({
      from: BIN_START + i * BIN,
      to: BIN_START + i * BIN + BIN - 1,
      n: 0,
    }));
    for (const s of base) {
      const i = Math.floor((s.y - BIN_START) / BIN);
      if (arr[i]) arr[i].n++;
    }
    return arr;
  }, [base]);
  const binMax = Math.max(1, ...bins.map((b) => b.n));

  const activeEra = ERAS.find((e) => e.from === range[0] && e.to === range[1])?.key ?? null;
  const isFiltered = q !== "" || shape !== "all" || range[0] !== MIN_Y || range[1] !== MAX_Y;

  const select = (s: Sighting | null) => setSelected(s);

  const pct = (y: number) => ((y - MIN_Y) / (MAX_Y - MIN_Y)) * 100;

  return (
    <div className="app">
      <header className="top">
        <div className="brand">
          <div className="brand-kicker">
            <span className="dot" />
            A world atlas of UFO reports
          </div>
          <h1>
            Sight<span>ings</span>
          </h1>
          <p className="tagline">
            Every pin is a reported encounter. Spin the globe, narrow the years, read what people saw.
          </p>
        </div>
        <dl className="stats">
          <div>
            <dt>Reports</dt>
            <dd>{ALL.length.toLocaleString("en-US")}</dd>
          </div>
          <div>
            <dt>Countries</dt>
            <dd>{COUNTRIES}</dd>
          </div>
          <div>
            <dt>Years</dt>
            <dd>
              {MIN_Y}–{MAX_Y}
            </dd>
          </div>
        </dl>
      </header>

      <div className="layout">
        <aside className="panel">
          <div className="panel-top">
            <div className="tabs" role="tablist" aria-label="What to show">
              <button
                role="tab"
                aria-selected={mode === "reports"}
                className={mode === "reports" ? "is-on" : ""}
                onClick={() => setMode("reports")}
              >
                Reports <span>{ALL.length.toLocaleString("en-US")}</span>
              </button>
              <button
                role="tab"
                aria-selected={mode === "videos"}
                className={mode === "videos" ? "is-on" : ""}
                onClick={() => setMode("videos")}
              >
                Video <span>{VIDEOS.length}</span>
              </button>
              <button
                role="tab"
                aria-selected={mode === "social"}
                className={mode === "social" ? "is-on" : ""}
                onClick={() => setMode("social")}
              >
                Social <span>{SOCIAL.length}</span>
              </button>
            </div>

            <label className="search">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="11" cy="11" r="6.5" />
                <path d="M16 16l4.5 4.5" />
              </svg>
              <input
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search a city, country or shape"
                aria-label="Search sightings"
              />
            </label>

            <div className={`field ${mode === "social" ? "hide" : ""}`}>
              <span className="field-label">Era</span>
              <div className="chips">
                <button
                  className={`chip ${!activeEra ? "is-on" : ""}`}
                  onClick={() => setRange([MIN_Y, MAX_Y])}
                >
                  All
                </button>
                {ERAS.map((e) => (
                  <button
                    key={e.key}
                    className={`chip ${activeEra === e.key ? "is-on" : ""}`}
                    onClick={() => setRange([Math.max(MIN_Y, e.from), Math.min(MAX_Y, e.to)])}
                    style={activeEra === e.key ? { borderColor: e.color, color: e.color } : undefined}
                  >
                    {e.short}
                  </button>
                ))}
              </div>
            </div>

            <div className={`field ${mode === "social" ? "hide" : ""}`}>
              <div className="field-head">
                <span className="field-label">Years</span>
                <span className="field-value">
                  {range[0]} <span className="dim">—</span> {range[1]}
                </span>
              </div>

              <div className="histo" aria-hidden="true">
                {bins.map((b) => {
                  const inside = b.to >= range[0] && b.from <= range[1];
                  const h = Math.max(3, Math.round((b.n / binMax) * 100));
                  return (
                    <i
                      key={b.from}
                      style={{
                        height: `${h}%`,
                        background: inside ? ERA_COLOR[eraOf(Math.max(b.from, MIN_Y))] : undefined,
                        opacity: inside ? 0.55 : 0.13,
                      }}
                      title={`${b.from}–${b.to}: ${b.n}`}
                    />
                  );
                })}
              </div>

              <div className="slider">
                <div className="slider-rail" />
                <div
                  className="slider-fill"
                  style={{
                    left: `${pct(range[0])}%`,
                    right: `${100 - pct(range[1])}%`,
                    background: `linear-gradient(90deg, ${ERA_COLOR[eraOf(range[0])]}, ${ERA_COLOR[eraOf(range[1])]})`,
                  }}
                />
                <input
                  type="range"
                  min={MIN_Y}
                  max={MAX_Y}
                  value={range[0]}
                  aria-label="Earliest year"
                  onChange={(e) => setRange([Math.min(+e.target.value, range[1] - 1), range[1]])}
                />
                <input
                  type="range"
                  min={MIN_Y}
                  max={MAX_Y}
                  value={range[1]}
                  aria-label="Latest year"
                  onChange={(e) => setRange([range[0], Math.max(+e.target.value, range[0] + 1)])}
                />
              </div>
            </div>

            <div className={`field field-row ${mode === "reports" ? "" : "hide"}`}>
              <label className="select">
                <span className="field-label">Shape</span>
                <select value={shape} onChange={(e) => setShape(e.target.value)}>
                  <option value="all">All shapes</option>
                  {SHAPES.map(([key, n]) => (
                    <option key={key} value={key}>
                      {SHAPE_LABEL[key] ?? key} ({n})
                    </option>
                  ))}
                </select>
              </label>
              <div className="segmented" role="group" aria-label="Sort sightings">
                <button
                  className={sort === "new" ? "is-on" : ""}
                  onClick={() => setSort("new")}
                  aria-pressed={sort === "new"}
                >
                  Newest
                </button>
                <button
                  className={sort === "old" ? "is-on" : ""}
                  onClick={() => setSort("old")}
                  aria-pressed={sort === "old"}
                >
                  Oldest
                </button>
              </div>
            </div>
          </div>

          <div className="results-head">
            <span>
              {filtered.length.toLocaleString("en-US")}{" "}
              {mode === "videos"
                ? filtered.length === 1
                  ? "clip"
                  : "clips"
                : mode === "social"
                  ? filtered.length === 1
                    ? "post"
                    : "posts"
                  : filtered.length === 1
                    ? "report"
                    : "reports"}
            </span>
            {isFiltered && (
              <button
                className="link"
                onClick={() => {
                  setQuery("");
                  setShape("all");
                  setRange([MIN_Y, MAX_Y]);
                }}
              >
                Clear filters
              </button>
            )}
          </div>

          <ul className="list">
            {(mode === "reports" ? filtered.slice(0, limit) : filtered).map((s) => {
              if (s.video) {
                return (
                  <li key={s.id}>
                    <button
                      className={`vcard ${selected?.id === s.id ? "is-active" : ""}`}
                      onClick={() => select(s)}
                    >
                      {s.video.platform === "tiktok" ? (
                        <span className="vbadge">TT</span>
                      ) : (
                        <img
                          className="vthumb"
                          src={`https://i.ytimg.com/vi/${s.video.id}/mqdefault.jpg`}
                          alt=""
                          loading="lazy"
                        />
                      )}
                      <span className="vbody">
                        <span className="vtitle">{s.video.title}</span>
                        <span className="vsub">
                          {s.city} · {s.d.slice(0, 4)}
                          {s.video.platform === "tiktok" ? " · claimed" : ""}
                        </span>
                        <span className="vch">{s.video.channel}</span>
                      </span>
                    </button>
                  </li>
                );
              }
              const date = shortDate(s.d);
              return (
                <li key={s.id}>
                  <button
                    className={`row ${selected?.id === s.id ? "is-active" : ""}`}
                    onClick={() => select(s)}
                  >
                    <span className="row-era" style={{ background: ERA_COLOR[eraOf(s.y)] }} />
                    <span className="row-date">
                      <b>{date.day}</b>
                      <span>
                        {date.month} {date.year}
                      </span>
                    </span>
                    <span className="row-body">
                      <span className="row-place">{s.city || s.co}</span>
                      <span className="row-sub">
                        {s.co}
                        {s.shape && s.shape !== "unknown" ? ` · ${SHAPE_LABEL[s.shape] ?? s.shape}` : ""}
                      </span>
                    </span>
                  </button>
                </li>
              );
            })}
            {filtered.length === 0 && (
              <li className="empty">Nothing matches those filters. Try widening the years.</li>
            )}
          </ul>

          {mode === "reports" && filtered.length > limit && (
            <button className="more" onClick={() => setLimit((l) => l + 200)}>
              Show 200 more
            </button>
          )}

          <p className="source">
            {mode === "videos"
              ? "Video clips are third-party YouTube uploads, embedded here and credited to the channel that published them."
              : mode === "social"
                ? "TikTok uploads collected from public posts. Captions, places and dates are the uploaders' own claims — none of it is verified, so treat these as leads, not evidence."
                : "Reports compiled by the National UFO Reporting Center, via public datasets. Positions are city-level, not exact."}
          </p>
        </aside>

        <section className="stage">
          <div className="stage-meta">
            <span className="mono">DRAG TO ROTATE · SCROLL TO ZOOM</span>
            <span className="mono stage-count">
              {globePoints.length.toLocaleString("en-US")} plotted
            </span>
          </div>

          <Globe
            sightings={globePoints}
            selected={selected?.video?.shared ? null : selected}
            onSelect={select}
            autoRotate={autoRotate}
            onUserRotate={() => setAutoRotate(false)}
            resetKey={resetKey}
          />

          <div className="stage-controls">
            <button
              className={`ctrl ${autoRotate ? "is-on" : ""}`}
              onClick={() => setAutoRotate((v) => !v)}
              aria-pressed={autoRotate}
            >
              <span className={`ctrl-led ${autoRotate ? "on" : ""}`} />
              Auto-spin
            </button>
            <button
              className="ctrl"
              onClick={() => {
                setResetKey((k) => k + 1);
                setAutoRotate(false);
              }}
            >
              Reset view
            </button>
          </div>

          <div className="legend">
            {ERAS.map((e) => (
              <span key={e.key} className="legend-item">
                <span className="legend-dot" style={{ background: e.color, boxShadow: `0 0 8px ${e.color}` }} />
                {e.label}
              </span>
            ))}
          </div>

          {selected && (
            <article className={`detail ${selected.video ? "is-video" : ""}`}>
              <button className="detail-close" onClick={() => select(null)} aria-label="Close report">
                ×
              </button>
              <div
                className="detail-era"
                style={{ color: selected.video?.shared ? "var(--muted)" : ERA_COLOR[eraOf(selected.y)] }}
              >
                <span
                  className="legend-dot"
                  style={{ background: selected.video?.shared ? "var(--muted)" : ERA_COLOR[eraOf(selected.y)] }}
                />
                {selected.video?.shared
                  ? selected.video.platform === "tiktok"
                    ? "TikTok link"
                    : "YouTube link"
                  : ERA_LABEL[eraOf(selected.y)]}
              </div>
              {!selected.video?.shared && (
                <div className="detail-date">
                  {formatDate(selected.d)}
                  {selected.t ? ` · ${selected.t}` : ""}
                </div>
              )}
              <h2>{selected.video?.shared ? "Shared clip" : selected.video ? selected.city : fullPlace(selected)}</h2>
              {selected.video && !selected.video.shared && (
                <div className="detail-vtitle">{selected.video.title}</div>
              )}
              <div className="detail-meta">
                {selected.video ? (
                  <span>{selected.video.channel}</span>
                ) : (
                  <>
                    <span>{selected.co}</span>
                    <span>{coordLabel(selected.lat, selected.lon)}</span>
                    {selected.shape && selected.shape !== "unknown" && (
                      <span>{SHAPE_LABEL[selected.shape] ?? selected.shape}</span>
                    )}
                    {selected.duration && <span>{selected.duration}</span>}
                  </>
                )}
              </div>
              {selected.video ? (
                <>
                  <div className={`player ${selected.video.platform === "tiktok" ? "vertical" : ""}`}>
                    <iframe
                      src={embedUrl(selected.video)}
                      title={selected.video.title}
                      allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                      allowFullScreen
                      loading="lazy"
                    />
                  </div>
                  <p className="detail-note">{selected.video.note}</p>
                </>
              ) : (
                <p className="detail-summary">{selected.summary}</p>
              )}
              <div className="detail-foot">
                {selected.video ? (
                  <>
                    <span>third-party upload</span>
                    <a
                      className="link"
                      href={
                        selected.video.url ??
                        (selected.video.platform === "tiktok"
                          ? `https://www.tiktok.com/video/${selected.video.id}`
                          : `https://www.youtube.com/watch?v=${selected.video.id}`)
                      }
                      target="_blank"
                      rel="noreferrer"
                    >
                      {selected.video.platform === "tiktok" ? "Open on TikTok" : "Watch on YouTube"}
                    </a>
                  </>
                ) : (
                  <>
                    <span>NUFORC report</span>
                    <span>city-level position</span>
                  </>
                )}
              </div>
            </article>
          )}
        </section>
      </div>
    </div>
  );
}
