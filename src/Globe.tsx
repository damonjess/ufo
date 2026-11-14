import { useEffect, useRef, useState } from "react";
import type * as React from "react";
import { geoOrthographic, geoPath, geoGraticule10, geoDistance } from "d3-geo";
import type { GeoPermissibleObjects } from "d3-geo";
import { feature } from "topojson-client";
import landTopo from "world-atlas/land-50m.json";
import { ERA_COLOR, eraOf, placeLabel, type Sighting } from "./lib/sightings";

const topology = landTopo as unknown as {
  objects: { land: unknown };
};
const LAND = feature(topology as never, topology.objects.land as never) as unknown as GeoPermissibleObjects;
const GRATICULE = geoGraticule10();

type Props = {
  sightings: Sighting[];
  selected: Sighting | null;
  onSelect: (s: Sighting | null) => void;
  autoRotate: boolean;
  onUserRotate: () => void;
  resetKey: number;
};

type Pin = { x: number; y: number; s: Sighting };

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

const MIN_ZOOM = 0.85;
const MAX_ZOOM = 42;

function hexToRgb(hex: string) {
  const h = hex.replace("#", "");
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

function makeSprite(hex: string) {
  const size = 26;
  const c = document.createElement("canvas");
  c.width = c.height = size;
  const g = c.getContext("2d")!;
  const [r, gr, b] = hexToRgb(hex);
  const glow = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  glow.addColorStop(0, `rgba(${r},${gr},${b},0.55)`);
  glow.addColorStop(0.28, `rgba(${r},${gr},${b},0.28)`);
  glow.addColorStop(0.6, `rgba(${r},${gr},${b},0.09)`);
  glow.addColorStop(1, `rgba(${r},${gr},${b},0)`);
  g.fillStyle = glow;
  g.fillRect(0, 0, size, size);
  g.beginPath();
  g.arc(size / 2, size / 2, 2.4, 0, Math.PI * 2);
  g.fillStyle = `rgba(${r},${gr},${b},0.95)`;
  g.fill();
  g.beginPath();
  g.arc(size / 2, size / 2, 1.05, 0, Math.PI * 2);
  g.fillStyle = "rgba(255,255,255,0.9)";
  g.fill();
  return c;
}

function shortestDelta(from: number, to: number) {
  return ((to - from + 540) % 360) - 180;
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r);
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
}

export default function Globe({ sightings, selected, onSelect, autoRotate, onUserRotate, resetKey }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const tipRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<Sighting | null>(null);
  const hoverRef = useRef<Sighting | null>(null);
  const view = useRef({
    lam: -18,
    phi: -14,
    zoom: 1,
    drag: null as null | { x: number; y: number; moved: boolean },
    target: null as null | { lam: number; phi: number; t0: number },
    dirty: true,
    pins: [] as Pin[],
  });
  const sizeRef = useRef({ w: 0, h: 0, dpr: 1 });
  const pointersRef = useRef(new Map<number, { x: number; y: number }>());
  const pinchRef = useRef<null | { dist: number; zoom: number; mx: number; my: number }>(null);
  const pinchUsedRef = useRef(false);
  const starsRef = useRef<{ x: number; y: number; r: number; a: number }[]>([]);
  const spritesRef = useRef<Map<string, HTMLCanvasElement>>(new Map());

  const list = sightings;

  /* ---------- sprites ---------- */
  if (spritesRef.current.size === 0) {
    for (const key of ["old", "mid", "modern", "recent"] as const) {
      spritesRef.current.set(key, makeSprite(ERA_COLOR[key]));
    }
    spritesRef.current.set("video", makeSprite("#fff8e7"));
  }

  /* ---------- size / stars ---------- */
  useEffect(() => {
    const wrap = wrapRef.current;
    const canvas = canvasRef.current;
    if (!wrap || !canvas) return;
    const resize = () => {
      const rect = wrap.getBoundingClientRect();
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = Math.max(200, Math.round(rect.width));
      const h = Math.max(200, Math.round(rect.height));
      sizeRef.current = { w, h, dpr };
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      canvas.style.width = `${w}px`;
      canvas.style.height = `${h}px`;
      let seed = 7;
      const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
      starsRef.current = Array.from({ length: 190 }, () => ({
        x: rnd() * w,
        y: rnd() * h,
        r: rnd() < 0.86 ? 0.6 : 1.2,
        a: 0.18 + rnd() * 0.5,
      }));
      view.current.dirty = true;
    };
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(wrap);
    return () => ro.disconnect();
  }, []);

  /* ---------- selection / reset ---------- */
  useEffect(() => {
    if (!selected) return;
    view.current.target = { lam: -selected.lon, phi: clamp(-selected.lat, -72, 72), t0: performance.now() };
    view.current.zoom = Math.max(view.current.zoom, 1.25);
  }, [selected]);

  useEffect(() => {
    view.current.target = { lam: -18, phi: -14, t0: performance.now() };
    view.current.zoom = 1;
  }, [resetKey]);

  /* ---------- render loop ---------- */
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    view.current.dirty = true;
    let raf = 0;
    let last = performance.now();

    const draw = () => {
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      const { w, h, dpr } = sizeRef.current;
      const v = view.current;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, h);

      // starfield
      for (const st of starsRef.current) {
        ctx.globalAlpha = st.a;
        ctx.fillStyle = "#cfe8ef";
        ctx.fillRect(st.x, st.y, st.r, st.r);
      }
      ctx.globalAlpha = 1;

      const R = (Math.min(w, h) / 2) * 0.84 * v.zoom;
      const cx = w / 2;
      const cy = h / 2;
      const projection = geoOrthographic()
        .translate([cx, cy])
        .scale(R)
        .rotate([v.lam, v.phi, 0])
        .clipAngle(90);
      const path = geoPath(projection, ctx);

      // outer atmosphere
      const atmo = ctx.createRadialGradient(cx, cy, R * 0.94, cx, cy, R * 1.22);
      atmo.addColorStop(0, "rgba(110,231,183,0.16)");
      atmo.addColorStop(0.55, "rgba(88,175,205,0.07)");
      atmo.addColorStop(1, "rgba(88,175,205,0)");
      ctx.fillStyle = atmo;
      ctx.beginPath();
      ctx.arc(cx, cy, R * 1.24, 0, Math.PI * 2);
      ctx.fill();

      // ocean
      const ocean = ctx.createRadialGradient(cx - R * 0.35, cy - R * 0.4, R * 0.1, cx, cy, R * 1.04);
      ocean.addColorStop(0, "#123039");
      ocean.addColorStop(0.45, "#0b1f27");
      ocean.addColorStop(1, "#05121a");
      ctx.beginPath();
      path({ type: "Sphere" });
      ctx.fillStyle = ocean;
      ctx.fill();

      // land
      ctx.beginPath();
      path(LAND);
      ctx.fillStyle = "#1b3a40";
      ctx.fill();
      ctx.lineWidth = 0.7;
      ctx.strokeStyle = "rgba(146,216,224,0.42)";
      ctx.stroke();

      // graticule
      ctx.beginPath();
      path(GRATICULE);
      ctx.lineWidth = 0.5;
      ctx.strokeStyle = "rgba(160,215,230,0.13)";
      ctx.stroke();

      // rim
      ctx.beginPath();
      path({ type: "Sphere" });
      ctx.lineWidth = 1;
      ctx.strokeStyle = "rgba(150,226,240,0.4)";
      ctx.stroke();

      // pins
      const center: [number, number] = [-v.lam, -v.phi];
      const pins: Pin[] = [];
      // constant on-screen size: pins mark cities, so they should not balloon when zooming in
      const spriteSize = Math.min(w, h) * 0.022;
      for (const s of list) {
        if (geoDistance([s.lon, s.lat], center) > Math.PI / 2 - 0.015) continue;
        const p = projection([s.lon, s.lat]);
        if (!p) continue;
        pins.push({ x: p[0], y: p[1], s });
        const sprite = spritesRef.current.get(s.video ? "video" : eraOf(s.y));
        if (sprite) {
          ctx.globalAlpha = 0.9;
          ctx.drawImage(sprite, p[0] - spriteSize / 2, p[1] - spriteSize / 2, spriteSize, spriteSize);
        }
      }
      // video reports sit on top with a ring so they read differently from eyewitness pins
      for (const p of pins) {
        if (!p.s.video) continue;
        ctx.beginPath();
        ctx.arc(p.x, p.y, spriteSize * 0.62, 0, Math.PI * 2);
        ctx.strokeStyle = "rgba(255,248,231,0.8)";
        ctx.lineWidth = 1.2;
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
      v.pins = pins;

      // hovered pin
      if (hoverRef.current) {
        const p = projection([hoverRef.current.lon, hoverRef.current.lat]);
        if (p && geoDistance([hoverRef.current.lon, hoverRef.current.lat], center) < Math.PI / 2 - 0.015) {
          ctx.beginPath();
          ctx.arc(p[0], p[1], 7, 0, Math.PI * 2);
          ctx.strokeStyle = "rgba(232,250,246,0.85)";
          ctx.lineWidth = 1.1;
          ctx.stroke();
        }
      }

      // selected pin
      if (selected) {
        const p = projection([selected.lon, selected.lat]);
        const visible = geoDistance([selected.lon, selected.lat], center) < Math.PI / 2 - 0.015;
        if (p && visible) {
          const color = selected.video ? "#fff8e7" : ERA_COLOR[eraOf(selected.y)];
          const pulse = 1 + Math.sin(performance.now() / 420) * 0.12;
          ctx.beginPath();
          ctx.arc(p[0], p[1], 11 * pulse, 0, Math.PI * 2);
          ctx.strokeStyle = color;
          ctx.lineWidth = 1.4;
          ctx.stroke();
          ctx.beginPath();
          ctx.arc(p[0], p[1], 3.2, 0, Math.PI * 2);
          ctx.fillStyle = "#ffffff";
          ctx.fill();

          const label = placeLabel(selected);
          ctx.font = "600 11px ui-monospace, SFMono-Regular, Menlo, monospace";
          const tw = ctx.measureText(label).width;
          const bx = clamp(p[0] - tw / 2 - 8, 6, w - tw - 22);
          const by = p[1] - 30;
          ctx.fillStyle = "rgba(5,13,17,0.85)";
          roundRect(ctx, bx, by, tw + 16, 22, 6);
          ctx.fill();
          ctx.strokeStyle = "rgba(150,226,240,0.28)";
          ctx.lineWidth = 1;
          ctx.stroke();
          ctx.fillStyle = "#e9f6f4";
          ctx.textBaseline = "middle";
          ctx.fillText(label, bx + 8, by + 11.5);
        }
      }
    };

    const tick = (now: number) => {
      const v = view.current;
      const dt = Math.min(64, now - last);
      last = now;
      let moving = false;

      if (v.target) {
        const t = clamp((now - v.target.t0) / 620, 0, 1);
        const e = 1 - Math.pow(1 - t, 3);
        const dl = shortestDelta(v.lam, v.target.lam);
        const fromLam = v.lam;
        const fromPhi = v.phi;
        v.lam = fromLam + dl * e;
        v.phi = fromPhi + (v.target.phi - fromPhi) * e;
        if (t >= 1) {
          v.lam = ((v.target.lam % 360) + 540) % 360 - 180;
          v.phi = v.target.phi;
          v.target = null;
        }
        moving = true;
      } else if (autoRotate && !v.drag) {
        v.lam += dt * 0.0032;
        moving = true;
      }

      if (moving || v.dirty) {
        draw();
        v.dirty = false;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [list, selected, autoRotate]);

  /* ---------- pointer ---------- */
  const nearest = (mx: number, my: number, maxDist: number): Pin | null => {
    let best: Pin | null = null;
    let bestD = maxDist * maxDist;
    for (const p of view.current.pins) {
      const dx = p.x - mx;
      const dy = p.y - my;
      const d = dx * dx + dy * dy;
      if (d < bestD) {
        bestD = d;
        best = p;
      }
    }
    return best;
  };

  const localPoint = (e: { clientX: number; clientY: number }) => {
    const rect = canvasRef.current!.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };

  const projectionAt = (zoom: number) => {
    const { w, h } = sizeRef.current;
    return geoOrthographic()
      .translate([w / 2, h / 2])
      .scale((Math.min(w, h) / 2) * 0.84 * zoom)
      .rotate([view.current.lam, view.current.phi, 0])
      .clipAngle(90);
  };

  /** Zoom while keeping the point under (mx, my) pinned — i.e. zoom toward the cursor. */
  const zoomToward = (next: number, mx: number, my: number) => {
    const v = view.current;
    const zoom = clamp(next, MIN_ZOOM, MAX_ZOOM);
    if (Math.abs(zoom - v.zoom) < 1e-4) return;

    const invert = projectionAt(v.zoom).invert;
    const target = invert ? invert([mx, my]) : null;
    v.zoom = zoom;
    if (!target || !Number.isFinite(target[0]) || !Number.isFinite(target[1])) {
      v.dirty = true;
      return;
    }

    const R = (Math.min(sizeRef.current.w, sizeRef.current.h) / 2) * 0.84 * zoom;
    const degPerPx = 180 / (Math.PI * R);
    // a few Newton-ish steps are enough to bring the anchor back under the cursor
    for (let i = 0; i < 6; i++) {
      const p = projectionAt(zoom)(target);
      if (!p) break;
      const dx = mx - p[0];
      const dy = my - p[1];
      if (Math.abs(dx) < 0.4 && Math.abs(dy) < 0.4) break;
      v.lam += dx * degPerPx;
      v.phi = clamp(v.phi - dy * degPerPx, -85, 85);
    }
    v.dirty = true;
  };

  const onPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const v = view.current;
    const p = localPoint(e);
    pointersRef.current.set(e.pointerId, p);
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      /* ignore */
    }
    onUserRotate();

    // Two fingers down: pinch to zoom, drag the midpoint to pan.
    if (pointersRef.current.size === 2) {
      const [a, b] = [...pointersRef.current.values()];
      pinchRef.current = {
        dist: Math.hypot(a.x - b.x, a.y - b.y) || 1,
        zoom: v.zoom,
        mx: (a.x + b.x) / 2,
        my: (a.y + b.y) / 2,
      };
      pinchUsedRef.current = true;
      v.drag = null;
      if (hoverRef.current) {
        hoverRef.current = null;
        setHover(null);
        if (tipRef.current) tipRef.current.style.opacity = "0";
      }
      return;
    }
    if (pointersRef.current.size === 1) pinchUsedRef.current = false;
    v.drag = { x: p.x, y: p.y, moved: false };
  };

  const onPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const v = view.current;
    const p = localPoint(e);
    if (pointersRef.current.has(e.pointerId)) pointersRef.current.set(e.pointerId, p);

    const pinch = pinchRef.current;
    if (pinch && pointersRef.current.size >= 2) {
      const [a, b] = [...pointersRef.current.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y) || 1;
      const mx = (a.x + b.x) / 2;
      const my = (a.y + b.y) / 2;
      const sens = 0.24 / v.zoom;
      v.lam += (mx - pinch.mx) * sens;
      v.phi = clamp(v.phi - (my - pinch.my) * sens, -85, 85);
      pinch.mx = mx;
      pinch.my = my;
      zoomToward(pinch.zoom * (dist / pinch.dist), mx, my);
      v.dirty = true;
      return;
    }

    if (v.drag) {
      const dx = p.x - v.drag.x;
      const dy = p.y - v.drag.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) v.drag.moved = true;
      if (v.drag.moved) {
        const sens = 0.24 / v.zoom;
        v.lam += dx * sens;
        v.phi = clamp(v.phi - dy * sens, -85, 85);
        v.dirty = true;
        if (hoverRef.current) {
          hoverRef.current = null;
          setHover(null);
          if (tipRef.current) tipRef.current.style.opacity = "0";
        }
      }
      v.drag = { x: p.x, y: p.y, moved: v.drag.moved };
      return;
    }
    const hit = nearest(p.x, p.y, 14);
    const s = hit?.s ?? null;
    if (s !== hoverRef.current) {
      hoverRef.current = s;
      setHover(s);
      if (canvasRef.current) canvasRef.current.style.cursor = s ? "pointer" : "grab";
      v.dirty = true;
    }
    const tip = tipRef.current;
    if (tip) {
      if (s) {
        tip.style.opacity = "1";
        tip.style.transform = `translate(${Math.round(p.x)}px, ${Math.round(p.y)}px)`;
      } else {
        tip.style.opacity = "0";
      }
    }
  };

  const endPointer = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const v = view.current;
    pointersRef.current.delete(e.pointerId);
    if (pointersRef.current.size < 2) pinchRef.current = null;
    const wasDrag = v.drag?.moved;
    v.drag = null;
    return { wasDrag, wasPinch: pinchUsedRef.current };
  };

  const onPointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const { wasDrag, wasPinch } = endPointer(e);
    if (wasDrag || wasPinch) return;
    const p = localPoint(e);
    const hit = nearest(p.x, p.y, 14);
    onSelect(hit ? hit.s : null);
  };

  const onPointerCancel = (e: React.PointerEvent<HTMLCanvasElement>) => {
    endPointer(e);
  };

  const onWheel = (e: React.WheelEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const p = localPoint(e);
    zoomToward(view.current.zoom * Math.exp(-e.deltaY * 0.0022), p.x, p.y);
    onUserRotate();
  };

  return (
    <div className="globe" ref={wrapRef}>
      <canvas
        ref={canvasRef}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerCancel}
        onPointerLeave={() => {
          view.current.drag = null;
          pointersRef.current.clear();
          pinchRef.current = null;
          if (hoverRef.current) {
            hoverRef.current = null;
            setHover(null);
            if (tipRef.current) tipRef.current.style.opacity = "0";
            view.current.dirty = true;
          }
        }}
        onWheel={onWheel}
      />
      <div className="globe-tip" ref={tipRef} aria-hidden={hover ? "false" : "true"}>
        {hover && (
          <>
            <span className="globe-tip-date">{hover.d.slice(0, 4)}</span>
            <span className="globe-tip-place">{placeLabel(hover)}</span>
            <span className="globe-tip-country">{hover.co}</span>
          </>
        )}
      </div>
    </div>
  );
}
