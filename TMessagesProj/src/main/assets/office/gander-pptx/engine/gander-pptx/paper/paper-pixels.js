/* Modifications Copyright (C) 2026 Ettacent */

"use strict";

var VW_MATTE_PIXELS = 350000;

function vwPaperWhite(d, p) {
  var o = p * 4, lo = Math.min(d[o], d[o + 1], d[o + 2]);
  return lo >= 248 && Math.max(d[o], d[o + 1], d[o + 2]) - lo <= 10;
}

function vwMatteArtwork(d, w, h) {
  var n = w * h;
  if (w < 8 || h < 8 || w > 2048 || h > 2048 || n > VW_MATTE_PIXELS || d.length !== n * 4) return false;
  var border = 0, whiteBorder = 0, foreground = 0, neutral = 0, accent = 0;
  var palette = new Uint32Array(4096);
  for (var p = 0; p < n; p++) {
    var o = p * 4;
    if (d[o + 3] !== 255) return false;
    var x = p % w, y = Math.floor(p / w), white = vwPaperWhite(d, p);
    if (!x || x === w - 1 || !y || y === h - 1) { border++; if (white) whiteBorder++; }
    if (white) continue;
    foreground++;
    var hi = Math.max(d[o], d[o + 1], d[o + 2]), lo = Math.min(d[o], d[o + 1], d[o + 2]);
    if (hi - lo <= 12 && hi > 48 && hi < 240) neutral++;
    if (hi - lo > 40) accent++;
    palette[(d[o] >> 4) * 256 + (d[o + 1] >> 4) * 16 + (d[o + 2] >> 4)]++;
  }
  if (whiteBorder < border * .75 || foreground < n * .01 || foreground > n * .65 ||
      neutral > foreground * .12 || accent < foreground * .25) return false;
  var common = Array.from(palette).sort(function (a, b) { return b - a; });
  var flat = 0;
  for (var k = 0; k < 16; k++) flat += common[k];
  if (flat < foreground * .60) return false;

  var mask = new Uint8Array(n), queue = new Int32Array(n), head = 0, tail = 0;
  function paper(p) {
    if (!mask[p] && vwPaperWhite(d, p)) { mask[p] = 1; queue[tail++] = p; }
  }
  for (var x = 0; x < w; x++) { paper(x); paper(n - w + x); }
  for (var y = 1; y < h - 1; y++) { paper(y * w); paper(y * w + w - 1); }
  while (head < tail) {
    var p = queue[head++], x = p % w;
    if (x) paper(p - 1);
    if (x < w - 1) paper(p + 1);
    if (p >= w) paper(p - w);
    if (p < n - w) paper(p + w);
    if (p >= w && x) paper(p - w - 1);
    if (p >= w && x < w - 1) paper(p - w + 1);
    if (p < n - w && x) paper(p + w - 1);
    if (p < n - w && x < w - 1) paper(p + w + 1);
  }
  if (tail < n * .12) return false;
  function besidePaper(p) {
    var x = p % w;
    return (x && mask[p - 1] === 1) || (x < w - 1 && mask[p + 1] === 1) ||
      (p >= w && (mask[p - w] === 1 || (x && mask[p - w - 1] === 1) || (x < w - 1 && mask[p - w + 1] === 1))) ||
      (p < n - w && (mask[p + w] === 1 || (x && mask[p + w - 1] === 1) || (x < w - 1 && mask[p + w + 1] === 1)));
  }
  var edgeAlpha = new Uint8Array(n);
  for (var p = 0; p < n; p++) {
    if (mask[p] === 1 || !besidePaper(p)) continue;
    var o = p * 4, cr = 255 - d[o], cg = 255 - d[o + 1], cb = 255 - d[o + 2];
    var depth = Math.max(cr, cg, cb), best = depth, x = p % w, y = Math.floor(p / w);
    if (!depth || depth > 160) continue;
    for (var yy = Math.max(0, y - 2); yy <= Math.min(h - 1, y + 2); yy++) {
      for (var xx = Math.max(0, x - 2); xx <= Math.min(w - 1, x + 2); xx++) {
        var q = (yy * w + xx) * 4, r = 255 - d[q], g = 255 - d[q + 1], b = 255 - d[q + 2];
        var deeper = Math.max(r, g, b);
        if (deeper > best && (depth < 96 || Math.max(Math.abs(cr / depth - r / deeper),
            Math.abs(cg / depth - g / deeper), Math.abs(cb / depth - b / deeper)) <= .08)) best = deeper;
      }
    }
    if (depth < 64) best = 255;
    if (best > depth) edgeAlpha[p] = Math.max(1, Math.round(255 * depth / best));
  }
  for (var p = 0; p < n; p++) {
    var o = p * 4;
    if (mask[p] === 1) d[o + 3] = 0;
    else if (edgeAlpha[p]) {
      var alpha = edgeAlpha[p] / 255;
      for (var k = 0; k < 3; k++) d[o + k] = Math.max(0, Math.round(255 - (255 - d[o + k]) / alpha));
      d[o + 3] = edgeAlpha[p];
    }
  }
  return true;
}

var vwPaperLinear = null;
function vwArtworkExposure(d, w, h) {
  var n = w * h, bins = new Array(32).fill(0);
  if (w < 8 || h < 8 || w > 2048 || h > 2048 || n > VW_MATTE_PIXELS || d.length !== n * 4) return bins;
  if (!vwPaperLinear) {
    vwPaperLinear = new Float64Array(256);
    for (var i = 0; i < 256; i++) {
      var channel = i / 255;
      vwPaperLinear[i] = channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }
  }
  for (var p = 0; p < n; p++) {
    var o = p * 4;
    if (d[o + 3] !== 255) continue;
    var x = p % w, y = Math.floor(p / w), exposed = false;
    for (var yy = Math.max(0, y - 2); yy <= Math.min(h - 1, y + 2) && !exposed; yy++) {
      for (var xx = Math.max(0, x - 2); xx <= Math.min(w - 1, x + 2); xx++) {
        if (d[(yy * w + xx) * 4 + 3] <= 32) { exposed = true; break; }
      }
    }
    if (!exposed) continue;
    var luminance = .2126 * vwPaperLinear[d[o]] + .7152 * vwPaperLinear[d[o + 1]] + .0722 * vwPaperLinear[d[o + 2]];
    bins[Math.min(31, Math.floor(luminance * 32))]++;
  }
  return bins;
}

function vwArtworkDarkLayers(d, w, h) {
  var n = w * h;
  if (w < 8 || h < 8 || w > 2048 || h > 2048 || n > VW_MATTE_PIXELS || d.length !== n * 4) return null;
  var exterior = new Uint16Array(n), support = new Uint16Array(n), queue = new Int32Array(n);
  var ink = new Uint8ClampedArray(d.length), minimum = [255, 255, 255], count = 0;
  function distance(out, paper) {
    out.fill(65535); var head = 0, tail = 0;
    for (var p = 0; p < n; p++) {
      var o = p * 4;
      var seed = paper ? d[o + 3] === 0 : d[o + 3] === 255 &&
        .2126 * vwPaperLinear[d[o]] + .7152 * vwPaperLinear[d[o + 1]] + .0722 * vwPaperLinear[d[o + 2]] >= .25;
      if (seed) { out[p] = 0; queue[tail++] = p; }
    }
    while (head < tail) {
      var p = queue[head++], x = p % w, y = Math.floor(p / w), next = out[p] + 1;
      for (var yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++) {
        for (var xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++) {
          var q = yy * w + xx;
          if (out[q] === 65535) { out[q] = next; queue[tail++] = q; }
        }
      }
    }
  }
  if (!vwPaperLinear) vwArtworkExposure(d, w, h);
  distance(exterior, true); distance(support, false);
  for (var p = 0; p < n; p++) {
    var o = p * 4;
    if (!d[o + 3] || exterior[p] > support[p]) continue;
    var luminance = .2126 * vwPaperLinear[d[o]] + .7152 * vwPaperLinear[d[o + 1]] + .0722 * vwPaperLinear[d[o + 2]];
    if (luminance >= .25) continue;
    for (var k = 0; k < 4; k++) ink[o + k] = d[o + k];
    for (var k = 0; k < 3; k++) minimum[k] = Math.min(minimum[k], d[o + k]);
    d[o + 3] = 0; count++;
  }
  return {data: ink, minimum: minimum, pixels: count};
}

function vwArtworkInkMix(minimum, rgb) {
  function valid(v) { return Array.isArray(v) && v.length === 3 && v.every(function(c) { return Number.isInteger(c) && c >= 0 && c <= 255; }); }
  if (!valid(minimum) || !valid(rgb)) return null;
  function linear(c) { c /= 255; return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4); }
  function luminance(c) { return .2126 * linear(c[0]) + .7152 * linear(c[1]) + .0722 * linear(c[2]); }
  var paper = luminance(rgb);
  if (paper > .04) return null;
  var target = 4.6 * (paper + .05) - .05;
  for (var amount = 0; amount <= 255; amount++) {
    var colour = minimum.map(function(c) { return Math.floor(c + (255 - c) * amount / 255); });
    if (luminance(colour) >= target) return amount / 255;
  }
  return null;
}
