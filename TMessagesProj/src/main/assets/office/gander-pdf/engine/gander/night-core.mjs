/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent. Gander (MIT); see the accompanying Gander license. */
export const SOURCE_SHA256 = 'cb16c869e1baf901230b7603802353e9841f60a994c2e72c47b172dda9c2ace4';
export const CLASSIFIER_SHA256 = '9839f27669079f6152c817650e7bf830aaec394032a8f76e5fbc1069e5dccdd5';
export const CORE_SHA256 = 'c21a9a4e19208eb96c3217d15c4a187c1e341ee6c10fd47f481109deff211896';
var PROBE_MAX_PIXELS = 65536;
var PROBE_MAX_SIDE = 512;
var PROBE_MIN_SAMPLES = 32;
var probeCanvas = null;

function packedSample(ctx, quads, unionScale, unionArea) {
  if (quads.length < 2 || quads.length > 64 || unionScale >= 1) return null;
  var rects = [];
  for (var q of quads) {
    if (!((q[0] === q[2] && q[3] === q[5]) || (q[1] === q[3] && q[2] === q[4]))) continue;
    var left = Math.min(q[0], q[2], q[4], q[6]), top = Math.min(q[1], q[3], q[5], q[7]);
    var right = Math.max(q[0], q[2], q[4], q[6]), bottom = Math.max(q[1], q[3], q[5], q[7]);
    if (left < 0 || top < 0 || right > ctx.canvas.width || bottom > ctx.canvas.height) continue;
    var width = right - left, height = bottom - top;
    if (width > 0 && height > 0) rects.push({q, left, top, width, height});
  }
  var occupied = rects.reduce((sum, rect) => sum + rect.width * rect.height, 0);
  if (rects.length < 2 || occupied * 2 >= unionArea) return null;
  rects.sort((a, b) => b.height - a.height);
  function layout(scale, side) {
    var x = 0, y = 0, row = 0, width = 0, placed = [];
    for (var r of rects) {
      var w = Math.max(1, Math.ceil(r.width * scale)), h = Math.max(1, Math.ceil(r.height * scale));
      if (w > side || h > PROBE_MAX_SIDE) return null;
      if (x + w > side) { y += row; x = row = 0; }
      if ((y + h) * Math.max(width, x + w) > PROBE_MAX_PIXELS || y + h > PROBE_MAX_SIDE) return null;
      placed.push({r, x, y, w, h}); x += w; row = Math.max(row, h); width = Math.max(width, x);
    }
    var height = y + row;
    return width * height <= PROBE_MAX_PIXELS ? {width, height, placed, scale} : null;
  }
  var best = null;
  for (var side of [128, 256, 512]) {
    var low = 0, high = 1;
    for (var iteration = 0; iteration < 8; iteration++) {
      var scale = (low + high) / 2, plan = layout(scale, side);
      if (plan) { low = scale; if (!best || scale > best.scale) best = plan; } else high = scale;
    }
  }
  if (!best || best.scale <= unionScale * 1.1) return null;
  if (!probeCanvas) probeCanvas = typeof document === 'object' ? document.createElement('canvas') : new OffscreenCanvas(1, 1);
  probeCanvas.width = best.width; probeCanvas.height = best.height;
  var p = probeCanvas.getContext('2d', {willReadFrequently: true});
  p.imageSmoothingEnabled = false; p.clearRect(0, 0, best.width, best.height);
  var regions = new Map();
  for (var box of best.placed) {
    var r = box.r;
    p.drawImage(ctx.canvas, r.left, r.top, r.width, r.height, box.x, box.y, box.w, box.h);
    regions.set(r.q, {x: box.x, y: box.y, w: box.w, h: box.h});
  }
  return {w: best.width, h: best.height, data: p.getImageData(0, 0, best.width, best.height).data,
    sourceW: ctx.canvas.width, sourceH: ctx.canvas.height, remaining: PROBE_MAX_PIXELS * 4, regions};
}

function pageSample(ctx, quads) {
  if (!quads.length) return null;
  var x0 = ctx.canvas.width, y0 = ctx.canvas.height, x1 = 0, y1 = 0;
  quads.forEach(function (q) {
    for (var i = 0; i < 8; i += 2) {
      x0 = Math.min(x0, q[i]); y0 = Math.min(y0, q[i + 1]);
      x1 = Math.max(x1, q[i]); y1 = Math.max(y1, q[i + 1]);
    }
  });
  x0 = Math.max(0, x0); y0 = Math.max(0, y0);
  x1 = Math.min(ctx.canvas.width, x1); y1 = Math.min(ctx.canvas.height, y1);
  var cw = x1 - x0, ch = y1 - y0;
  if (!(cw > 0 && ch > 0)) return null;
  var k = Math.min(1, Math.sqrt(PROBE_MAX_PIXELS / (cw * ch)), PROBE_MAX_SIDE / cw, PROBE_MAX_SIDE / ch);
  var packed = packedSample(ctx, quads, k, cw * ch);
  if (packed) return packed;
  var w = Math.max(1, Math.floor(cw * k)), h = Math.max(1, Math.floor(ch * k));
  if (!probeCanvas) probeCanvas = typeof document === "object" ? document.createElement("canvas") : new OffscreenCanvas(1, 1);
  if (probeCanvas.width !== w || probeCanvas.height !== h) {
    probeCanvas.width = w;
    probeCanvas.height = h;
  }
  var p = probeCanvas.getContext("2d", { willReadFrequently: true });
  p.imageSmoothingEnabled = false;
  p.clearRect(0, 0, w, h);
  p.drawImage(ctx.canvas, x0, y0, cw, ch, 0, 0, w, h);
  return {
    w: w, h: h,
    data: p.getImageData(0, 0, w, h).data,
    sx: w / cw, sy: h / ch,
    ox: -x0 * w / cw, oy: -y0 * h / ch,
    sourceW: ctx.canvas.width, sourceH: ctx.canvas.height,
    remaining: PROBE_MAX_PIXELS * 4
  };
}

function looksLikeDiagram(sample, q) {
  if (!sample) return false;
  if (!((q[0] === q[2] && q[3] === q[5]) || (q[1] === q[3] && q[2] === q[4]))) return false;
  var xs = [q[0], q[2], q[4], q[6]];
  var ys = [q[1], q[3], q[5], q[7]];
  if (Math.min.apply(null, xs) < 0 || Math.min.apply(null, ys) < 0 ||
      Math.max.apply(null, xs) > sample.sourceW || Math.max.apply(null, ys) > sample.sourceH) return false;
  var region = sample.regions?.get(q);
  if (sample.regions && !region) return false;
  var x0 = region ? region.x : Math.max(0, Math.floor(Math.min.apply(null, xs) * sample.sx + sample.ox));
  var y0 = region ? region.y : Math.max(0, Math.floor(Math.min.apply(null, ys) * sample.sy + sample.oy));
  var x1 = region ? region.x + region.w : Math.min(sample.w, Math.ceil(Math.max.apply(null, xs) * sample.sx + sample.ox));
  var y1 = region ? region.y + region.h : Math.min(sample.h, Math.ceil(Math.max.apply(null, ys) * sample.sy + sample.oy));
  var area = Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
  if (area < PROBE_MIN_SAMPLES || area > sample.remaining) return false;
  sample.remaining -= area;

  function strictDiagram() {
    var d = sample.data, pale = 0, ink = 0, middle = 0, interior = 0, flat = 0, flatPale = 0, flatTint = 0, tinted = 0, fringe = 0, toneCore = 0, n = 0;
    var stride = sample.w * 4;
    for (var y = y0; y < y1; y++) {
      for (var x = x0; x < x1; x++) {
        var o = (y * sample.w + x) * 4;
        var r = d[o], g = d[o + 1], b = d[o + 2];
        var mx = Math.max(r, g, b), mn = Math.min(r, g, b);
        if (d[o + 3] !== 255) return false;
        if (mx - mn > 40) {
          if (mx - mn > 64 || x === x0 || x + 1 === x1 || y === y0 || y + 1 === y1) return false;
          var center = r + g + b;
          var left = d[o - 4] + d[o - 3] + d[o - 2];
          var right = d[o + 4] + d[o + 5] + d[o + 6];
          var above = d[o - stride] + d[o - stride + 1] + d[o - stride + 2];
          var below = d[o + stride] + d[o + stride + 1] + d[o + stride + 2];
          var edgeLow = Math.min(center, left, right, above, below), edgeHigh = Math.max(center, left, right, above, below);
          if (edgeHigh - edgeLow < (mx - mn) * 5.25) {
            if (mx !== 255 || x < x0 + 3 || x + 3 >= x1 || y < y0 + 3 || y + 3 >= y1) return false;
            for (var radius = 2; radius <= 3 && edgeHigh - edgeLow < (mx - mn) * 5.25; radius++) {
              var fl = o - radius * 4, fr = o + radius * 4, fu = o - radius * stride, fd = o + radius * stride;
              var lt = d[fl] + d[fl + 1] + d[fl + 2], rt = d[fr] + d[fr + 1] + d[fr + 2];
              var ut = d[fu] + d[fu + 1] + d[fu + 2], dt = d[fd] + d[fd + 1] + d[fd + 2];
              edgeLow = Math.min(edgeLow, lt, rt, ut, dt); edgeHigh = Math.max(edgeHigh, lt, rt, ut, dt);
            }
            if (edgeHigh - edgeLow < (mx - mn) * 5.25) return false;
          }
          fringe++;
        }
        if (mx !== mn) tinted++;
        if (tinted > area * .08) return false;
        if (mx - mn > 2 && mn < 245) {
          var horizontal = x >= x0 + 4 && x + 4 < x1;
          var vertical = y >= y0 + 4 && y + 4 < y1;
          for (var step = -4; step <= 4 && (horizontal || vertical); step++) {
            for (var runChannel = 0; runChannel < 3 && (horizontal || vertical); runChannel++) {
              var runValue = d[o + runChannel];
              if (horizontal && Math.abs(runValue - d[o + step * 4 + runChannel]) > 2) horizontal = false;
              if (vertical && Math.abs(runValue - d[o + step * stride + runChannel]) > 2) vertical = false;
            }
          }
          if (horizontal || vertical) return false;
        }
        if (mx - mn > 2 && mn < 245 && x > x0 && x + 1 < x1 && y > y0 && y + 1 < y1) {
          var colourFlat = true;
          for (var channel = 0; channel < 3 && colourFlat; channel++) {
            var value = d[o + channel];
            colourFlat = Math.max(value, d[o - 4 + channel], d[o + 4 + channel], d[o - stride + channel], d[o + stride + channel]) -
              Math.min(value, d[o - 4 + channel], d[o + 4 + channel], d[o - stride + channel], d[o + stride + channel]) <= 4;
          }
          if (colourFlat) {
            if (mx < 245) return false;
            flatTint++;
          }
        }
        if (mn >= 245) pale++;
        else if (mx <= 32) ink++;
        else {
          middle++;
          if (!tinted && x >= x0 + 2 && x + 2 < x1 && y >= y0 + 2 && y + 2 < y1) {
            var core = true;
            for (var distance = 1; distance <= 2 && core; distance++) {
              var l = d[o - distance * 4], rr = d[o + distance * 4];
              var u = d[o - distance * stride], dd = d[o + distance * stride];
              core = l > 32 && l < 245 && rr > 32 && rr < 245 && u > 32 && u < 245 && dd > 32 && dd < 245;
            }
            if (core) toneCore++;
          }
          if (x > x0 && x + 1 < x1 && y > y0 && y + 1 < y1 &&
              d[o - 4] > 32 && d[o - 4] < 245 && d[o + 4] > 32 && d[o + 4] < 245 &&
              d[o - stride] > 32 && d[o - stride] < 245 &&
              d[o + stride] > 32 && d[o + stride] < 245) {
            interior++;
            var low = Math.min(r, d[o - 4], d[o + 4], d[o - stride], d[o + stride]);
            var high = Math.max(r, d[o - 4], d[o + 4], d[o - stride], d[o + stride]);
            if (high - low <= 16) {
              flatPale++;
              if (r < 240) flat++;
            }
          }
        }
        n++;
      }
    }
    if (n < PROBE_MIN_SAMPLES) return false;
    if (!tinted && pale / n >= .65 && ink >= 2 && middle / n <= .25 && interior / n <= .01) return true;
    if (!tinted && pale / n >= .65 && ink >= 2 && middle / n <= .35 &&
        flat / n <= .005 && flatPale / n <= .02 && toneCore / n <= .01) return true;
    return pale / n >= .8 && ink >= 2 && middle / n <= .2 && tinted / n <= .08 &&
      flat / n <= .005 && flatPale / n <= .02 && flatTint / n <= .00025 &&
      (!fringe || (fringe / n <= .001 && tinted / n >= .01));
  }
  if (strictDiagram()) return true;
  function subpixelLineart(sample, x0, y0, x1, y1, area) {
    if (area < 256 || area > sample.remaining) return false;
    var state = sample.lineartAnalysis;
    if (state && state.attempts >= 64) return false;
    sample.remaining -= area;
    if (!state) {
      state = {attempts: 0, palette: new Uint16Array(4096), runs: new Uint16Array(4096),
        keys: new Uint16Array(4096), previous: new Uint8Array(sample.w), columns: new Uint16Array(sample.w)};
      Object.defineProperty(sample, "lineartAnalysis", {value: state});
    }
    state.attempts++;
    var palette = state.palette, runs = state.runs, keys = state.keys;
    var previous = state.previous, columns = state.columns;
    palette.fill(0); runs.fill(0); previous.fill(0); columns.fill(0);
    var d = sample.data, stride = sample.w * 4;
    var paper = 0, foreground = 0, dark = 0, contrastInk = 0, neutralInk = 0, core = 0;
    var chroma = 0, warm = 0, cool = 0, axis = 0, shades = 0, flat = 0;
    var weakRun = 0, anyWeakRun = 0, badRun = 0, unsupported = 0, maxSpread = 0;
    var horizontalPairs = 0, rowRuns = 0, maxRuns = 0, keyCount = 0;
    function tone(o) { return (d[o] + d[o + 1] + d[o + 2]) / 3; }
    function opposed(o, cr, cb) {
      var r = d[o], g = d[o + 1], b = d[o + 2];
      if (Math.max(r, g, b) - Math.min(r, g, b) <= 2) return false;
      var nr = r - g, nb = b - g;
      return cr * nr + cb * nb < -.1 * (Math.abs(cr) + Math.abs(cb)) * (Math.abs(nr) + Math.abs(nb));
    }
    function hueKey(r, g, b) {
      var cr = r - g, cb = b - g, norm = Math.max(1, Math.abs(cr) + Math.abs(cb));
      return Math.min(7, ((cr / norm + 1) * 4) | 0) * 8 + Math.min(7, ((cb / norm + 1) * 4) | 0);
    }
    for (var y = y0; y < y1; y++) {
      rowRuns = 0;
      for (var x = x0; x < x1; x++) {
        var o = (y * sample.w + x) * 4;
        var r = d[o], g = d[o + 1], b = d[o + 2], hi = Math.max(r, g, b), lo = Math.min(r, g, b);
        var spread = hi - lo, value = (r + g + b) / 3, column = x - x0;
        if (d[o + 3] !== 255 || spread > 192) return false;
        maxSpread = Math.max(maxSpread, spread);
        if (value >= 245) { paper++; previous[column] = 0; }
        else {
          foreground++;
          if (foreground > area * .2) return false;
          if (x === x0 || tone(o - 4) >= 245) rowRuns++;
          if (!previous[column]) { columns[column]++; maxRuns = Math.max(maxRuns, columns[column]); }
          previous[column] = 1;
          maxRuns = Math.max(maxRuns, rowRuns);
        }
        if (value <= 96) dark++;
        if (value <= 160) contrastInk++;
        if (value <= 128 && spread <= 16) neutralInk++;
        var cr = r - g, cb = b - g, key = spread > 8 ? hueKey(r, g, b) : 0;
        var paired = false, pairedHorizontal = false;
        if (spread > 8 && lo < 245 && value < 250) {
          for (var radius = 1; radius <= 3; radius++) {
            if (x >= x0 + radius && opposed(o - radius * 4, cr, cb)) pairedHorizontal = true;
            if (x + radius < x1 && opposed(o + radius * 4, cr, cb)) pairedHorizontal = true;
            if (y >= y0 + radius && opposed(o - radius * stride, cr, cb)) paired = true;
            if (y + radius < y1 && opposed(o + radius * stride, cr, cb)) paired = true;
          }
          paired = paired || pairedHorizontal;
          if (paired && palette[key] < 65535) palette[key]++;
        }
        if ((g - Math.max(r, b) > 24 ||
            ((x === x0 || x + 1 === x1) && (y === y0 || y + 1 === y1) && spread > 40)) && !paired) return false;
        if (value < 245 && x >= x0 + 2 && x + 2 < x1 && y >= y0 + 2 && y + 2 < y1) {
          var interior = true;
          for (var radius = 1; radius <= 2 && interior; radius++) {
            var tl = tone(o - radius * 4), tr = tone(o + radius * 4);
            var tu = tone(o - radius * stride), td = tone(o + radius * stride);
            interior = tl < 245 && tr < 245 && tu < 245 && td < 245;
          }
          if (interior) core++;
        }
        if (spread > 8 && value < 245) {
          chroma++;
          if (pairedHorizontal) horizontalPairs++;
          if (r - b > 8) warm++;
          if (b - r > 8) cool++;
          if ((r >= g - 4 && g >= b - 4) || (b >= g - 4 && g >= r - 4)) axis++;
          shades |= 1 << (g >> 4);
          var lowTone = value, highTone = value;
          for (var radius = 1; radius <= 3; radius++) {
            if (x >= x0 + radius) { var t = tone(o - radius * 4); lowTone = Math.min(lowTone, t); highTone = Math.max(highTone, t); }
            if (x + radius < x1) { var t = tone(o + radius * 4); lowTone = Math.min(lowTone, t); highTone = Math.max(highTone, t); }
            if (y >= y0 + radius) { var t = tone(o - radius * stride); lowTone = Math.min(lowTone, t); highTone = Math.max(highTone, t); }
            if (y + radius < y1) { var t = tone(o + radius * stride); lowTone = Math.min(lowTone, t); highTone = Math.max(highTone, t); }
          }
          if (highTone - lowTone < Math.max(24, spread) || highTone < 245) unsupported++;
        }
        if (spread > 2 && lo < 245 && x > x0 && x + 1 < x1 && y > y0 && y + 1 < y1) {
          var same = true;
          for (var channel = 0; channel < 3 && same; channel++) {
            var v = d[o + channel];
            same = Math.max(v, d[o - 4 + channel], d[o + 4 + channel], d[o - stride + channel], d[o + stride + channel]) -
              Math.min(v, d[o - 4 + channel], d[o + 4 + channel], d[o - stride + channel], d[o + stride + channel]) <= 4;
          }
          if (same && !(hi <= 40 && spread <= 4)) {
            if (hi < 245) return false;
            flat++;
          }
        }
        if (spread > 2 && lo < 245) {
          var horizontal = x >= x0 + 4 && x + 4 < x1, vertical = y >= y0 + 4 && y + 4 < y1;
          var hueHorizontal = horizontal && spread > 8;
          for (var step = -4; step <= 4 && (horizontal || vertical || hueHorizontal); step++) {
            if (hueHorizontal) {
              var at = o + step * 4, hr = d[at], hg = d[at + 1], hb = d[at + 2];
              if (Math.max(hr, hg, hb) - Math.min(hr, hg, hb) <= 8 || hueKey(hr, hg, hb) !== key) hueHorizontal = false;
            }
            for (var channel = 0; channel < 3 && (horizontal || vertical); channel++) {
              var v = d[o + channel];
              if (horizontal && Math.abs(v - d[o + step * 4 + channel]) > 2) horizontal = false;
              if (vertical && Math.abs(v - d[o + step * stride + channel]) > 2) vertical = false;
            }
          }
          if (hueHorizontal) {
            var transverse = false;
            for (var radius = 1; radius <= 3 && !transverse; radius++) {
              if (y >= y0 + radius && opposed(o - radius * stride, cr, cb)) transverse = true;
              if (y + radius < y1 && opposed(o + radius * stride, cr, cb)) transverse = true;
            }
            if (!transverse) return false;
          }
          if (horizontal || vertical) {
            if (spread <= 8) { if (!(value <= 128 && spread <= 4)) { anyWeakRun++; if (value > 224) weakRun++; } }
            else {
              var runPaired = false;
              for (var radius = 1; radius <= 3 && !runPaired; radius++) {
                if (horizontal && y >= y0 + radius && opposed(o - radius * stride, cr, cb)) runPaired = true;
                if (horizontal && y + radius < y1 && opposed(o + radius * stride, cr, cb)) runPaired = true;
                if (vertical && x >= x0 + radius && opposed(o - radius * 4, cr, cb)) runPaired = true;
                if (vertical && x + radius < x1 && opposed(o + radius * 4, cr, cb)) runPaired = true;
              }
              if (!runPaired) {
                badRun++;
                if (!runs[key]) keys[keyCount++] = key;
                if (runs[key] < 65535) runs[key]++;
              }
            }
          }
        }
      }
    }
    if (paper / area < .8 || dark < 2 || (contrastInk < foreground * .2 && dark < foreground * .1) ||
        core > Math.ceil(foreground * .2) || flat > Math.max(2, Math.ceil(foreground * .005))) return false;
    var shadeCount = 0;
    for (var bits = shades; bits; bits &= bits - 1) shadeCount++;
    var badPalette = false;
    for (var i = 0; i < keyCount; i++) {
      var key = keys[i];
      var support = palette[key] * 2, hueR = key >> 3, hueB = key & 7;
      for (var direction = -1; direction <= 1; direction += 2) {
        var nextR = hueR + direction;
        var nextB = hueB + ((hueR < 4) === (hueB < 4) ? -direction : direction);
        if (nextR >= 0 && nextR < 8 && nextB >= 0 && nextB < 8 &&
            (nextR < 4) === (hueR < 4) && (nextB < 4) === (hueB < 4)) support += palette[nextR * 8 + nextB];
      }
      if (palette[key] < 2 || runs[key] > support) { badPalette = true; break; }
    }
    var dense = chroma >= 32 && axis >= chroma * .65 && Math.min(warm, cool) >= 8 &&
      Math.min(warm, cool) >= Math.max(warm, cool) * .25 && shadeCount >= 8 &&
      horizontalPairs >= chroma * .2 && weakRun / area <= .001 && !badPalette &&
      badRun <= foreground * .15 && maxRuns >= 3;
    var sparse = chroma <= Math.max(4, area * .0015) && maxSpread <= 96 &&
      neutralInk >= foreground * .2 && maxRuns >= 3 && !unsupported && !anyWeakRun && !badRun;
    return dense || sparse;
  }
  try { return subpixelLineart(sample, x0, y0, x1, y1, area); } catch (_) { return false; }
}

export function releaseProbe() { if (probeCanvas) { probeCanvas.width = probeCanvas.height = 0; probeCanvas = null; } }
export {pageSample, looksLikeDiagram};
