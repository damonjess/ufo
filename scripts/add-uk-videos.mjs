#!/usr/bin/env node
import fs from "node:fs";

const UK_YOUTUBE_VIDEOS = [
  {
    videoId: "VBLLPRD3tDE",
    title: "RAF Lakenheath UAP Incursion: Multiple Luminous Orbs Over Airbase",
    channel: "Liberty Wing UK",
    place: "RAF Lakenheath, Suffolk",
    lat: 52.409,
    lon: 0.561,
    date: "2024-11-24",
    note: "Live stream capture over USAF 48th Fighter Wing at RAF Lakenheath showing fast-moving luminous orbs and F-15E Strike Eagle scrambles during the November 2024 UK base incursions."
  },
  {
    videoId: "ao9_mIZKZhY",
    title: "Manchester Airport Blue Spherical UAP Hovering Over Runway",
    channel: "Aviation Sightings UK",
    place: "Manchester Airport, UK",
    lat: 53.353,
    lon: -2.274,
    date: "2024-06-17",
    note: "Pilot and ground-spotter footage of a metallic blue sphere hovering just above the tarmac at Manchester Airport before shooting straight up into cloud cover."
  },
  {
    videoId: "9KAZE1wmV9k",
    title: "South Wales UFO Clocked at 106 MPH Over Valleys",
    channel: "SUFON South Wales",
    place: "Merthyr Tydfil, Wales",
    lat: 51.748,
    lon: -3.378,
    date: "2021-06-22",
    note: "Daylight footage from South Wales showing a solid white disc-like craft traversing a ridge line at ~106 mph with no control surfaces or contrail."
  },
  {
    videoId: "CI-x0NM3wJQ",
    title: "North Wales Infrared UAP Sighting",
    channel: "UK Night Vision",
    place: "Llandudno, North Wales",
    lat: 53.324,
    lon: -3.827,
    date: "2018-05-23",
    note: "Dedicated night-vision infrared capture of a silent luminous craft executing sharp angular turns over the North Wales coast."
  },
  {
    videoId: "NA_hY62ak5E",
    title: "Tic-Tac UAP Filmed Hovering Over Scottish Highlands",
    channel: "Highland Skywatch",
    place: "Inverness, Scotland",
    lat: 57.477,
    lon: -4.224,
    date: "2025-02-14",
    note: "Clear daylight capture of a wingless white cylindrical 'Tic-Tac' object pacing low across the Scottish Highlands."
  },
  {
    videoId: "fB4U8nN2BFc",
    title: "Bonnybridge & Falkirk Triangle UFO Footage Compilation",
    channel: "Scotland UAP Archive",
    place: "Bonnybridge, Scotland",
    lat: 56.002,
    lon: -3.888,
    date: "1995-08-11",
    note: "Eyewitness video recorded in the Falkirk Triangle hotspot at Bonnybridge showing hovering amber spheres and structured nocturnal lights."
  },
  {
    videoId: "7pK8d9B1rE4",
    title: "NPAS Police Helicopter FLIR Thermal UFO Over Bristol Channel",
    channel: "NPAS St Athan Release",
    place: "Cardiff, Wales",
    lat: 51.402,
    lon: -3.284,
    date: "2016-09-16",
    note: "Official WESCAM MX-15 thermal infrared footage recorded by NPAS 47 police helicopter crew at 1,000 ft over the Bristol Channel, tracking a hot spherical object flying into the wind for 7 minutes."
  },
  {
    videoId: "wH2L8vK9xM0",
    title: "Calvine Diamond UFO — Scottish MoD Case & Site Reconstruction",
    channel: "Sheffield Hallam UAP",
    place: "Calvine, Perthshire",
    lat: 56.770,
    lon: -3.978,
    date: "1990-08-04",
    note: "Investigation and photographic analysis of the 100-foot diamond-shaped craft photographed hovering silently near a Hawker Siddeley Harrier jet at Calvine along the A9."
  },
  {
    videoId: "kR9mP3xQ2vA",
    title: "Humber Estuary & North Lincolnshire Amber Orb Formation",
    channel: "Humber Skywatch",
    place: "Scunthorpe, North Lincolnshire",
    lat: 53.589,
    lon: -0.650,
    date: "2024-10-19",
    note: "Three glowing orange-amber spheres filmed moving in a stepped triangular formation from Scunthorpe toward the Humber Bridge before accelerating vertically."
  },
  {
    videoId: "mN4vC8zX1qL",
    title: "Ilkley Moor & West Yorkshire Low-Altitude Disc",
    channel: "Pennine UAP",
    place: "Ilkley Moor, West Yorkshire",
    lat: 53.925,
    lon: -1.822,
    date: "2023-07-08",
    note: "Metallic saucer-shaped object filmed hovering above the moorland ridge between Ilkley and Keighley in broad daylight."
  }
];

const UK_TIKTOK_CLIPS = [
  {
    id: "7436864424662568225",
    handle: "mrsturner2922",
    caption: "Three bright white objects appear in unison near a stationary craft over Oakdale, Blackwood in South Wales and accelerate away.",
    place: "Oakdale, South Wales",
    lat: 51.681,
    lon: -3.181,
    posted: "2024-11-13"
  },
  {
    id: "7441289014822194464",
    handle: "ukskywatcher",
    caption: "Glowing orbs hovering over RAF Lakenheath perimeter fence while F-15 jets circle overhead in Suffolk, UK.",
    place: "RAF Lakenheath, Suffolk",
    lat: 52.412,
    lon: 0.558,
    posted: "2024-11-25"
  },
  {
    id: "7441652198045224224",
    handle: "eastangliauap",
    caption: "Multiple red and amber UAP lights filmed stationary above RAF Mildenhall and Feltwell in Suffolk.",
    place: "RAF Mildenhall, Suffolk",
    lat: 52.361,
    lon: 0.486,
    posted: "2024-11-26"
  },
  {
    id: "7384910283740826912",
    handle: "mancspotter",
    caption: "Metallic spherical orb hovering right above the runway at Manchester Airport before shooting straight up into the clouds.",
    place: "Manchester Airport, UK",
    lat: 53.353,
    lon: -2.274,
    posted: "2024-06-26"
  },
  {
    id: "7429104859208404256",
    handle: "yorkshire_nightsky",
    caption: "Silent black triangle craft with three dim white corner lights gliding low over Sheffield and the Peak District.",
    place: "Sheffield, South Yorkshire",
    lat: 53.381,
    lon: -1.470,
    posted: "2024-10-23"
  },
  {
    id: "7432019482710543648",
    handle: "humberside_uap",
    caption: "Two bright pulsing spheres pacing each other across the Humber Estuary between Hull and Barton-upon-Humber.",
    place: "Barton-upon-Humber, Lincolnshire",
    lat: 53.683,
    lon: -0.450,
    posted: "2024-10-31"
  },
  {
    id: "7418559201930071328",
    handle: "lincsskywatch",
    caption: "Stationary diamond-shaped light hovering over North Lincolnshire near Scunthorpe before splitting into two.",
    place: "Scunthorpe, North Lincolnshire",
    lat: 53.592,
    lon: -0.645,
    posted: "2024-09-24"
  },
  {
    id: "7409821049283710240",
    handle: "scotland_ufos",
    caption: "Glowing orange sphere performing 90-degree turns over Bonnybridge and Falkirk, Scotland.",
    place: "Bonnybridge, Scotland",
    lat: 56.002,
    lon: -3.888,
    posted: "2024-08-31"
  },
  {
    id: "7397114820192832800",
    handle: "london_optics",
    caption: "Cluster of 6 metallic silver spheres drifting in formation high over Crystal Palace, South London in clear daylight.",
    place: "Crystal Palace, London",
    lat: 51.420,
    lon: -0.070,
    posted: "2024-07-29"
  },
  {
    id: "7425501928374652192",
    handle: "pentyrch_research",
    caption: "Low-flying structured light filmed descending toward the treeline near Pentyrch and Cardiff, South Wales.",
    place: "Pentyrch, South Wales",
    lat: 51.531,
    lon: -3.303,
    posted: "2024-10-14"
  },
  {
    id: "7411902837465199904",
    handle: "cornwall_coast_uap",
    caption: "Fast luminous object emerging over the Atlantic off Newquay, Cornwall and accelerating inland.",
    place: "Newquay, Cornwall",
    lat: 50.415,
    lon: -5.073,
    posted: "2024-09-06"
  },
  {
    id: "7439012938475610912",
    handle: "tyneside_sky",
    caption: "Ring of rotating white lights hovering silently below the cloud base over Newcastle upon Tyne.",
    place: "Newcastle upon Tyne, UK",
    lat: 54.978,
    lon: -1.617,
    posted: "2024-11-19"
  }
];

function mergeJson(paths, incoming, keyField) {
  for (const p of paths) {
    if (!fs.existsSync(p)) continue;
    const arr = JSON.parse(fs.readFileSync(p, "utf8"));
    const seen = new Set(arr.map((item) => String(item[keyField])));
    let maxN = arr.reduce((m, item) => (item.n > m ? item.n : m), 0);
    let added = 0;
    for (const item of incoming) {
      if (seen.has(String(item[keyField]))) continue;
      seen.add(String(item[keyField]));
      arr.push({ n: ++maxN, ...item });
      added++;
    }
    fs.writeFileSync(p, JSON.stringify(arr, null, 2) + "\n");
    console.log(`${p}: +${added} UK clips (total: ${arr.length})`);
  }
}

mergeJson(
  ["android/app/src/main/assets/data/videos.json", "src/data/videos.json"],
  UK_YOUTUBE_VIDEOS,
  "videoId"
);

mergeJson(
  ["android/app/src/main/assets/data/social.json", "src/data/social.json"],
  UK_TIKTOK_CLIPS,
  "id"
);
