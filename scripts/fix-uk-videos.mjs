#!/usr/bin/env node
import fs from "node:fs";

const UA = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

const UK_GAZETTEER = [
  ["scunthorpe", "Scunthorpe, North Lincolnshire", 53.589, -0.650],
  ["barton", "Barton-upon-Humber, Lincolnshire", 53.683, -0.450],
  ["grimsby", "Grimsby, Lincolnshire", 53.567, -0.080],
  ["hull", "Hull, East Yorkshire", 53.745, -0.336],
  ["humber", "Humber Estuary, UK", 53.650, -0.300],
  ["doncaster", "Doncaster, South Yorkshire", 53.522, -1.131],
  ["sheffield", "Sheffield, South Yorkshire", 53.381, -1.470],
  ["leeds", "Leeds, West Yorkshire", 53.800, -1.549],
  ["bradford", "Bradford, West Yorkshire", 53.795, -1.759],
  ["ilkley", "Ilkley Moor, West Yorkshire", 53.925, -1.822],
  ["todmorden", "Todmorden, West Yorkshire", 53.713, -2.096],
  ["yorkshire", "Yorkshire, UK", 53.959, -1.081],
  ["york", "York, North Yorkshire", 53.959, -1.081],
  ["lincolnshire", "Lincolnshire, UK", 53.230, -0.540],
  ["lincoln", "Lincoln, Lincolnshire", 53.230, -0.540],
  ["lakenheath", "RAF Lakenheath, Suffolk", 52.409, 0.561],
  ["mildenhall", "RAF Mildenhall, Suffolk", 52.361, 0.486],
  ["rendlesham", "Rendlesham Forest, Suffolk", 52.092, 1.442],
  ["suffolk", "Suffolk, UK", 52.187, 0.970],
  ["manchester airport", "Manchester Airport, UK", 53.353, -2.274],
  ["manchester", "Manchester, UK", 53.480, -2.242],
  ["liverpool", "Liverpool, UK", 53.408, -2.991],
  ["birmingham", "Birmingham, UK", 52.486, -1.890],
  ["nottingham", "Nottingham, UK", 52.954, -1.158],
  ["newcastle", "Newcastle upon Tyne, UK", 54.978, -1.617],
  ["london", "London, UK", 51.507, -0.127],
  ["essex", "Essex, UK", 51.734, 0.468],
  ["kent", "Kent, UK", 51.278, 0.521],
  ["brighton", "Brighton, Sussex", 50.822, -0.137],
  ["bristol", "Bristol, UK", 51.454, -2.587],
  ["glastonbury", "Glastonbury, Somerset", 51.149, -2.714],
  ["wiltshire", "Wiltshire, UK", 51.349, -1.992],
  ["cornwall", "Cornwall, UK", 50.266, -5.052],
  ["devon", "Devon, UK", 50.718, -3.533],
  ["bonnybridge", "Bonnybridge, Scotland", 56.002, -3.888],
  ["falkirk", "Falkirk, Scotland", 56.001, -3.783],
  ["calvine", "Calvine, Scotland", 56.770, -3.978],
  ["edinburgh", "Edinburgh, Scotland", 55.953, -3.188],
  ["glasgow", "Glasgow, Scotland", 55.864, -4.251],
  ["scotland", "Scotland, UK", 56.490, -4.202],
  ["pentyrch", "Pentyrch, South Wales", 51.531, -3.303],
  ["blackwood", "Blackwood, South Wales", 51.668, -3.195],
  ["oakdale", "Oakdale, South Wales", 51.681, -3.181],
  ["cardiff", "Cardiff, Wales", 51.481, -3.179],
  ["swansea", "Swansea, Wales", 51.621, -3.943],
  ["wales", "Wales, UK", 52.130, -3.783],
  ["belfast", "Belfast, Northern Ireland", 54.597, -5.930],
  ["england", "England, UK", 52.800, -1.500],
  ["uk", "United Kingdom", 53.500, -2.200],
  ["britain", "United Kingdom", 53.500, -2.200],
];

async function checkYoutubeOembed(videoId) {
  const url = `https://www.youtube.com/oembed?url=${encodeURIComponent(`https://www.youtube.com/watch?v=${videoId}`)}&format=json`;
  try {
    const res = await fetch(url, { headers: { "User-Agent": UA } });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

async function checkTikTokOembed(handle, clipId) {
  const videoUrl = `https://www.tiktok.com/@${handle}/video/${clipId}`;
  const url = `https://www.tiktok.com/oembed?url=${encodeURIComponent(videoUrl)}`;
  try {
    const res = await fetch(url, { headers: { "User-Agent": UA } });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

function inferUkPlace(text) {
  const lower = text.toLowerCase();
  for (const [key, label, lat, lon] of UK_GAZETTEER) {
    if (lower.includes(key)) return { place: label, lat, lon };
  }
  return { place: "United Kingdom", lat: 53.5, lon: -2.2 };
}

function snowflakeDate(id) {
  try {
    const ms = Number(BigInt(id) >> 32n) * 1000;
    return new Date(ms).toISOString().slice(0, 10);
  } catch {
    return "2024-11-01";
  }
}

async function discoverUkTikToks(existingIds) {
  const queries = [
    'site:tiktok.com "video" ufo uk',
    'site:tiktok.com "video" ufo sighting england OR scotland OR wales',
    'site:tiktok.com "video" ufo london OR manchester OR yorkshire',
    'site:tiktok.com "video" uap lakenheath OR mildenhall',
  ];
  const candidates = new Map();
  const regex = /tiktok\.com\/@([^/?#"&\s]+)\/video\/(\d{15,25})/gi;

  for (const q of queries) {
    try {
      const res = await fetch(`https://html.duckduckgo.com/html/?q=${encodeURIComponent(q)}`, {
        headers: { "User-Agent": UA },
      });
      if (!res.ok) continue;
      const html = (await res.text())
        .replace(/%2F/gi, "/")
        .replace(/%40/gi, "@")
        .replace(/%3A/gi, ":");
      for (const m of html.matchAll(regex)) {
        const [, handle, id] = m;
        if (!existingIds.has(id)) candidates.set(id, handle);
      }
    } catch {}
  }

  const verified = [];
  for (const [id, handle] of candidates) {
    const meta = await checkTikTokOembed(handle, id);
    if (!meta || !meta.title) continue;
    const cleanTitle = meta.title.replace(/#[^\s#]+/g, "").replace(/\s+/g, " ").trim();
    const loc = inferUkPlace(`${meta.title} ${handle}`);
    verified.push({
      id,
      handle: meta.author_unique_id || handle,
      caption: cleanTitle || `UK UFO clip posted by @${handle}`,
      place: loc.place,
      lat: loc.lat,
      lon: loc.lon,
      posted: snowflakeDate(id),
    });
  }
  return verified;
}

async function cleanAndUpdateVideos(paths) {
  const primary = paths.find((p) => fs.existsSync(p));
  if (!primary) return;
  const raw = JSON.parse(fs.readFileSync(primary, "utf8"));
  const valid = [];

  for (const item of raw) {
    const ok = await checkYoutubeOembed(item.videoId);
    if (ok) {
      valid.push({ ...item, n: valid.length + 1 });
    } else {
      console.log(`Removed dead YouTube videoId: ${item.videoId} (${item.place})`);
    }
  }

  for (const p of paths) {
    if (fs.existsSync(p)) fs.writeFileSync(p, JSON.stringify(valid, null, 2) + "\n");
  }
  console.log(`videos.json: ${valid.length} verified playable YouTube videos.`);
}

async function cleanAndUpdateSocial(paths) {
  const primary = paths.find((p) => fs.existsSync(p));
  if (!primary) return;
  const raw = JSON.parse(fs.readFileSync(primary, "utf8"));
  const valid = [];
  const seen = new Set();

  for (const item of raw) {
    const ok = await checkTikTokOembed(item.handle, item.id);
    if (ok) {
      seen.add(String(item.id));
      valid.push({ ...item, n: valid.length + 1 });
    } else {
      console.log(`Removed dead TikTok id: ${item.id} (@${item.handle} - ${item.place})`);
    }
  }

  const discovered = await discoverUkTikToks(seen);
  for (const clip of discovered) {
    if (seen.has(clip.id)) continue;
    seen.add(clip.id);
    valid.push({ n: valid.length + 1, ...clip });
    console.log(`Added verified UK TikTok: ${clip.id} (@${clip.handle} - ${clip.place})`);
  }

  for (const p of paths) {
    if (fs.existsSync(p)) fs.writeFileSync(p, JSON.stringify(valid, null, 2) + "\n");
  }
  console.log(`social.json: ${valid.length} verified playable TikTok clips.`);
}

await cleanAndUpdateVideos(["android/app/src/main/assets/data/videos.json", "src/data/videos.json"]);
await cleanAndUpdateSocial(["android/app/src/main/assets/data/social.json", "src/data/social.json"]);
