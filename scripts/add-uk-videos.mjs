#!/usr/bin/env node
import fs from "node:fs";

// Verified additions: every YouTube ID resolves through YouTube oEmbed and every
// TikTok ID resolves through the public TikTok extractor. Location fields are
// only populated where the source clip itself supplies them.
const UK_YOUTUBE_VIDEOS = [
  {
    "videoId": "VBLLPRD3tDE",
    "title": "USAF UK Bases Flooded With Sightings: What’s REALLY Happening?",
    "channel": "Geeksvana",
    "place": "RAF Lakenheath, Suffolk",
    "lat": 52.409,
    "lon": 0.561,
    "date": "2024-11-24",
    "note": "Geeksvana examines reports of UAP sightings around USAF bases in the UK, including RAF Lakenheath and RAF Mildenhall, during the November 2024 drone/UAP flap. The claims remain unverified."
  },
  {
    "videoId": "ao9_mIZKZhY",
    "title": "UFO SPOTTED at Manchester Airport! What's Going On?",
    "channel": "Explore With Ant",
    "place": "Manchester Airport, UK",
    "lat": 53.353,
    "lon": -2.274,
    "date": "2024-06-17",
    "note": "Commentary on a viral report of a spherical object near Manchester Airport. The footage and its interpretation are unverified."
  },
  {
    "videoId": "9KAZE1wmV9k",
    "title": "UFO caught on camera 'travelling 106mph' in Wales as UK gov urged to come clean",
    "channel": "The Sun",
    "place": "Merthyr Tydfil, Wales",
    "lat": 51.748,
    "lon": -3.378,
    "date": "2021-06-22",
    "note": "The Sun reports on footage said to show an object travelling at 106 mph over South Wales, amid calls for the UK government to release UAP data. The object's identity is unresolved."
  },
  {
    "videoId": "CI-x0NM3wJQ",
    "title": "UFO sighting over Wales, UK 2018. video taken on an infra red camera.",
    "channel": "Sad Fantasy",
    "place": "Llandudno, North Wales",
    "lat": 53.324,
    "lon": -3.827,
    "date": "2018-05-23",
    "note": "Infrared camera footage said to show a luminous object over North Wales in 2018. The clip is presented as a UAP; no independent identification is available."
  },
  {
    "videoId": "NA_hY62ak5E",
    "title": "WATCH: Tic Tac UFO seen Hovering Over Scotland! #ufo #shorts",
    "channel": "Orbit - Beyond the Blue",
    "place": "Inverness, Scotland",
    "lat": 57.477,
    "lon": -4.224,
    "date": "2025-02-14",
    "note": "Short clip described as a Tic Tac-shaped object over the Scottish Highlands. The footage is unverified."
  },
  {
    "videoId": "T7jkqsCa4-I",
    "title": "The Town with the Most UFO Sightings in the World",
    "channel": "Jordi Busquets",
    "place": "Bonnybridge, Scotland",
    "lat": 56.002,
    "lon": -3.888,
    "date": "2023-01-29",
    "note": "Short documentary visit to Bonnybridge, the Scottish town at the centre of the Falkirk Triangle and decades of UFO reports. Includes local accounts and case history; claims are unverified."
  },
  {
    "videoId": "Yd25HLhAVuU",
    "title": "Police helicopter captures UFO",
    "channel": "SWNS",
    "place": "Bristol Channel, Wales",
    "lat": 51.402,
    "lon": -3.284,
    "date": "2016-09-27",
    "note": "South Wales Police helicopter thermal footage filmed 1,000 ft over the Bristol Channel, showing a bright spherical object moving against the wind. The object remains unidentified."
  },
  {
    "videoId": "0mQ1kGk2A88",
    "title": "The story of the Calvine UFO photograph",
    "channel": "BBC News",
    "place": "Calvine, Perthshire",
    "lat": 56.77,
    "lon": -3.978,
    "date": "1990-08-04",
    "note": "BBC retrospective on the 1990 Calvine photograph, the unpublished image, the MoD records and the search for the original negatives. The case remains unexplained."
  },
  {
    "videoId": "gEbYr7AL8BI",
    "title": "The Bempton Mystery: Something Is Happening On The Yorkshire Coast",
    "channel": "Project Blue World",
    "place": "Bempton, East Yorkshire",
    "lat": 54.149,
    "lon": -0.173,
    "date": "2020-09-14",
    "note": "Investigation of strange lights and unexplained activity around Bempton and the Yorkshire coast, including witness accounts. Presented as an unresolved mystery; no conclusion is established."
  },
  {
    "videoId": "bPhJbyen7ks",
    "title": "FIRST SOLO WILDCAMP UK - Ilkley Moor the UK UFO Hotspot!",
    "channel": "BOTG Adventures",
    "place": "Ilkley Moor, West Yorkshire",
    "lat": 53.925,
    "lon": -1.822,
    "date": "2019-09-10",
    "note": "Outdoor filmmaker wild-camps on Ilkley Moor and explores the area's reputation as a UFO hotspot, including the 1987 Ilkley Moor alien photograph case. This is a location feature, not verified UAP footage."
  }
];

const UK_TIKTOK_CLIPS = [
  {
    "id": "7436864424662568225",
    "handle": "mrsturner2922",
    "caption": "@sarahleighxx i found them! 25th August 2023 these videos are from 🛸👽 in day light!",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2024-11-13"
  },
  {
    "id": "7683623438296042785",
    "handle": "mcflavauk",
    "caption": "Saw about 20 UFO/UAP orbs flying around Hemel Hempstead at about 6pm by the M1 junction. Anyone else see them?",
    "place": "Hemel Hempstead, England",
    "lat": 51.752,
    "lon": -0.457,
    "posted": "2026-09-09"
  },
  {
    "id": "7699569020957248781",
    "handle": "newsnationnow",
    "caption": "NewsNation examines reports of drones over RAF Lakenheath and nearby bases in November 2024, including claims from witnesses who fly drones.",
    "place": "RAF Lakenheath, Suffolk",
    "lat": 52.409,
    "lon": 0.561,
    "posted": "2026-10-22"
  },
  {
    "id": "7690215666690821406",
    "handle": "foxnews",
    "caption": "BREAKING: Five men are nabbed near a UK airbase used by U.S. forces; police arrest the suspects on suspicion of preparing a terrorist attack.",
    "place": "United Kingdom",
    "lat": null,
    "lon": null,
    "posted": "2026-09-27"
  },
  {
    "id": "7692069151673748742",
    "handle": "alexandernamee",
    "caption": "REAL UFO Sightings 2026 | They Aren’t Waiting Anymore | Real UFO footage caught on camera.",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2026-10-02"
  },
  {
    "id": "7701292135512952077",
    "handle": "stargazeroracle",
    "caption": "Orb being followed by a helicopter - 2026. #orb #UFO #uap",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2026-10-27"
  },
  {
    "id": "7615008823425666317",
    "handle": "uaps2025",
    "caption": "UAP in the sky; the uploader asks for help identifying what was recorded on 7 July 2023.",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2026-03-08"
  },
  {
    "id": "7569514451162942751",
    "handle": "stormseer5",
    "caption": "When a plane and random flying objects cross paths. #plane #ufo #uap #sighting",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2025-11-06"
  },
  {
    "id": "7643394458297453846",
    "handle": "dwnews",
    "caption": "The Pentagon released 51 new UFO videos - DW News asks what they actually show.",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2026-05-24"
  },
  {
    "id": "7650951213978012941",
    "handle": "scienceinyoruba",
    "caption": "A 2025 video showing a clear view of two UFOs.",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2026-06-13"
  },
  {
    "id": "7431959266208042273",
    "handle": "steven",
    "caption": "Ex-Pentagon official explains how UFOs are real and why he thinks that.",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2024-10-31"
  },
  {
    "id": "7446134154976087329",
    "handle": "extraordinaryphenomenon",
    "caption": "Chris Bales and Bob Sinker describe a silent, football-field-sized UFO sighting. #UFO #UAP #Missing411",
    "place": "Location not given",
    "lat": null,
    "lon": null,
    "posted": "2024-12-08"
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
