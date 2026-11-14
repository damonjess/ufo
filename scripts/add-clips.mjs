// Bulk-import TikTok / YouTube links into src/data/social.json (TikTok) or src/data/videos.json (YouTube).
//
// Usage:
//   node scripts/add-clips.mjs clips.txt
//
// One link per line. Optional annotations after a pipe:
//   https://www.tiktok.com/@user/video/123 | Cardiff, Wales | 51.48,-3.18
//   https://youtu.be/abc123 | Some Case | 51.48,-3.18 | Video title here
//
// Lines are skipped when the link is already in the archive, so re-running is safe.
// Nothing here talks to TikTok or YouTube: it only structures links you supply.
import fs from "node:fs";
import path from "node:path";

const SOCIAL = "src/data/social.json";
const VIDEOS = "src/data/videos.json";

const file = process.argv[2];
if (!file) {
  console.error("Give me a file of links: node scripts/add-clips.mjs clips.txt");
  process.exit(1);
}

const social = JSON.parse(fs.readFileSync(SOCIAL, "utf8"));
const videos = JSON.parse(fs.readFileSync(VIDEOS, "utf8"));
const known = new Set([
  ...social.map((v) => v.id),
  ...videos.map((v) => v.videoId),
]);

let nextSocial = Math.max(0, ...social.map((v) => v.n)) + 1;
let nextVideo = Math.max(0, ...videos.map((v) => v.n)) + 1;

const tiktokTs = (id) => {
  try {
    return new Date(Number(BigInt(id) >> 32n) * 1000).toISOString().slice(0, 10);
  } catch {
    return new Date().toISOString().slice(0, 10);
  }
};

const lines = fs.readFileSync(file, "utf8").split("\n");
const added = { tiktok: 0, youtube: 0 };
const skipped = [];
const failed = [];

for (const raw of lines) {
  const line = raw.trim();
  if (!line || line.startsWith("#")) continue;

  const [linkPart, placePart, coordPart, titlePart] = line.split("|").map((s) => s.trim());
  const place = placePart || "";
  let lat = null;
  let lon = null;
  if (coordPart) {
    const m = /(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)/.exec(coordPart);
    if (m) {
      lat = Number(m[1]);
      lon = Number(m[2]);
    }
  }

  const tt = /tiktok\.com\/(?:@([^/?#]+)\/)?(?:video|v)\/(\d{8,25})/.exec(linkPart);
  if (tt) {
    const id = tt[2];
    if (known.has(id)) {
      skipped.push(id);
      continue;
    }
    const handle = tt[1] || "unknown";
    social.push({
      n: nextSocial++,
      id,
      handle,
      caption: titlePart || place ? place || "TikTok clip" : "TikTok clip",
      place,
      lat,
      lon,
      posted: tiktokTs(id),
    });
    known.add(id);
    added.tiktok++;
    continue;
  }

  const yt = /(?:youtube\.com\/(?:watch\?[^\s]*v=|shorts\/|embed\/|live\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/.exec(linkPart);
  if (yt) {
    const id = yt[1];
    if (known.has(id)) {
      skipped.push(id);
      continue;
    }
    videos.push({
      n: nextVideo++,
      videoId: id,
      title: titlePart || place || "Shared clip",
      channel: "YouTube",
      place: place || "Location not given",
      lat: lat ?? 0,
      lon: lon ?? 0,
      date: new Date().toISOString().slice(0, 10),
      note: "Added from a supplied link. Details are unverified.",
    });
    known.add(id);
    added.youtube++;
    continue;
  }

  failed.push(line.slice(0, 80));
}

fs.writeFileSync(SOCIAL, JSON.stringify(social, null, 2) + "\n");
fs.writeFileSync(VIDEOS, JSON.stringify(videos, null, 2) + "\n");

console.log(`added ${added.tiktok} TikTok + ${added.youtube} YouTube`);
if (skipped.length) console.log(`skipped ${skipped.length} already in the archive`);
if (failed.length) {
  console.log(`could not parse ${failed.length}:`);
  for (const f of failed) console.log("  " + f);
}
console.log("now:", social.length, "TikTok posts,", videos.length, "YouTube clips");
