// Discovers and enriches UFO/UAP TikTok clips using public search + TikTok's page hydration
// metadata & oEmbed endpoint, geocodes locations (with a built-in UK gazetteer + OpenStreetMap
// Nominatim), and inserts them into both src/data/social.json and
// android/app/src/main/assets/data/social.json.
//
// Usage:
//   node scripts/fetch-tiktok-ufo.mjs                        # Discover UK & global UFO TikTok clips + insert
//   node scripts/fetch-tiktok-ufo.mjs --dry-run              # Preview without modifying files
//   node scripts/fetch-tiktok-ufo.mjs --query "ufo scotland" # Custom search query
//   node scripts/fetch-tiktok-ufo.mjs https://www.tiktok.com/@user/video/1234567890123456789
//   node scripts/fetch-tiktok-ufo.mjs --file clips.txt
import fs from "node:fs";

const SOCIAL_PATHS = [
  "src/data/social.json",
  "android/app/src/main/assets/data/social.json",
];

const USER_AGENT =
  "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

const DEFAULT_QUERIES = [
  'site:tiktok.com "video" ufo uk',
  'site:tiktok.com "video" ufo london OR manchester OR scotland',
  'site:tiktok.com "video" uap lakenheath OR england OR wales',
  'site:tiktok.com "video" "ufo sighting" uk OR britain',
];

const UK_GAZETTEER = new Map([
  ["crystal palace", ["Crystal Palace, London", 51.42, -0.07]],
  ["london", ["London, UK", 51.51, -0.13]],
  ["manchester airport", ["Manchester Airport, UK", 53.35, -2.27]],
  ["manchester", ["Manchester, UK", 53.48, -2.24]],
  ["birmingham", ["Birmingham, UK", 52.49, -1.90]],
  ["leeds", ["Leeds, UK", 53.80, -1.55]],
  ["glasgow", ["Glasgow, Scotland", 55.86, -4.25]],
  ["edinburgh", ["Edinburgh, Scotland", 55.95, -3.19]],
  ["liverpool", ["Liverpool, UK", 53.41, -2.99]],
  ["bristol", ["Bristol, UK", 51.45, -2.59]],
  ["sheffield", ["Sheffield, UK", 53.38, -1.47]],
  ["cardiff", ["Cardiff, Wales", 51.48, -3.18]],
  ["belfast", ["Belfast, Northern Ireland", 54.60, -5.93]],
  ["newcastle", ["Newcastle, UK", 54.98, -1.61]],
  ["nottingham", ["Nottingham, UK", 52.95, -1.15]],
  ["cornwall", ["Cornwall, UK", 50.27, -5.05]],
  ["devon", ["Devon, UK", 50.72, -3.53]],
  ["yorkshire", ["Yorkshire, UK", 53.96, -1.08]],
  ["york", ["York, UK", 53.96, -1.08]],
  ["lakenheath", ["RAF Lakenheath, Suffolk", 52.41, 0.56]],
  ["mildenhall", ["RAF Mildenhall, Suffolk", 52.36, 0.49]],
  ["rendlesham", ["Rendlesham, Suffolk", 52.09, 1.43]],
  ["suffolk", ["Suffolk, UK", 52.19, 0.99]],
  ["norfolk", ["Norfolk, UK", 52.63, 1.30]],
  ["essex", ["Essex, UK", 51.73, 0.47]],
  ["kent", ["Kent, UK", 51.28, 0.52]],
  ["surrey", ["Surrey, UK", 51.31, -0.56]],
  ["sussex", ["Sussex, UK", 50.91, -0.46]],
  ["brighton", ["Brighton, UK", 50.82, -0.14]],
  ["hampshire", ["Hampshire, UK", 51.06, -1.31]],
  ["dorset", ["Dorset, UK", 50.75, -2.34]],
  ["somerset", ["Somerset, UK", 51.10, -2.93]],
  ["glastonbury", ["Glastonbury, UK", 51.15, -2.71]],
  ["wiltshire", ["Wiltshire, UK", 51.35, -1.99]],
  ["stonehenge", ["Stonehenge, Wiltshire", 51.18, -1.83]],
  ["oxford", ["Oxford, UK", 51.75, -1.26]],
  ["cambridge", ["Cambridge, UK", 52.21, 0.12]],
  ["peak district", ["Peak District, UK", 53.35, -1.78]],
  ["lake district", ["Lake District, UK", 54.46, -3.09]],
  ["lancashire", ["Lancashire, UK", 53.76, -2.70]],
  ["blackpool", ["Blackpool, UK", 53.82, -3.05]],
  ["aberdeen", ["Aberdeen, Scotland", 57.15, -2.09]],
  ["inverness", ["Inverness, Scotland", 57.48, -4.22]],
  ["calvine", ["Calvine, Scotland", 56.77, -3.85]],
  ["bonnybridge", ["Bonnybridge, Scotland", 56.00, -3.88]],
  ["falkirk", ["Falkirk, Scotland", 56.00, -3.78]],
  ["scotland", ["Scottish Highlands, UK", 56.49, -4.20]],
  ["swansea", ["Swansea, Wales", 51.62, -3.94]],
  ["pontypool", ["Pontypool, Wales", 51.70, -3.04]],
  ["snowdonia", ["Snowdonia, Wales", 52.90, -3.85]],
  ["wales", ["Wales, UK", 52.13, -3.78]],
  ["england", ["England, UK", 52.80, -1.50]],
  ["british", ["United Kingdom", 53.10, -1.80]],
  ["britain", ["United Kingdom", 53.50, -2.20]],
  ["united kingdom", ["United Kingdom", 53.50, -2.20]],
]);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Extract YYYY-MM-DD creation date from a 64-bit TikTok Snowflake ID. */
function tiktokTs(id) {
  try {
    const ms = Number(BigInt(id) >> 32n) * 1000;
    const d = new Date(ms);
    if (d.getUTCFullYear() >= 2016 && d.getUTCFullYear() <= 2035) {
      return d.toISOString().slice(0, 10);
    }
  } catch {
    // ignore
  }
  return new Date().toISOString().slice(0, 10);
}

/** Follow shortlinks (vm.tiktok.com, vt.tiktok.com, tiktok.com/t/...) to canonical video URLs. */
async function resolveTikTokUrl(rawUrl) {
  const direct = /tiktok\.com\/(?:@([^/?#]+)\/)?(?:video|v)\/(\d{15,25})/.exec(rawUrl);
  if (direct) {
    return {
      handle: direct[1] || null,
      id: direct[2],
      url: `https://www.tiktok.com/@${direct[1] || "video"}/video/${direct[2]}`,
    };
  }
  if (!/(?:vm\.tiktok\.com|vt\.tiktok\.com|tiktok\.com\/t\/)/.test(rawUrl)) {
    return null;
  }
  try {
    const res = await fetch(rawUrl, {
      method: "HEAD",
      redirect: "follow",
      headers: { "User-Agent": USER_AGENT },
    });
    const m = /tiktok\.com\/(?:@([^/?#]+)\/)?(?:video|v)\/(\d{15,25})/.exec(res.url);
    if (m) {
      return {
        handle: m[1] || null,
        id: m[2],
        url: `https://www.tiktok.com/@${m[1] || "video"}/video/${m[2]}`,
      };
    }
  } catch {
    // ignore redirect failure
  }
  return null;
}

/** Decode JSON string escapes like \u0026 or \" */
function decodeJsonString(raw) {
  if (!raw) return "";
  try {
    return JSON.parse(`"${raw}"`);
  } catch {
    return raw;
  }
}

/** Extract caption, author handle, POI name, and country code from TikTok page + oEmbed. */
async function fetchTikTokMetadata(videoUrl) {
  let handle = null;
  let rawDesc = "";
  let poiName = "";
  let locationCreated = "";

  try {
    const res = await fetch(videoUrl, {
      headers: { "User-Agent": USER_AGENT, Accept: "text/html" },
    });
    if (res.ok) {
      const html = await res.text();
      const descMatch = html.match(/"desc"\s*:\s*"((?:\\.|[^"\\])*)"/);
      const authorMatch = html.match(/"uniqueId"\s*:\s*"([^"]+)"/);
      const poiMatch = html.match(/"poi"\s*:\s*\{[^}]*"name"\s*:\s*"((?:\\.|[^"\\])*)"/);
      const locMatch = html.match(/"locationCreated"\s*:\s*"([A-Z]{2})"/);
      if (descMatch) rawDesc = decodeJsonString(descMatch[1]);
      if (authorMatch) handle = authorMatch[1];
      if (poiMatch) poiName = decodeJsonString(poiMatch[1]);
      if (locMatch) locationCreated = locMatch[1];
    }
  } catch {
    // fallback to oembed below
  }

  if (!rawDesc || !handle) {
    try {
      const endpoint = `https://www.tiktok.com/oembed?url=${encodeURIComponent(videoUrl)}`;
      const res = await fetch(endpoint, {
        headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
      });
      if (res.ok) {
        const data = await res.json();
        if (!handle) handle = data.author_unique_id || data.author_name || null;
        if (!rawDesc && data.title) rawDesc = data.title;
      }
    } catch {
      // ignore
    }
  }

  return {
    handle,
    rawDesc,
    caption: cleanCaption(rawDesc),
    poiName,
    locationCreated,
  };
}

/** Strip trailing hashtag blocks and normalize whitespace. */
function cleanCaption(raw) {
  if (!raw) return "";
  let text = String(raw)
    .replace(/\s+/g, " ")
    .replace(/(?:\s+#[^\s#]+)+\s*$/g, "")
    .replace(/#[^\s#]+/g, "")
    .replace(/\s+/g, " ")
    .trim();
  if (text.length > 220) {
    text = text.slice(0, 217).replace(/[,;\s]+\S*$/, "") + "…";
  }
  return text;
}

/** Match against UK gazetteer first, then general caption patterns. */
function inferKnownLocation(rawDesc = "", poiName = "", locationCreated = "") {
  const combined = `${poiName} ${rawDesc}`.toLowerCase();
  for (const [key, [label, lat, lon]] of UK_GAZETTEER.entries()) {
    if (combined.includes(key)) {
      return { place: label, lat, lon };
    }
  }
  if (locationCreated === "GB" || /\b(?:uk|u\.k\.|britain|british|🇬🇧)\b/i.test(combined)) {
    return { place: "United Kingdom", lat: 53.5, lon: -2.2 };
  }
  return null;
}

/** Extract a candidate place name from a caption or post title (e.g. "over Phoenix, Arizona"). */
function extractPlaceCandidate(text) {
  if (!text) return "";
  const patterns = [
    /\b(?:over|above|in|near|outside|filmed in|spotted in|seen in|from)\s+([A-Z][A-Za-z .'-]{1,28}(?:,\s*[A-Z][A-Za-z .'-]{1,24})?)/,
  ];
  const stopWords = new Set([
    "The", "My", "Our", "This", "That", "Night", "Sky", "Daylight", "Broad",
    "TikTok", "Flight", "Plane", "Window", "Clouds", "Space", "Real", "Video",
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
  ]);
  for (const re of patterns) {
    const m = re.exec(text);
    if (m && m[1]) {
      const candidate = m[1]
        .replace(/\b(?:on|at|last|yesterday|today|around|during)\b.*$/i, "")
        .replace(/[.,;:!?]+$/, "")
        .trim();
      const firstWord = candidate.split(/\s+/)[0];
      if (candidate.length >= 3 && !stopWords.has(firstWord)) {
        return candidate;
      }
    }
  }
  return "";
}

const geocodeCache = new Map();

/** Geocode a place string into { lat, lon } via OpenStreetMap Nominatim. */
async function geocodePlace(place) {
  if (!place) return null;
  const key = place.toLowerCase().trim();
  if (geocodeCache.has(key)) return geocodeCache.get(key);

  await sleep(1100);
  const url = `https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encodeURIComponent(place)}`;
  try {
    const res = await fetch(url, {
      headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
    });
    if (!res.ok) {
      geocodeCache.set(key, null);
      return null;
    }
    const rows = await res.json();
    if (!Array.isArray(rows) || rows.length === 0) {
      geocodeCache.set(key, null);
      return null;
    }
    const lat = Number(parseFloat(rows[0].lat).toFixed(2));
    const lon = Number(parseFloat(rows[0].lon).toFixed(2));
    const result = Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
    geocodeCache.set(key, result);
    return result;
  } catch {
    geocodeCache.set(key, null);
    return null;
  }
}

/** Discover public TikTok UFO video URLs via DuckDuckGo HTML search. */
async function discoverFromWebSearch(queries) {
  const found = new Map();
  for (const q of queries) {
    const endpoint = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(q)}`;
    try {
      const res = await fetch(endpoint, {
        headers: { "User-Agent": USER_AGENT, Accept: "text/html" },
      });
      if (!res.ok) continue;
      const html = await res.text();
      const decoded = html
        .replace(/%2F/gi, "/")
        .replace(/%40/gi, "@")
        .replace(/%3A/gi, ":");
      const matches = decoded.matchAll(
        /https?:\/\/(?:www\.)?tiktok\.com\/@([^/?"&\s]+)\/video\/(\d{15,25})/g,
      );
      for (const m of matches) {
        const url = m[0];
        if (!found.has(url)) {
          found.set(url, {
            rawUrl: url,
            fallbackTitle: "",
            place: "",
            lat: null,
            lon: null,
            fromSearch: true,
          });
        }
      }
      await sleep(500);
    } catch (err) {
      console.warn(`Warning: search failed for "${q}": ${err.message}`);
    }
  }
  return [...found.values()];
}

/** Parse CLI arguments. */
function parseArgs(argv) {
  const opts = {
    dryRun: false,
    queries: [],
    file: null,
    urls: [],
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--dry-run" || arg === "-n") {
      opts.dryRun = true;
    } else if (arg === "--query" || arg === "-q") {
      const q = argv[++i];
      if (q) opts.queries.push(`site:tiktok.com "video" ${q}`);
    } else if (arg === "--file" || arg === "-f") {
      opts.file = argv[++i] || null;
    } else if (/^https?:\/\//i.test(arg)) {
      opts.urls.push(arg);
    } else if (!arg.startsWith("-") && fs.existsSync(arg)) {
      opts.file = arg;
    }
  }
  return opts;
}

/** Parse a pipe-delimited file (compatible with scripts/add-clips.mjs). */
function readInputFile(filePath) {
  const items = [];
  const lines = fs.readFileSync(filePath, "utf8").split("\n");
  for (const raw of lines) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const [linkPart, placePart, coordPart, titlePart] = line.split("|").map((s) => s.trim());
    let lat = null;
    let lon = null;
    if (coordPart) {
      const m = /(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)/.exec(coordPart);
      if (m) {
        lat = Number(m[1]);
        lon = Number(m[2]);
      }
    }
    items.push({
      rawUrl: linkPart,
      fallbackTitle: titlePart || "",
      place: placePart || "",
      lat,
      lon,
      fromSearch: false,
    });
  }
  return items;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const primaryPath = SOCIAL_PATHS[0];
  const social = JSON.parse(fs.readFileSync(primaryPath, "utf8"));
  const knownIds = new Set(social.map((v) => String(v.id)));
  let nextN = Math.max(0, ...social.map((v) => Number(v.n) || 0)) + 1;

  let candidates = [];
  if (opts.file) {
    console.log(`Reading TikTok links from ${opts.file}...`);
    candidates.push(...readInputFile(opts.file));
  }
  if (opts.urls.length > 0) {
    candidates.push(
      ...opts.urls.map((u) => ({
        rawUrl: u,
        fallbackTitle: "",
        place: "",
        lat: null,
        lon: null,
        fromSearch: false,
      })),
    );
  }
  if (candidates.length === 0) {
    const queries = opts.queries.length > 0 ? opts.queries : DEFAULT_QUERIES;
    console.log(`Searching for UFO TikTok clips (${queries.length} query/queries)...`);
    candidates = await discoverFromWebSearch(queries);
    console.log(`Found ${candidates.length} candidate TikTok link(s).`);
  }

  const added = [];
  let skippedKnown = 0;

  for (const item of candidates) {
    const resolved = await resolveTikTokUrl(item.rawUrl);
    if (!resolved) continue;

    if (knownIds.has(resolved.id)) {
      skippedKnown++;
      continue;
    }

    const meta = await fetchTikTokMetadata(resolved.url);
    const combinedText = `${meta.rawDesc} ${resolved.handle || ""} ${item.fallbackTitle}`;
    if (
      item.fromSearch &&
      !/\b(?:ufo|uap|ufos|uaps|orb|orbs|alien|aliens|sighting|sky|lights|craft|object)\b/i.test(combinedText)
    ) {
      continue;
    }

    knownIds.add(resolved.id);
    const handle = meta.handle || resolved.handle || "unknown";
    const knownLoc = inferKnownLocation(meta.rawDesc, meta.poiName, meta.locationCreated);
    let place =
      knownLoc?.place ||
      item.place ||
      meta.poiName ||
      extractPlaceCandidate(meta.caption) ||
      "";
    let lat = knownLoc?.lat ?? item.lat;
    let lon = knownLoc?.lon ?? item.lon;

    if (place && (lat === null || lon === null)) {
      const coords = await geocodePlace(place);
      if (coords) {
        lat = coords.lat;
        lon = coords.lon;
      }
    }

    const caption =
      meta.caption ||
      cleanCaption(item.fallbackTitle) ||
      (place ? `UFO/UAP sighting clip reported in ${place}.` : `UFO/UAP clip posted by @${handle}.`);

    const entry = {
      n: nextN++,
      id: resolved.id,
      handle: handle.replace(/^@/, ""),
      caption,
      place: place || "",
      lat: lat ?? null,
      lon: lon ?? null,
      posted: tiktokTs(resolved.id),
    };

    social.push(entry);
    added.push(entry);
    console.log(
      `  + [@${entry.handle}] ${entry.id} (${entry.posted})` +
        (entry.place ? ` — ${entry.place} [${entry.lat}, ${entry.lon}]` : "") +
        `\n    "${entry.caption}"`,
    );
  }

  if (added.length === 0) {
    console.log(`No new clips added (${skippedKnown} already in archive).`);
    return;
  }

  if (opts.dryRun) {
    console.log(`\nDry run complete: would add ${added.length} clip(s).`);
    return;
  }

  const serialized = JSON.stringify(social, null, 2) + "\n";
  for (const target of SOCIAL_PATHS) {
    if (fs.existsSync(target)) {
      fs.writeFileSync(target, serialized);
      console.log(`Updated ${target} (${social.length} total TikTok clips)`);
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
