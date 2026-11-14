export type VideoClip = {
  n: number;
  videoId: string;
  title: string;
  channel: string;
  place: string;
  lat: number;
  lon: number;
  date: string;
  note: string;
};

export type Sighting = {
  id: number;
  video?: {
    id: string;
    title: string;
    channel: string;
    note: string;
    platform?: "youtube" | "tiktok";
    url?: string;
    shared?: boolean;
  };
  /** ISO date, YYYY-MM-DD */
  d: string;
  y: number;
  /** local time, HH:MM, may be empty */
  t: string;
  city: string;
  region: string;
  cc: string;
  co: string;
  shape: string;
  duration: string;
  summary: string;
  lat: number;
  lon: number;
};

export type RawSighting = {
  id: number;
  d: string;
  y: number;
  t: string;
  c: string;
  r: string;
  cc: string;
  co: string;
  s: string;
  du: string;
  x: string;
  lat: number;
  lon: number;
};

export type EraKey = "old" | "mid" | "modern" | "recent";

export const ERAS: { key: EraKey; label: string; short: string; from: number; to: number; color: string }[] = [
  { key: "old", label: "Before 1970", short: "pre-1970", from: 1900, to: 1969, color: "#ff9f52" },
  { key: "mid", label: "1970 – 1999", short: "1970–99", from: 1970, to: 1999, color: "#f2d276" },
  { key: "modern", label: "2000 – 2014", short: "2000–14", from: 2000, to: 2014, color: "#6fc9f2" },
  { key: "recent", label: "2015 onwards", short: "2015+", from: 2015, to: 2100, color: "#6ee7b7" },
];

export const ERA_COLOR: Record<EraKey, string> = {
  old: "#ff9f52",
  mid: "#f2d276",
  modern: "#6fc9f2",
  recent: "#6ee7b7",
};

export const ERA_LABEL: Record<EraKey, string> = {
  old: "Before 1970",
  mid: "1970–1999",
  modern: "2000–2014",
  recent: "2015 onwards",
};

export function eraOf(year: number): EraKey {
  if (year < 1970) return "old";
  if (year < 2000) return "mid";
  if (year < 2015) return "modern";
  return "recent";
}

export const SHAPE_LABEL: Record<string, string> = {
  unknown: "Unclassified",
  other: "Other",
  light: "Light",
  disc: "Disc",
  sphere: "Sphere",
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

const MONTHS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
];

export function formatDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  if (!y || !m || !d) return iso;
  return `${d} ${MONTHS[m - 1]} ${y}`;
}

export function shortDate(iso: string): { day: string; month: string; year: string } {
  const [y, m, d] = iso.split("-");
  return { day: String(Number(d)), month: MONTHS[Number(m) - 1]?.slice(0, 3) ?? "", year: y };
}

export function coordLabel(lat: number, lon: number): string {
  const ns = lat >= 0 ? "N" : "S";
  const ew = lon >= 0 ? "E" : "W";
  return `${Math.abs(lat).toFixed(2)}° ${ns}, ${Math.abs(lon).toFixed(2)}° ${ew}`;
}

export function placeLabel(s: Sighting): string {
  return s.city || s.region || s.co;
}

/** "Pinehurst, North Carolina, United States" without duplicate segments. */
export function fullPlace(s: Sighting): string {
  const parts = [s.city, s.region, s.co].filter(Boolean);
  const seen = new Set<string>();
  return parts
    .filter((p) => {
      const k = p.toLowerCase();
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    })
    .join(", ");
}

export const ISO_SOURCE = "NUFORC";

export type SocialClip = {
  n: number;
  id: string;
  handle: string;
  caption: string;
  place: string;
  lat: number | null;
  lon: number | null;
  posted: string;
};

export function socialToSighting(v: SocialClip): Sighting {
  return {
    id: 700000 + v.n,
    d: v.posted,
    y: Number(v.posted.slice(0, 4)),
    t: "",
    city: v.place || "Location not given",
    region: "",
    cc: "",
    co: `@${v.handle}`,
    shape: "video",
    duration: "",
    summary: v.caption,
    lat: v.lat ?? Number.NaN,
    lon: v.lon ?? Number.NaN,
    video: {
      id: v.id,
      title: v.caption,
      channel: `@${v.handle}`,
      note: "Caption, place and date are as posted by the uploader — none of it is verified.",
      platform: "tiktok",
      url: `https://www.tiktok.com/@${v.handle}/video/${v.id}`,
    },
  };
}

export function parseClipUrl(raw: string): { platform: "youtube" | "tiktok"; id: string; url: string } | null {
  const input = raw.trim();
  if (!input) return null;
  const yt = /(?:youtube\.com\/(?:watch\?[^\s]*v=|shorts\/|embed\/|live\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/.exec(input);
  if (yt) {
    return { platform: "youtube", id: yt[1], url: `https://www.youtube.com/watch?v=${yt[1]}` };
  }
  const tt = /tiktok\.com\/(?:@([^/?#]+)\/)?(?:video|v)\/(\d{8,25})/.exec(input);
  if (tt) {
    const id = tt[2];
    const handle = tt[1];
    return {
      platform: "tiktok",
      id,
      url: handle ? `https://www.tiktok.com/@${handle}/video/${id}` : `https://www.tiktok.com/video/${id}`,
    };
  }
  // bare TikTok id pasted on its own
  const bare = /^(\d{17,20})$/.exec(input);
  if (bare) return { platform: "tiktok", id: bare[1], url: `https://www.tiktok.com/video/${bare[1]}` };
  return null;
}

export function embedUrl(video: { id: string; platform?: string }): string {
  if (video.platform === "tiktok") {
    return `https://www.tiktok.com/player/v1/${video.id}?embed_from=oembed&music_info=1&description=1`;
  }
  return `https://www.youtube-nocookie.com/embed/${video.id}?rel=0`;
}

export function videoToSighting(v: VideoClip): Sighting {
  return {
    id: 900000 + v.n,
    d: v.date,
    y: Number(v.date.slice(0, 4)),
    t: "",
    city: v.place,
    region: "",
    cc: "",
    co: v.channel,
    shape: "video",
    duration: "",
    summary: v.note,
    lat: v.lat,
    lon: v.lon,
    video: { id: v.videoId, title: v.title, channel: v.channel, note: v.note },
  };
}

export function toSighting(r: RawSighting): Sighting {
  return {
    id: r.id,
    d: r.d,
    y: r.y,
    t: r.t,
    city: r.c,
    region: r.r,
    cc: r.cc,
    co: r.co,
    shape: r.s,
    duration: r.du,
    summary: r.x,
    lat: r.lat,
    lon: r.lon,
  };
}
