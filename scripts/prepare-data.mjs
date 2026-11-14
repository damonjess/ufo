// Builds src/data/sightings.json from two public NUFORC-derived datasets.
//
//   1. planetsig/ufo-reports  — 80k scrubbed NUFORC reports, 1906–2013, already geocoded.
//   2. rfordatascience/tidytuesday (2023-06-20) — NUFORC reports through 2023, no coordinates,
//      so city names are resolved against GeoNames.
//
// Run: node scripts/prepare-data.mjs
import fs from "node:fs";
import path from "node:path";

const CSV_OLD = "/tmp/ufo1.csv";
const CSV_NEW = "/tmp/ufo2.csv";
const GEONAMES = "/tmp/gn/cities5000.txt";
const COUNTRY_INFO = "/tmp/countryInfo.txt";
const OUT = "src/data/sightings.json";

/* ---------- csv ---------- */
function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else quoted = false;
      } else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ",") {
      row.push(field);
      field = "";
    } else if (c === "\n") {
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else if (c !== "\r") field += c;
  }
  if (field.length || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

/* ---------- text helpers ---------- */
const ENTITIES = { "&#44;": ",", "&amp;": "&", "&quot;": '"', "&#39;": "'", "&apos;": "'", "&nbsp;": " " };
const decode = (s) =>
  s.replace(/&#?\w+;/g, (m) => (ENTITIES[m] ?? (m.startsWith("&#") ? " " : "")));

function cleanText(s, max) {
  let t = decode(String(s ?? ""))
    .replace(/\s+/g, " ")
    .replace(/[^\S\r\n]+/g, " ")
    .trim();
  if (max && t.length > max) {
    const cut = t.slice(0, max);
    const stop = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "));
    t = (stop > max * 0.5 ? cut.slice(0, stop + 1) : cut.replace(/[,;\s]+\S*$/, "") + "…").trim();
  }
  return t;
}

const titleCase = (s) =>
  s.replace(/\b[a-z]/g, (m) => m.toUpperCase()).replace(/\bMc([a-z])/g, (_, c) => "Mc" + c.toUpperCase());

const norm = (s) =>
  String(s ?? "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/\(.*?\)/g, " ")
    .replace(/[^a-z0-9 ]/g, " ")
    .replace(/\s+/g, " ")
    .trim();

/* ---------- country names ---------- */
const countryNames = new Map();
for (const line of fs.readFileSync(COUNTRY_INFO, "utf8").split("\n")) {
  if (!line || line.startsWith("#")) continue;
  const f = line.split("\t");
  const iso2 = (f[0] || "").toLowerCase();
  if (!iso2) continue;
  countryNames.set(iso2, { name: f[4], continent: f[8] });
}

/* ---------- gazetteers ---------- */
const gaz = new Map(); // "city|cc" and "city|cc|admin1" -> [lat, lon]
const addGaz = (key, lat, lon) => {
  if (!key || !Number.isFinite(lat) || !Number.isFinite(lon)) return;
  if (!gaz.has(key)) gaz.set(key, [lat, lon]);
};

for (const line of fs.readFileSync(GEONAMES, "utf8").split("\n")) {
  if (!line) continue;
  const f = line.split("\t");
  const name = norm(f[2] || f[1]);
  const lat = parseFloat(f[4]);
  const lon = parseFloat(f[5]);
  const cc = (f[8] || "").toLowerCase();
  const admin1 = (f[10] || "").toLowerCase();
  const pop = parseInt(f[14], 10) || 0;
  if (!name || pop < 5000) continue;
  addGaz(`${name}|${cc}`, lat, lon);
  if (admin1) addGaz(`${name}|${cc}|${admin1}`, lat, lon);
}

/* ---------- countries seen in the archive (also a gazetteer) ---------- */
const oldRows = parseCsv(fs.readFileSync(CSV_OLD, "utf8")).slice(0);
console.log("old rows", oldRows.length);

const archiveGaz = new Map();
const archive = [];
for (const f of oldRows) {
  if (f.length < 11) continue;
  const [dt, city, state, country, shape, , duration, description, , latS, lonS] = f;
  const lat = parseFloat(latS);
  const lon = parseFloat(lonS);
  const cc = (country || "").trim().toLowerCase();
  const cityN = norm(city);
  if (cityN && cc) addGaz(`${cityN}|${cc}|${norm(state)}`, lat, lon);
  if (cityN && cc) addGaz(`${cityN}|${cc}`, lat, lon);
  if (!cityN || !cc || !Number.isFinite(lat) || !Number.isFinite(lon)) continue;
  if (lat === 0 && lon === 0) continue;
  const m = /^(\d{1,2})\/(\d{1,2})\/(\d{4})\s*(\d{1,2}):(\d{2})?/.exec(dt);
  if (!m) continue;
  const [, mo, da, yr, hh, mm] = m;
  const year = +yr;
  if (year < 1900 || year > 2014) continue;
  if (+mo < 1 || +mo > 12 || +da < 1 || +da > 31) continue;
  archive.push({
    iso: `${yr.padStart(4, "0")}-${mo.padStart(2, "0")}-${da.padStart(2, "0")}`,
    year,
    time: mm ? `${hh.padStart(2, "0")}:${mm}` : "",
    city: cleanText(city, 60),
    region: state === cc ? "" : cleanText(state, 40),
    cc,
    shape: norm(shape),
    duration: cleanText(duration, 24),
    summary: cleanText(description, 340),
    lat,
    lon,
    precision: "city",
  });
}
console.log("archive usable", archive.length);

/* ---------- recent rows ---------- */
const newRows = parseCsv(fs.readFileSync(CSV_NEW, "utf8"));
const header = newRows[0];
const col = Object.fromEntries(header.map((h, i) => [h, i]));
const recent = [];
let geoFail = 0;
for (const f of newRows.slice(1)) {
  if (f.length < header.length - 1) continue;
  const dt = f[col.reported_date_time] || "";
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(dt);
  if (!m) continue;
  const year = +m[1];
  if (year < 2014 || year > 2023) continue;
  const cc = (f[col.country_code] || "").toLowerCase();
  const cityN = norm(f[col.city]);
  const stateN = norm(f[col.state]);
  if (!cc) continue;
  let coords = null;
  if (cityN) {
    coords =
      gaz.get(`${cityN}|${cc}|${stateN}`) ||
      gaz.get(`${cityN}|${cc}`) ||
      null;
  }
  if (!coords) {
    geoFail++;
    continue;
  }
  const shape = norm(f[col.shape]);
  const summary = cleanText(f[col.summary], 340);
  if (!summary) continue;
  recent.push({
    iso: `${m[1]}-${m[2]}-${m[3]}`,
    year,
    time: cleanText(dt.slice(11, 16), 5),
    city: cleanText(f[col.city], 60),
    region: titleCase(cleanText(f[col.state], 40)),
    cc,
    shape: shape === "na" ? "" : shape,
    duration: cleanText(f[col.reported_duration], 24),
    summary,
    lat: coords[0],
    lon: coords[1],
    precision: "city",
  });
}
console.log("recent usable", recent.length, "ungeocoded", geoFail);

/* ---------- dedupe ---------- */
function dedupe(list) {
  const seen = new Set();
  const out = [];
  for (const r of list) {
    const k = `${r.iso}|${norm(r.city)}|${r.summary.slice(0, 48)}`;
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(r);
  }
  return out;
}
const pool = dedupe([...archive, ...recent]);
console.log("deduped pool", pool.length);

/* ---------- sampling: keep everything old, thin the crowded modern years ---------- */
let seed = 1337;
const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);

const TARGETS = [
  { from: 1900, to: 1999, n: 4600 },
  { from: 2000, to: 2013, n: 2200 },
  { from: 2014, to: 2023, n: 3400 },
];

// Reports are thinned per country so the globe reads as a world map, not a US map.
// Historical non-US reports (before 1990) are all kept — they are the rarest thing here.
function sampleBalanced(rows, target) {
  const out = [];
  const chosen = new Set();
  const us = rows.filter((r) => r.cc === "us");
  const others = new Map();
  for (const r of rows) {
    if (r.cc === "us") continue;
    if (!others.has(r.cc)) others.set(r.cc, []);
    others.get(r.cc).push(r);
  }

  const takeEvenly = (list, q) => {
    const sorted = [...list].sort((a, b) => a.iso.localeCompare(b.iso));
    const n = Math.min(q, sorted.length);
    const step = sorted.length / n;
    for (let i = 0; i < n; i++) {
      const item = sorted[Math.min(sorted.length - 1, Math.floor(i * step + rnd() * step))];
      if (!chosen.has(item)) {
        chosen.add(item);
        out.push(item);
      }
    }
    return n;
  };

  // United States: at most ~30% of the sampled set, spread across its timeline.
  let usBudget = Math.min(Math.round(target * 0.3), us.length);
  takeEvenly(us, usBudget);
  usBudget = out.length;

  // Everyone else gets the rest, with old reports first and then sqrt-weighted quotas.
  const restBudget = Math.max(0, target - usBudget);
  const allOther = [...others.values()].flat();
  const oldOther = allOther.filter((r) => r.year <= 1989);
  const recentOther = allOther.filter((r) => r.year > 1989);
  const oldTake = Math.min(oldOther.length, Math.round(restBudget * 0.34));
  // thin the old set evenly across countries rather than by year
  const oldSorted = [...oldOther].sort((a, b) => a.cc.localeCompare(b.cc) || a.iso.localeCompare(b.iso));
  const oldStep = oldTake > 0 ? oldSorted.length / oldTake : 0;
  for (let i = 0; i < oldTake; i++) {
    const item = oldSorted[Math.min(oldSorted.length - 1, Math.floor(i * oldStep))];
    if (!chosen.has(item)) {
      chosen.add(item);
      out.push(item);
    }
  }

  let remaining = Math.max(0, target - out.length);
  const weights = [...others.entries()].map(([cc, list]) => [cc, Math.sqrt(list.length)]);
  const totalW = weights.reduce((a, [, w]) => a + w, 0) || 1;
  const cap = Math.max(30, target * 0.16);
  for (const [cc, w] of weights) {
    if (remaining <= 0) break;
    const list = others.get(cc);
    const q = Math.min(cap, Math.max(4, Math.round((w / totalW) * (target * 0.7))));
    takeEvenly(list, q);
    remaining = Math.max(0, target - out.length);
  }

  // Top up any shortfall with the remaining worldwide reports, round-robin by country.
  const fill = (queues) => {
    let i = 0;
    while (out.length < target && queues.some((q) => q.length)) {
      const q = queues[i % queues.length];
      const item = q.shift();
      if (item && !chosen.has(item)) {
        chosen.add(item);
        out.push(item);
      }
      i++;
    }
  };
  fill(
    [...others.values()]
      .map((list) => [...list].sort((a, b) => a.iso.localeCompare(b.iso)).filter((r) => !chosen.has(r)))
      .sort((a, b) => b.length - a.length),
  );
  if (out.length < target) {
    fill([[...us].sort((a, b) => a.iso.localeCompare(b.iso)).filter((r) => !chosen.has(r))]);
  }
  return out;
}

const picked = [];
for (const t of TARGETS) {
  const rows = pool.filter((r) => r.year >= t.from && r.year <= t.to);
  picked.push(...sampleBalanced(rows, Math.min(t.n, rows.length)));
}
// The United Kingdom is deliberately complete: every UK report in both datasets is kept,
// on top of the balanced sample above.
const alreadyPicked = new Set(picked);
const ukExtra = pool.filter((r) => r.cc === "gb" && !alreadyPicked.has(r));
picked.push(...ukExtra);
console.log("UK extras added", ukExtra.length);

picked.sort((a, b) => a.iso.localeCompare(b.iso));

/* ---------- shape normalisation + ids ---------- */
const SHAPE_MAP = {
  "": "unknown",
  na: "unknown",
  unknown: "unknown",
  other: "other",
  light: "light",
  lights: "light",
  circle: "disc",
  disc: "disc",
  disk: "disc",
  round: "disc",
  sphere: "sphere",
  orb: "sphere",
  oval: "oval",
  ellipse: "oval",
  egg: "oval",
  cylinder: "cylinder",
  cigar: "cylinder",
  rectangle: "rectangle",
  rectangular: "rectangle",
  square: "rectangle",
  triangle: "triangle",
  delta: "triangle",
  diamond: "diamond",
  chevron: "chevron",
  boomerang: "chevron",
  cone: "cone",
  cross: "cross",
  teardrop: "teardrop",
  fireball: "fireball",
  flash: "light",
  formation: "formation",
  changed: "changing",
  changing: "changing",
  star: "star",
};
const shapeLabel = {
  unknown: "Unclassified",
  other: "Other",
  light: "Light",
  disc: "Disc",
  sphere: "Sphere / orb",
  oval: "Oval",
  cylinder: "Cylinder",
  rectangle: "Rectangle",
  triangle: "Triangle",
  diamond: "Diamond",
  chevron: "Chevron",
  cone: "Cone",
  cross: "Cross",
  teardrop: "Teardrop",
  fireball: "Fireball",
  formation: "Formation",
  changing: "Changing",
  star: "Star-like",
};

const records = picked.map((r, i) => {
  const shapeKey = SHAPE_MAP[r.shape] ?? (shapeLabel[r.shape] ? r.shape : "other");
  return {
    id: i + 1,
    d: r.iso,
    y: r.year,
    t: r.time || "",
    c: titleCase(r.city),
    r: r.region || "",
    cc: r.cc,
    co: (countryNames.get(r.cc)?.name) || r.cc.toUpperCase(),
    s: shapeKey,
    du: r.duration || "",
    x: r.summary,
    lat: Math.round(r.lat * 1000) / 1000,
    lon: Math.round(r.lon * 1000) / 1000,
  };
});

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(records));

const byCountry = {};
for (const r of records) byCountry[r.co] = (byCountry[r.co] || 0) + 1;
const top = Object.entries(byCountry).sort((a, b) => b[1] - a[1]).slice(0, 12);
console.log("wrote", records.length, "records ->", OUT, (fs.statSync(OUT).size / 1024 / 1024).toFixed(2), "MB");
console.log("countries", Object.keys(byCountry).length);
console.log("top", top.map(([c, n]) => `${c} ${n}`).join(", "));
console.log("year span", records[0].d, "->", records[records.length - 1].d);
console.log("pre-1970", records.filter((r) => r.y < 1970).length, "2015+", records.filter((r) => r.y >= 2015).length);
console.log("shapes", Object.entries(records.reduce((a, r) => ((a[r.s] = (a[r.s] || 0) + 1), a), {})).sort((a, b) => b[1] - a[1]).slice(0, 10));
