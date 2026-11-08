/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent; Gander MIT attribution in LICENSE_GANDER_MIT.txt. */
import * as pdfjsLib from "./lib/pdf.min.mjs";
import * as officeSearch from "./search-text.mjs";
import { pageSample, looksLikeDiagram, releaseProbe } from "./night-core.mjs";
import { NightPipeline } from "./night-client.mjs";
import { extraPixels as nightExtraPixels } from "./night-protocol.mjs";
















pdfjsLib.GlobalWorkerOptions.workerSrc = new URL("./lib/pdf.worker.min.mjs", import.meta.url).href;

var pages = document.getElementById("pages");
var officeLayoutRevision = 0;
function officeLayoutChanged() {officeLayoutRevision++;}
window.officePdfLayoutChanged = officeLayoutChanged;
window.addEventListener('resize', officeLayoutChanged);
var officePageRects = new WeakMap(),officeExtentCache = null,officeGeometryObserver = null;
if (typeof ResizeObserver === 'function') {
  try {
    officeGeometryObserver = new ResizeObserver(officeLayoutChanged);
    officeGeometryObserver.observe(pages);
    if (document.body) officeGeometryObserver.observe(document.body, { box: 'border-box' });
  } catch (_) {
    if (officeGeometryObserver) officeGeometryObserver.disconnect();
    officeGeometryObserver = null;
  }
}
function officePageRect(slot, force) {

  {
    var cached = officePageRects.get(slot.box),w = innerWidth,h = innerHeight;
    if (force || !officeGeometryObserver || !cached || cached.revision !== officeLayoutRevision || cached.w !== w || cached.h !== h) {

      var rect = slot.box.getBoundingClientRect();
      cached = { revision: officeLayoutRevision, w: w, h: h, top: rect.top + scrollY, left: rect.left + scrollX,
        width: rect.width, height: rect.height };
      officePageRects.set(slot.box, cached);
    }
    var top = cached.top - scrollY,left = cached.left - scrollX;
    return { top: top, bottom: top + cached.height, left: left, right: left + cached.width, width: cached.width, height: cached.height };
  }
}
function officeDocumentExtent(force) {

  {
    var w = innerWidth,h = innerHeight;
    if (force || !officeGeometryObserver || !officeExtentCache || officeExtentCache.revision !== officeLayoutRevision || officeExtentCache.w !== w || officeExtentCache.h !== h) {

      officeExtentCache = { revision: officeLayoutRevision, w: w, h: h, value: document.documentElement.scrollHeight };
    }
    return officeExtentCache.value;
  }
}

var PAGE_WIDTH = pages.clientWidth || window.innerWidth;

var DPR_FALLBACK = Math.min(window.devicePixelRatio || 1, 2);

var BASE_HEADROOM = 1.1;

var MAX_SIDE = 8192;
var BASE_MAX_PIXELS = 4e6;

function baseScale() {
  var w = vwPhysicalWidth();
  return w ? Math.min(w / PAGE_WIDTH * BASE_HEADROOM, DPR_FALLBACK) : DPR_FALLBACK;
}

function officeBaseDimensions(unscaled) {
  var k = PAGE_WIDTH / unscaled.width * baseScale();
  k *= within(unscaled.width * k, unscaled.height * k, BASE_MAX_PIXELS);
  return { width: Math.floor(unscaled.width * k), height: Math.floor(unscaled.height * k) };
}

var officeLostCanvases = new WeakMap();
function officeTrackCanvas(slot, canvas) {
  canvas.addEventListener("contextlost", function () {
    officeLostCanvases.set(canvas, false);
  });
  canvas.addEventListener("contextrestored", function () {
    officeLostCanvases.set(canvas, true);
    if (officeClosed || officePaused ||
        slot.canvas !== canvas && slot.tile?.canvas !== canvas) return;
    officeVisible();
    scheduleRefine();
  });
  return canvas;
}

function officeCanvasLost(canvas, context) {
  return officeLostCanvases.has(canvas) ||
    typeof context.isContextLost === "function" && context.isContextLost();
}

function officeBaseNeedsRefresh(slot) {
  if (officeLostCanvases.has(slot.canvas)) return officeLostCanvases.get(slot.canvas);
  if (!slot.unscaled) return false;
  var size = officeBaseDimensions(slot.unscaled);
  return slot.canvas.width + 1 < size.width || slot.canvas.height + 1 < size.height;
}

function officeAbandonBaseWork(slot) {
  if (slot.workCanvas) {

    {
      officeNightReleaseCanvas(slot.workCanvas);
      slot.workCanvas = null;
    }
  }
  var keep = !officeClosed && slot.baseComplete && slot.canvas.width > 0 && slot.canvas.height > 0 &&
  slot.mode === inverted && slot.palette === officePaletteEpoch;
  if (keep && !officePaused) {
    var rect = officePageRect(slot),margin = Math.round(KEEP_PAGES * PAGE_PITCH);
    keep = rect.bottom > -margin && rect.top < innerHeight + margin;
  }
  if (keep) slot.state = "drawn";else
  blank(slot);
}

function liveScale() {
  var vv = window.visualViewport;
  if (!vv || !(vv.width > 0)) return DPR_FALLBACK;
  var w = vwPhysicalWidth();
  return w ? w / vv.width : DPR_FALLBACK * (PAGE_WIDTH / vv.width);
}

function within(w, h, maxPixels) {
  var f = 1;
  if (w > MAX_SIDE) f = Math.min(f, MAX_SIDE / w);
  if (h > MAX_SIDE) f = Math.min(f, MAX_SIDE / h);
  if (w * h > maxPixels) f = Math.min(f, Math.sqrt(maxPixels / (w * h)));
  return f;
}

var MAX_IN_FLIGHT = 2;

var DRAW_PAGES = 1;
var KEEP_PAGES = 2;

var PAGE_PITCH = 0;

var doc = null;

var slots = [];
var queue = [];

/* Modifications Copyright (C) 2026 Ettacent. Gander (MIT); see the accompanying Gander license. */
var officeClosed = false;
var officePaused = false;
var officeLoadingTask = null;

var officeNativeWorker = null;
var officePdfWorker = null;
var officePreserveImages = true;
var officeObservers = [];
var officeIndexTimer = 0;
var officeIndexResume = null;
var officePaletteEpoch = 0;
var officeInitialSlot = null;
var officeInitialPlaced = false;
var officeInitialPlacement = null;
var officeDidReady = false;
var officeIndexBytes = 0;
var OFFICE_MAX_MATCHES = 1000;
var officeReveals = new Map();
var OFFICE_MAX_REVEALS = 4;
var officeReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
function officeMotionChanged() {if (officeReducedMotion.matches) officeFinishReveals();}
officeReducedMotion.addEventListener("change", officeMotionChanged);

var OFFICE_MAX_PIXELS = 24e6;
var officeNightPipe = new NightPipeline();
var officeNightLease = null,officeNightSerial = 0;
function officeNightLimit() {
  return officeNightLease || inverted && officeNightPipe.available() ? 1 : MAX_IN_FLIGHT;
}
function officeNightPixels() {
  var lease = officeNightLease;
  if (!lease) return 0;
  var c = lease.canvas;
  var tracked = slots.some(function (s) {
    return s.canvas === c || s.workCanvas === c || s.tile?.canvas === c ||
    s.pending?.canvas === c || s.retiring?.canvas === c;
  });
  return lease.extra + (!tracked ? c.width * c.height : 0);
}
function officeNightReleaseCanvas(canvas) {
  if (officeNightPipe.deferRelease(canvas)) return;
  canvas.width = canvas.height = 0;
}
function officeNightClaim(width, height, slot, mode, canvas) {
  var pixels = width * height;
  if (!mode || !officeNightPipe.available()) {officeReservePixels(pixels, slot);return null;}
  if (officeNightLease) throw new Error('Night admission ownership');
  var extra = nightExtraPixels(width, height);
  officeReservePixels(pixels + extra, slot);
  var lease = { canvas: canvas, extra: extra, render: ++officeNightSerial };
  officeNightLease = lease;
  return lease;
}
function officeNightRetire(lease) {
  if (lease && officeNightLease === lease) officeNightLease = null;
}
function officeNightCancel(entry) {
  if (officeNightPipe.active?.entry === entry) officeNightPipe.cancel();
}
function officeNightPrepare(lease, entry, source, spec, current) {
  if (!lease || !officeNightPipe.available() || !current()) return Promise.resolve(null);
  var matrix = document.getElementById('office-night-matrix')?.getAttribute('values')?.trim().split(/[\s,]+/).map(Number);
  if (!matrix || matrix.length !== 20) {
    officeNightPipe.disabled = true;return Promise.resolve(null);
  }
  var promise = officeNightPipe.prepare(source, Object.assign({}, spec, { matrix: matrix,
    ids: { document: 1, palette: officePaletteEpoch, slot: spec.slot.n, render: lease.render } }), current);
  if (officeNightPipe.active) officeNightPipe.active.entry = entry;
  return promise.catch(function (error) {
    if (error.code === 'cancelled' || !current()) throw officeRenderCancelled();
    if (error.code === 'unsupported-clip' && error.pictures) {
      return { pictures: error.pictures, classificationOnly: true, dispose: function () {} };
    }
    return null;
  });
}
























function officeFinishReveal(canvas) {
  var entry = officeReveals.get(canvas);
  if (!entry) return;



  officeReveals.delete(canvas);
  clearTimeout(entry.timer);
  entry.animation.onfinish = entry.animation.oncancel = null;
  entry.animation.cancel();
  if (entry.previous) {
    if (entry.slot.retiring === entry.previous) entry.slot.retiring = null;
    shed(entry.previous);
  }
}

function officeFinishReveals(slot) {
  officeReveals.forEach(function (entry, canvas) {
    if (!slot || entry.slot === slot) officeFinishReveal(canvas);
  });
}

function officePresent(slot, canvas, previous) {

  try {
    var bounds = window.officePdfVisibleBounds();
    if (officeDidReady && !officeClosed && !officePaused && !document.hidden &&
    bounds.behavior === "smooth" && officeReveals.size < OFFICE_MAX_REVEALS &&
    typeof canvas.animate === "function") {
      var r = canvas.getBoundingClientRect();
      if (r.bottom > bounds.top && r.top < bounds.bottom && r.right > bounds.left && r.left < bounds.right) {
        var tile = canvas.className === "tile";
        var replacing = previous && previous.canvas.width > 0 && !officeLostCanvases.has(previous.canvas);
        var duration = tile || replacing ? 140 : 200;


        try {
          var animation = canvas.animate([{ opacity: 0 }, { opacity: 1 }],
          { duration: duration, easing: tile || replacing ? "cubic-bezier(.22,1,.36,1)" : "cubic-bezier(.4,0,.2,1)" });
        } catch (error) {throw error;}
        var entry = { slot: slot, animation: animation, previous: previous, timer: 0 };


        officeReveals.set(canvas, entry);
        if (previous) slot.retiring = previous;
        animation.onfinish = animation.oncancel = function () {officeFinishReveal(canvas);};
        entry.timer = setTimeout(function () {officeFinishReveal(canvas);}, duration + 50);

        return;
      }
    }

  } catch (_) { }

  shed(previous);
}

function officeGuard(slot) {
  if (officeClosed || officePaused || slot?.navigationCancelled) {
    var e = new Error('Inactive document');
    e.name = 'RenderingCancelledException';
    throw e;
  }
}

function officeRasterPixels() {
  return slots.reduce(function (n, s) {
    return n + s.canvas.width * s.canvas.height + (
    s.workCanvas ? s.workCanvas.width * s.workCanvas.height : 0) + (
    s.tile ? s.tile.canvas.width * s.tile.canvas.height : 0) + (
    s.retiring ? s.retiring.canvas.width * s.retiring.canvas.height : 0) + (
    s.pending ? s.pending.canvas.width * s.pending.canvas.height : 0);
  }, 0) + officeNightPixels() + officePreviewPendingPixels;
}

var officePreviews = new Map();
var officePreviewPendingPixels = 0;
var OFFICE_PREVIEW_COUNT = 12,OFFICE_PREVIEW_PIXELS = 524288;

function officePreviewPixels() {
  var n = 0;
  officePreviews.forEach(function (p) {n += p.canvas.width * p.canvas.height;});
  return n;
}
function officePreviewDrop(slot) {
  var preview = officePreviews.get(slot);
  if (!preview) return;
  officePreviews.delete(slot);
  if (slot.canvas === preview.canvas) {
    preview.canvas.width = preview.canvas.height = 0;
    slot.mode = null;
  }

}
function officePreviewClear() {
  Array.from(officePreviews.keys()).forEach(officePreviewDrop);
}
function officePreviewYield(extra, keep) {
  var candidates = Array.from(officePreviews.keys());
  for (var i = 0; i < candidates.length && officeRasterPixels() + extra > OFFICE_MAX_PIXELS; i++) {
    if (candidates[i] !== keep) officePreviewDrop(candidates[i]);
  }
}
function officePreviewAdmission(extra, keep) {
  var needed = officeRasterPixels() + extra - OFFICE_MAX_PIXELS;
  if (needed <= 0) return true;
  var discardable = 0;
  officePreviews.forEach(function (preview, slot) {
    if (slot !== keep && slot.canvas === preview.canvas) discardable += preview.canvas.width * preview.canvas.height;
  });
  if (discardable < needed) return false;
  officePreviewYield(extra, keep);
  return officeRasterPixels() + extra <= OFFICE_MAX_PIXELS;
}
function officePreviewForget(slot) {
  officePreviews.delete(slot);
}
function officePreviewRetain(slot) {
  var cached = officePreviews.get(slot);
  if (officeClosed || slot.mode !== inverted || slot.palette !== officePaletteEpoch) {
    officePreviewDrop(slot);return false;
  }
  if (officeLostCanvases.has(slot.canvas)) {officePreviewDrop(slot);return false;}
  if (cached && cached.canvas === slot.canvas && cached.canvas.width && cached.canvas.height) return true;
  if (officePaused || !slot.baseComplete || !slot.canvas.width || !slot.canvas.height) return false;
  var source = slot.canvas,k = Math.min(1, 256 / source.width, 256 / source.height,
    Math.sqrt(49152 / (source.width * source.height)));
  var w = Math.max(1, Math.floor(source.width * k)),h = Math.max(1, Math.floor(source.height * k));
  var pixels = w * h;
  while (officePreviews.size >= OFFICE_PREVIEW_COUNT || officePreviewPixels() + pixels > OFFICE_PREVIEW_PIXELS) {
    var oldest = officePreviews.keys().next().value;
    if (!oldest || oldest === slot) break;
    officePreviewDrop(oldest);
  }
  var preview = source;
  if (k < 1) {
    officePreviewYield(pixels, slot);
    if (officeRasterPixels() + pixels > OFFICE_MAX_PIXELS) {return false;}
    preview = officeTrackCanvas(slot, document.createElement('canvas'));
    try {
      officePreviewPendingPixels = pixels;
      preview.width = w;preview.height = h;
      var ctx = preview.getContext('2d');
      if (!ctx) throw new Error('Preview canvas');
      ctx.drawImage(source, 0, 0, w, h);
      source.width = source.height = 0;
      source.replaceWith(preview);
      slot.canvas = preview;
    } catch (_) {
      preview.width = preview.height = 0;return false;
    } finally {officePreviewPendingPixels = 0;}
  }
  preview.setAttribute('aria-hidden', 'true');
  officePreviews.set(slot, { canvas: preview, mode: slot.mode, palette: slot.palette });

  return true;
}

function officeReservePixels(extra, keep) {
  if (officeRasterPixels() + extra <= OFFICE_MAX_PIXELS) return;
  officeFinishReveals();
  if (officeRasterPixels() + extra <= OFFICE_MAX_PIXELS) return;
  if (officePreviewAdmission(extra, keep)) return;
  var candidates = slots.filter(function (s) {return s !== keep && (s.state === 'drawn' ||
    s.state === 'queued' && s.baseComplete && s.canvas.width > 0);});
  candidates.sort(function (a, b) {return distance(b) - distance(a);});
  while (candidates.length && officeRasterPixels() + extra > OFFICE_MAX_PIXELS) {
    var candidate = candidates.shift();
    if (candidate.state === 'queued') {
      candidate.canvas.width = candidate.canvas.height = 0;
    } else blank(candidate);
    officePreviewAdmission(extra, keep);
  }
  officePreviewYield(extra, keep);
  if (officeRasterPixels() + extra > OFFICE_MAX_PIXELS && officePreviews.has(keep)) officePreviewDrop(keep);
  if (officeRasterPixels() + extra > OFFICE_MAX_PIXELS) throw new Error('Raster budget');
}

function officeVisible() {
  if (!doc || officeClosed || officePaused) return;
  var vv = window.visualViewport;
  var top = vv ? vv.offsetTop : 0;
  var bottom = top + (vv ? vv.height : innerHeight);
  var lo = 0,hi = slots.length - 1,from = slots.length;
  while (lo <= hi) {
    var mid = lo + hi >> 1;
    if (officePageRect(slots[mid]).bottom > top) {from = mid;hi = mid - 1;} else
    lo = mid + 1;
  }
  for (var i = from; i < slots.length; i++) {
    if (officePageRect(slots[i]).top >= bottom) break;
    want(slots[i]);
  }
  pump();
}

function officeMaybeReady() {
  if (officeClosed || officePaused || officeDidReady || !officeInitialPlaced || !officeInitialSlot || officeInitialSlot.state !== 'drawn' || !officeInitialSlot.textReady) return;
  officeDidReady = true;
  vwStatusDone();
  if (doc.numPages > 1) observePages();
  reportPage();
  if (finding.query) {buildIndex();report();}
}

function officeContinueInitialPage() {
  if (officeClosed || officePaused || officeDidReady || !officeInitialSlot) return;
  if (!officeInitialPlaced) {
    if (officeInitialPlacement) return;
    var attempt = officeInitialPlacement = { epoch: officeNavigationEpoch };
    Promise.resolve().then(function () {
      if (officeInitialPlacement === attempt && attempt.epoch === officeNavigationEpoch && !officeClosed && !officePaused) return place(officeInitialSlot);
    }).then(function () {
      if (officeInitialPlacement !== attempt) return;
      officeInitialPlacement = null;
      if (officeClosed || officePaused) return;
      if (attempt.epoch === officeNavigationEpoch) officeInitialPlaced = true;
      officeContinueInitialPage();
    }, function (error) {
      if (officeInitialPlacement !== attempt) return;
      officeInitialPlacement = null;
      if (officeClosed || officePaused) return;
      if (attempt.epoch !== officeNavigationEpoch) officeContinueInitialPage();else
      window.officePdfFailure(error);
    });
    return;
  }
  want(officeInitialSlot);pump();officeMaybeReady();
}

function officeStopObservers() {
  officeObservers.forEach(function (o) {o.disconnect();});
  officeObservers.length = 0;
}

function officePause(on) {
  if (officeClosed || officePaused === on) return;
  officePaused = on;
  if (on) {
    officeInitialPlacement = null;
    officeNightPipe.cancel();
    officeCancelNavigation();
    officeFinishReveals();
    officeStopObservers();
    queue.forEach(function (s) {if (s.state === 'queued') officeAbandonBaseWork(s);});
    queue.length = 0;
    tileQueue.length = 0;
    clearTimeout(tileTimer);tileTimer = 0;
    clearTimeout(officeIndexTimer);officeIndexTimer = 0;
    cancelAnimationFrame(pageRaf);pageRaf = 0;
    slots.forEach(function (s) {
      if (s.task) s.task.cancel();
      officeCancelText(s);
      shed(s.pending);s.pending = null;
    });
  } else {
    if (doc && PAGE_PITCH) {
      officeContinueInitialPage();
      if (officeInitialPlaced) {observePages();officeVisible();officeMaybeReady();}
    }
    if (officeIndexResume && finding.query) officeIndexTimer = setTimeout(officeIndexResume, 0);
    if (finding.query && !finding.pages) buildIndex();
    officeScheduleTextDrain();
    paint();report();scheduleRefine();
  }
}

function officeClose() {
  if (officeClosed) return;
  officePause(true);
  officeClosed = true;
  officePreviewClear();
  officeNightPipe.close();
  if (officeGeometryObserver) {officeGeometryObserver.disconnect();officeGeometryObserver = null;}
  officePageRects = new WeakMap();officeExtentCache = null;
  window.removeEventListener('resize', officeLayoutChanged);
  if (window.officePdfLayoutChanged === officeLayoutChanged) delete window.officePdfLayoutChanged;
  officeInitialPlacement = officeInitialSlot = null;
  officeReducedMotion.removeEventListener("change", officeMotionChanged);
  officeIndexResume = null;
  finding.query = '';finding.pages = null;finding.matches = [];finding.building = false;
  officeIndexBytes = 0;officeCancelReveal();
  hitRanges = [];activeRanges = [];
  CSS.highlights.delete('vw-find');CSS.highlights.delete('vw-find-active');
  slots.forEach(function (s) {blank(s);});
  slots.length = 0;
  pages.replaceChildren();
  releaseProbe();
  var worker = officeNativeWorker,proxy = officePdfWorker,destroyTimer = 0,destroyed = false;
  officeNativeWorker = officePdfWorker = null;
  function terminate() {
    if (destroyed) return;
    destroyed = true;clearTimeout(destroyTimer);
    if (proxy) proxy.destroy();
    if (worker) worker.terminate();
  }
  var stopped = officeLoadingTask ? officeLoadingTask.destroy() : doc && typeof doc.destroy === 'function' ? doc.destroy() : null;
  if (stopped && stopped.then) {destroyTimer = setTimeout(terminate, 250);stopped.then(terminate, terminate);} else
  terminate();
  officeLoadingTask = null;doc = null;
  try {pdfjsLib.TextLayer.cleanup();} catch (_) {}
}

function officePaletteChanged() {
  if (officeClosed) return;
  officePaletteEpoch++;
  officePreviewClear();
  officeNightPipe.cancel();
  slots.forEach(function (s) {
    if (s.state === 'drawn') {blank(s);want(s);}
  });
  pump();
}

function officeReadingMode(on, preserve, changed) {
  if (officeClosed) return;
  preserve = preserve !== false;
  var redraw = on !== inverted || on && (preserve !== officePreserveImages || changed);
  inverted = on;officePreserveImages = preserve;
  document.documentElement.classList.toggle('vw-night', on);
  if (redraw) officePaletteChanged();
}

function officeInstall() {
  window.OfficePdf = Object.freeze({
    setPaused: officePause,
    finishPresentation: officeFinishReveals,
    close: officeClose,
    night: setNight,
    readingMode: officeReadingMode,
    paletteChanged: officePaletteChanged,
    cancelReveal: officeCancelReveal,
    cancelNavigation: officeCancelNavigation,
    retainPageRequest: officeRetainPageRequest,
    query: function (q, shouldReveal) {
      if (officeClosed || officePaused) return;
      officeTextProgress();
      if (shouldReveal) officeRequestReveal();
      var same = q === finding.query;
      onCommand('q' + q);
      if (shouldReveal && same && finding.matches.length) stepTo(finding.active < 0 ? 0 : finding.active);
      if (q && !finding.building && finding.built < (doc ? doc.numPages : 0)) buildIndex();
      report();
    },
    find: function (steps, shouldReveal) {
      if (!officeClosed && !officePaused) {
        officeTextProgress();
        if (shouldReveal !== false) officeRequestReveal();
        stepTo(finding.active + steps);
      }
    },
    goto: function (n, options) {
      if (officeClosed || officePaused || !doc || !(n >= 1 && n <= slots.length)) return;
      var velocity = officePageMotion ? officePageMotion.velocity : null;
      officeCancelNavigation();
      var target = slots[n - 1];
      officeNavigationTarget = target;
      var textNavigation = officeTextNavigation = { epoch: officeNavigationEpoch };
      var placed = place(target, Object.assign({}, options, { velocity: velocity })).finally(function () {
        if (officeTextNavigation === textNavigation) officeTextNavigation = null;
        officeScheduleTextDrain();
      });
      want(target);pump();
      return placed.then(function () {
        if (options?.isCurrent && !options.isCurrent()) return;
        reportPage();scheduleRefine();
      });
    },
    position: function () {
      if (!doc || !slots.length || !PAGE_PITCH) return 0;
      var vv = window.visualViewport;
      askedFor = officeRetainPageRequest(askedFor);
      return askedFor ? askedFor.index : (vv ? pageOnScreen(vv) : 1) - 1;
    }



















  });
  window.officePdfModuleReady();
}

/* Modifications Copyright (C) 2026 Ettacent.
 * Derived from Mozilla PDF.js PDFFindController v6.3.289 (Apache-2.0). */
var officeRevealIntent = false;
var officeRevealTarget = null;
var officeRevealPageMoved = false;
var officeRevealMoves = 0;

function officeRequestReveal() {
  officeRevealIntent = true;officeRevealTarget = null;officeRevealPageMoved = false;
}
function officeCancelReveal() {
  officeRevealIntent = false;officeRevealTarget = null;officeRevealPageMoved = false;
}
function officeRevealBounds() {
  return window.officePdfVisibleBounds ? window.officePdfVisibleBounds() : {
    top: 0, bottom: innerHeight, left: 0, right: innerWidth, behavior: 'instant'
  };
}
function officeScrollRect(rect) {
  var bounds = officeRevealBounds();
  var bottom = Number.isFinite(rect.bottom) ? rect.bottom : rect.top + rect.height;
  var right = Number.isFinite(rect.right) ? rect.right : rect.left + rect.width;
  var dy = 0,dx = 0;
  if (rect.top < bounds.top - 1 || bottom > bounds.bottom + 1) {
    dy = rect.height > bounds.bottom - bounds.top || rect.top < bounds.top ?
    rect.top - bounds.top : bottom - bounds.bottom;
  }
  if (rect.left < bounds.left - 1 || right > bounds.right + 1) {
    dx = rect.width > bounds.right - bounds.left || rect.left < bounds.left ?
    rect.left - bounds.left : right - bounds.right;
  }
  if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
    officeRevealMoves++;
    window.scrollBy({ left: dx, top: dy, behavior: bounds.behavior });
  }
}

function officeStepTo(i) {
  if (officeClosed || officePaused) return;
  var n = finding.matches.length;
  if (!n) {finding.active = -1;paint();report();return;}
  finding.active = (i % n + n) % n;
  var match = finding.matches[finding.active],slot = slots[match.page - 1];
  if (officeRevealIntent) officeRevealTarget = match;
  want(slot);officeTextProgress(slot);pump();paint();
  if (officeRevealIntent && !activeRanges.length && !officeRevealPageMoved) {
    var rect = officePageRect(slot),bounds = officeRevealBounds();
    if (rect.bottom <= bounds.top || rect.top >= bounds.bottom || rect.right <= bounds.left || rect.left >= bounds.right) {
      officeRevealPageMoved = true;officeScrollRect(rect);
    }
  }
  reveal();report();
}

function officeReveal() {
  if (officeClosed || officePaused || !officeRevealIntent || !activeRanges.length ||
  finding.matches[finding.active] !== officeRevealTarget) return;
  var rect = activeRanges[0].getBoundingClientRect();
  if (!rect.height && !rect.width) return;
  officeRevealIntent = false;
  officeRevealTarget = null;
  officeScrollRect(rect);
}

var inFlight = 0;

var tileQueue = [];

var officeRasterTasks = [];
var officeRenderJobs = [];
var officeRenderTurn = null;
var officeRenderPumping = false;

function officeRenderMoving() {
  return officeDidReady && officePageMotion && !officePageMotion.done;
}

function officeRenderCancelled() {
  var error = new Error("Inactive render phase");
  error.name = "RenderingCancelledException";
  return error;
}

function officeRenderCurrent(entry) {
  return entry.live && !officeClosed && !officePaused && entry.current();
}

function officeReleaseRenderTurn(turn) {
  if (officeRenderTurn !== turn) return;
  officeRenderTurn = null;
  officePumpRender();
}

function officeRunRenderPhase(job) {
  if (!officeRenderCurrent(job.entry)) {

    job.reject(officeRenderCancelled());return;
  }


  try {job.resolve(job.run());} catch (error) {job.reject(error);}

}

function officePumpRender() {
  if (officeRenderTurn || officeRenderPumping) return;
  officeRenderPumping = true;
  try {
    while (officeRenderJobs.length) {
      var job = officeRenderJobs.shift(),entry = job.entry;
      if (job.raster) entry.queued = false;
      if (!officeRenderCurrent(entry)) {
        if (!job.raster) {job.reject(officeRenderCancelled());}
        if (entry.live) entry.task.cancel();
        continue;
      }
      if (!officeRenderMoving()) {
        if (job.raster) job.resume();else officeRunRenderPhase(job);
        continue;
      }
      var turn = officeRenderTurn = { job: job, frame: 0, timer: 0 };
      if (job.raster) {
        job.resume();
        turn.frame = requestAnimationFrame(function () {officeReleaseRenderTurn(turn);});
      } else {
        turn.frame = requestAnimationFrame(function () {
          if (officeRenderTurn !== turn) return;
          turn.frame = 0;
          turn.timer = setTimeout(function () {
            if (officeRenderTurn !== turn) return;
            turn.timer = 0;
            officeRunRenderPhase(job);
            turn.job = null;
            officeReleaseRenderTurn(turn);
          }, 0);
        });
      }
      return;
    }
  } finally {officeRenderPumping = false;}
}

function officeForgetRaster(entry) {
  if (!entry || !entry.live) return;

  entry.live = false;
  officeNightCancel(entry);
  var i = officeRasterTasks.indexOf(entry);
  if (i >= 0) officeRasterTasks.splice(i, 1);
  officeRenderJobs = officeRenderJobs.filter(function (job) {
    if (job.entry !== entry) return true;
    if (!job.raster) {job.reject(officeRenderCancelled());}
    return false;
  });
  entry.queued = false;
  if (entry.task.onContinue === entry.onContinue) entry.task.onContinue = null;
  if (entry.task.onError === entry.forget) entry.task.onError = null;
  var turn = officeRenderTurn;
  if (turn && turn.job && turn.job.entry === entry && !turn.job.raster) {

    turn.job.reject(officeRenderCancelled());
    cancelAnimationFrame(turn.frame);
    clearTimeout(turn.timer);
    turn.job = null;turn.timer = 0;
    turn.frame = requestAnimationFrame(function () {officeReleaseRenderTurn(turn);});
  }
  if (!officeRasterTasks.length && turn) {
    cancelAnimationFrame(turn.frame);
    clearTimeout(turn.timer);
    officeRenderTurn = null;
  }
  officePumpRender();
}

function officeScheduleRaster(task, current) {
  var entry = { task: task, current: current, live: true, queued: false };
  entry.forget = function () {officeForgetRaster(entry);};
  entry.onContinue = function (resume) {
    if (!entry.live) return;
    if (!officeRenderCurrent(entry)) {task.cancel();return;}
    if (entry.queued) return;
    entry.queued = true;
    officeRenderJobs.push({ entry: entry, raster: true, resume: resume });
    officePumpRender();
  };
  officeRasterTasks.push(entry);
  task.onContinue = entry.onContinue;
  task.onError = entry.forget;
  task.promise.then(function () {}, entry.forget);
  return entry;
}

function officeRenderPhase(entry, run) {
  return new Promise(function (resolve, reject) {
    var job = { entry: entry, run: run, resolve: resolve, reject: reject };
    if (!officeRenderMoving() && !officeRenderTurn) officeRunRenderPhase(job);else
    {officeRenderJobs.push(job);officePumpRender();}
  });
}


var OFFICE_TEXT_DEFER_MS = 1200;
var officeTextQueue = [];
var officeTextActive = null;
var officeTextTimer = 0;
var officeTextNavigation = null;

function officeTextCurrent(job) {
  var slot = job.slot;
  return job.live && slot.textJob === job && slot.textEpoch === job.epoch &&
  !officeClosed && !officePaused && slot.page === job.page &&
  slot.mode === job.mode && slot.palette === job.palette &&
  job.mode === inverted && job.palette === officePaletteEpoch;
}

function officeTextBlocked() {
  return officeDidReady && (!!officeTextNavigation || !!(officePageMotion && !officePageMotion.done));
}

function officeScheduleTextDrain() {
  clearTimeout(officeTextTimer);officeTextTimer = 0;
  if (officeClosed || officePaused || officeTextActive || !officeTextQueue.length) return;
  var delay = 0;
  if (officeTextBlocked()) delay = Math.max(0, Math.min.apply(null,
  officeTextQueue.map(function (job) {return job.deadline;})) - performance.now());
  officeTextTimer = setTimeout(function () {officeTextTimer = 0;officeDrainText();}, delay);
}

function officeFinishText(job, completed) {
  if (!job.live) return;

  job.live = false;
  clearTimeout(job.padTimer);
  clearTimeout(job.turnTimer);cancelAnimationFrame(job.turnFrame);
  if (job.turnReject) {job.turnReject(officeRenderCancelled());job.turnReject = null;}
  if (job.padReject) {job.padReject(officeRenderCancelled());job.padReject = null;}
  var i = officeTextQueue.indexOf(job);
  if (i >= 0) officeTextQueue.splice(i, 1);
  if (officeTextActive === job) officeTextActive = null;
  if (job.slot.textJob === job) {
    job.slot.textJob = null;
    if (completed) job.slot.textReady = true;
  }
  job.resolve();
  officeScheduleTextDrain();
  if (completed && !officeClosed && !officePaused && finding.query) {paint();reveal();}
}

function officeCancelText(slot) {
  var job = slot.textJob;
  slot.textEpoch = (slot.textEpoch || 0) + 1;
  if (!job) return;
  slot.textJob = null;
  slot.textReady = false;
  if (slot.layer === job.layer) {
    slot.layer = null;
    var text = slot.text;
    if (job.layer) {
      var replacement = text.cloneNode(false);
      text.replaceWith(replacement);
      slot.text = replacement;
      job.layer.cancel();
    }
    text.replaceChildren();
  }
  officeFinishText(job, false);
}

function officeDiscardText(job) {
  if (job.slot.textJob === job) officeCancelText(job.slot);else
  officeFinishText(job, false);
}

function officeStartText(job) {
  if (!officeTextCurrent(job)) {officeDiscardText(job);return;}
  try {
    Promise.resolve(textLayerFor(job.slot, job.page, job.viewport, job)).then(function () {
      if (officeTextCurrent(job)) officeFinishText(job, true);else
      if (job.live) officeFinishText(job, false);
    }, function () {
      if (officeTextCurrent(job)) {
        if (job.slot.layer === job.layer) {job.slot.layer = null;job.slot.text.replaceChildren();}
        officeFinishText(job, true);
      } else if (job.live) officeFinishText(job, false);
    });
  } catch (_) {
    if (officeTextCurrent(job)) officeFinishText(job, true);else
    officeFinishText(job, false);
  }
}

function officeDrainText() {
  if (officeClosed || officePaused || officeTextActive) return;
  var now = performance.now();
  for (var i = 0; i < officeTextQueue.length; i++) {
    var job = officeTextQueue[i];
    if (!officeTextCurrent(job)) {officeDiscardText(job);i--;continue;}
    if (officeTextBlocked() && job.deadline > now) continue;
    officeTextQueue.splice(i, 1);
    officeTextActive = job;
    officeStartText(job);
    return;
  }
  officeScheduleTextDrain();
}

function officeQueueText(slot, page, viewport) {
  if (slot.textReady) return Promise.resolve();
  if (slot.textJob) return slot.textJob.promise;
  var job = { slot: slot, page: page, viewport: viewport, mode: slot.mode, palette: slot.palette,
    epoch: slot.textEpoch || 0, live: true, layer: null, padTimer: 0, padReject: null,
    deadline: performance.now() + OFFICE_TEXT_DEFER_MS };
  slot.textEpoch = job.epoch;
  job.promise = new Promise(function (resolve) {job.resolve = resolve;});
  slot.textJob = job;
  if (!officeDidReady) officeStartText(job);else
  {officeTextQueue.push(job);officeScheduleTextDrain();}
  return job.promise;
}

function officeTextPadding(job, run) {
  if (!officeDidReady) {run();return Promise.resolve();}
  return officeTextTurn(job, true).then(function () {
    if (!officeTextCurrent(job)) throw officeRenderCancelled();
    return run();
  });
}

function officeTextTurn(job, afterPaint) {
  if (!officeTextCurrent(job)) return Promise.reject(officeRenderCancelled());
  if (!officeDidReady) return Promise.resolve();
  var now = performance.now();
  if (!afterPaint && (!officeTextBlocked() || now >= job.deadline) && now < job.turnBudgetUntil) return Promise.resolve();
  return new Promise(function (resolve, reject) {
    job.turnReject = reject;
    function finish() {
      job.turnTimer = job.turnFrame = 0;
      job.turnReject = null;
      if (!officeTextCurrent(job)) {reject(officeRenderCancelled());return;}
      job.turnBudgetUntil = performance.now() + 2;
      resolve();
    }
    function attempt() {
      job.turnTimer = 0;
      if (!officeTextCurrent(job)) {finish();return;}
      if (officeTextBlocked() && performance.now() < job.deadline) {
        job.turnTimer = setTimeout(attempt, Math.min(32, Math.max(1, job.deadline - performance.now())));
        return;
      }
      job.turnFrame = requestAnimationFrame(function () {
        job.turnFrame = 0;
        job.turnTimer = setTimeout(function () {
          if (officeTextCurrent(job) && officeTextBlocked() && performance.now() < job.deadline) attempt();else
          finish();
        }, 0);
      });
    }
    attempt();
  });
}

function officeTextProgress(slot) {
  var now = performance.now();
  officeTextQueue.forEach(function (job) {if (!slot || job.slot === slot) job.deadline = now;});
  if (officeTextActive && (!slot || officeTextActive.slot === slot)) officeTextActive.deadline = now;
  officeScheduleTextDrain();
}

["copy", "selectstart"].forEach(function (type) {
  document.addEventListener(type, function () {officeTextProgress();}, { capture: true, passive: true });
});

var finding = {
  query: "",
  pages: null,
  matches: [],
  active: -1,
  building: false,
  capped: false,
  built: 0
};

function shape(box, unscaled) {
  var ratio = String(unscaled.width / unscaled.height);
  if (box._officePageRatio !== ratio) {
    box.style.aspectRatio = ratio;
    box._officePageRatio = ratio;
    officeLayoutChanged();
  }
}

function distance(slot) {
  var r = slot.canvas.getBoundingClientRect();
  return Math.abs(r.top + r.height / 2 - window.innerHeight / 2);
}

function want(slot) {
  if (!officeClosed && !officePaused && slot.state === "drawn" && !slot.textReady &&
  !slot.textJob && slot.page && slot.unscaled) {
    officeQueueText(slot, slot.page, slot.unscaled.clone({ scale: PAGE_WIDTH / slot.unscaled.width }));
  }
  if (officeClosed || officePaused || slot.state !== "blank" &&
  !(slot.state === "drawn" && officeBaseNeedsRefresh(slot))) return;
  if (officePreviews.has(slot)) {
    var p = officePreviews.get(slot);officePreviews.delete(slot);officePreviews.set(slot, p);

  }
  slot.state = "queued";
  queue.push(slot);
  if (queue.length > 12) {
    queue.sort(function (a, b) {return distance(a) - distance(b);});
    officeAbandonBaseWork(queue.pop());
  }
}

function unwant(slot) {
  if (!slot.baseComplete) officeCancelText(slot);
  if (slot.state === "queued") {
    var i = queue.indexOf(slot);
    if (i >= 0) queue.splice(i, 1);
    officeAbandonBaseWork(slot);
  } else if (slot.state === "drawing") {
    slot.navigationCancelled = true;
    if (slot.task) slot.task.cancel();
  }
}

function release(slot) {
  if (slot.state === "queued" && slot.canvas.width > 0) unwant(slot);
  if (slot.state !== "drawn") return;
  blank(slot);
}

function blank(slot) {
  var previewKept = officePreviewRetain(slot);
  officeCancelText(slot);
  slot.textReady = false;

  {
    if (slot.workCanvas) {officeNightReleaseCanvas(slot.workCanvas);slot.workCanvas = null;}
    if (!previewKept) {slot.canvas.width = 0;slot.canvas.height = 0;}
  }
  slot.state = "blank";
  slot.baseComplete = false;
  slot.unscaled = null;
  if (!previewKept) slot.mode = null;
  slot.pictures = null;

  dropTile(slot);

  if (slot.layer) {
    slot.layer.cancel();
    slot.layer = null;
  }
  slot.text.replaceChildren();

  if (slot.page) {
    slot.page.cleanup();
    slot.page = null;
  }
}

function pump() {
  if (officeClosed || officePaused || !doc) return;

  {
    while (inFlight < officeNightLimit()) {
      var bounds = window.officePdfVisibleBounds();
      var middle = (bounds.top + bounds.bottom) / 2;
      var next = -1,best = Infinity,visible = false;
      for (var i = 0; i < queue.length; i++) {
        var rect = officePageRect(queue[i]);
        var showing = rect.bottom > bounds.top && rect.top < bounds.bottom &&
        rect.right > bounds.left && rect.left < bounds.right;
        if (visible && !showing) continue;
        var d = Math.abs(rect.top + rect.height / 2 - middle);
        if (showing && !visible || d < best) {
          next = i;best = d;visible = showing;
        }
      }
      if (tileQueue.length && !visible) {
        try {drawTile(tileQueue.shift());} catch (e) {window.officePdfFailure(e);return;}
        continue;
      }
      if (next < 0) return;
      draw(queue.splice(next, 1)[0]);
    }
  }
}

var inverted = !!window.vwNight;

function imageQuads(page, sx, ox, sy, oy) {
  var raw = page && page.imageCoordinates;
  if (!raw || !raw.length) return [];
  var out = [];
  for (var i = 0; i + 5 < raw.length; i += 6) {
    var ax = raw[i],ay = raw[i + 1];
    var bx = raw[i + 2],by = raw[i + 3];
    var cx = raw[i + 4],cy = raw[i + 5];
    if (!(Math.abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) > 0)) continue;

    var pax = Math.round(ax * sx + ox),pay = Math.round(ay * sy + oy);
    var pbx = Math.round(bx * sx + ox),pby = Math.round(by * sy + oy);
    var pcx = Math.round(cx * sx + ox),pcy = Math.round(cy * sy + oy);
    out.push([
    pax, pay,
    pbx, pby,
    pbx + pcx - pax, pby + pcy - pay,
    pcx, pcy]
    );
  }
  return out;
}


function recolour(ctx, keep) {
  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalAlpha = 1;
  ctx.globalCompositeOperation = "source-over";
  for (var i = 0; i < keep.length; i++) {
    var q = keep[i];
    ctx.beginPath();
    ctx.rect(0, 0, ctx.canvas.width, ctx.canvas.height);
    ctx.moveTo(q[0], q[1]);
    ctx.lineTo(q[2], q[3]);
    ctx.lineTo(q[4], q[5]);
    ctx.lineTo(q[6], q[7]);
    ctx.closePath();
    ctx.clip("evenodd");
  }
  ctx.filter = "url(#vw-invert)";
  ctx.drawImage(ctx.canvas, 0, 0);
  ctx.restore();
}

function setNight(on) {
  if (officeClosed) return;
  if (on === inverted) return;
  inverted = on;
  officePreviewClear();
  officeNightPipe.cancel();
  document.documentElement.classList.toggle("vw-night", on);
  for (var i = 0; i < slots.length; i++) {
    var s = slots[i];
    if (s.state === "drawn" && s.mode !== inverted) {blank(s);want(s);}
  }
  pump();
}

function draw(slot) {
  if (officeClosed || officePaused) return Promise.resolve();

  var replacing = !!slot.baseComplete;
  var rasterEntry = null,nightLease = null,nightOutput = null;
  slot.state = "drawing";
  slot.navigationCancelled = false;
  inFlight++;
  var mode = inverted;
  var paletteEpoch = officePaletteEpoch;
  var preserveImages = officePreserveImages;
  return doc.getPage(slot.n).then(function (page) {
    slot.page = page;
    officeGuard(slot);

    var unscaled = page.getViewport({ scale: 1 });
    shape(slot.box, unscaled);
    slot.unscaled = unscaled;

    var cssScale = PAGE_WIDTH / unscaled.width;
    var scale = cssScale * baseScale();
    scale *= within(unscaled.width * scale, unscaled.height * scale, BASE_MAX_PIXELS);
    var viewport = unscaled.clone({ scale: scale });
    var canvas = officeTrackCanvas(slot, document.createElement("canvas"));

    {

      nightLease = officeNightClaim(Math.floor(viewport.width), Math.floor(viewport.height), slot, mode, canvas);
    }
    slot.workCanvas = canvas;

    {
      canvas.width = viewport.width;
      canvas.height = viewport.height;
      var ctx = canvas.getContext("2d");
    }

    {
      slot.task = page.render({
        canvasContext: ctx,
        viewport: viewport,
        recordImages: mode
      });

    }
    var rasterTask = slot.task;
    rasterEntry = officeScheduleRaster(rasterTask, function () {
      return slot.task === rasterTask && !slot.navigationCancelled;
    });
    return slot.task.promise.then(function () {
      return officeNightPrepare(nightLease, rasterEntry, canvas, { kind: "base", slot: slot,
        preserveImages: preserveImages, quads: imageQuads(page, canvas.width, 0, canvas.height, 0) }, function () {
        return officeRenderCurrent(rasterEntry) && slot.task === rasterTask && slot.workCanvas === canvas &&
        mode === inverted && paletteEpoch === officePaletteEpoch;
      });
    }).then(function (output) {
      nightOutput = output;
      return officeRenderPhase(rasterEntry, function () {
        officeGuard(slot);
        if (officeCanvasLost(canvas, ctx)) throw officeRenderCancelled();
        if (mode !== inverted || paletteEpoch !== officePaletteEpoch) {
          slot.mode = mode;slot.palette = paletteEpoch;return;
        }
        if (mode && nightOutput && !nightOutput.classificationOnly) {
          slot.pictures = nightOutput.pictures;
        } else if (mode) {
          var quads = imageQuads(page, canvas.width, 0, canvas.height, 0);
          if (nightOutput && nightOutput.classificationOnly) {
            slot.pictures = nightOutput.pictures;
          } else {
            var sample = null;
            if (preserveImages && quads.length) {

              try {sample = pageSample(ctx, quads);} catch (_) { }

            }

            {
              slot.pictures = quads.map(function (q) {return !looksLikeDiagram(sample, q);});

            }
          }

          {
            recolour(ctx, preserveImages ? quads.filter(function (q, i) {return slot.pictures[i];}) : []);

          }
        }

        {
          if (nightOutput && !nightOutput.classificationOnly) nightOutput.commit(ctx);
          var oldCanvas = slot.canvas;

          {
            officeFinishReveals(slot);
            oldCanvas.classList.add("pdf-retiring");
            oldCanvas.setAttribute("aria-hidden", "true");
            oldCanvas.after(canvas);
          }
          officePreviewForget(slot);
          slot.canvas = canvas;slot.workCanvas = null;
          officePresent(slot, canvas, { canvas: oldCanvas });
          slot.mode = mode;slot.palette = paletteEpoch;

        }
      });
    }).finally(function () {
      if (nightOutput) {nightOutput.dispose();nightOutput = null;}
    }).then(function () {
      if (replacing || slot.canvas !== canvas) return;
      officeGuard(slot);
      if (mode !== inverted || paletteEpoch !== officePaletteEpoch) return;
      var textDone = officeQueueText(slot, page, unscaled.clone({ scale: cssScale }));
      if (!officeDidReady) return textDone;
    }).then(function () {
      if (!officeRenderCurrent(rasterEntry)) throw officeRenderCancelled();
    });
  }).then(function () {
    if (!officeRenderCurrent(rasterEntry)) {officeAbandonBaseWork(slot);return;}
    slot.state = "drawn";
    slot.baseComplete = true;

    if (slot.mode !== inverted || slot.palette !== officePaletteEpoch) {

      blank(slot);
      want(slot);
      return;
    }
    if (finding.query) {paint();reveal();}
    officeMaybeReady();
    scheduleRefine();
  }, function (error) {

    if (!officeClosed && !officePaused && error && error.name !== "RenderingCancelledException") window.officePdfFailure(error);
    officeAbandonBaseWork(slot);
  }).finally(function () {

    officeForgetRaster(rasterEntry);
    slot.task = null;
    officeNightRetire(nightLease);
    inFlight--;
    if (!officeClosed && !officePaused) {
      officeVisible();
      officeContinueInitialPage();
    }
    pump();
  }).catch(function (e) {window.officePdfFailure(e);});
}


var TILE_GATE = 1.25;

var TILE_MARGIN = 0.2;

var TILE_MAX_PIXELS = 3e6;

var MAX_TILES = 2;

var TILE_SETTLE_MS = 150;

function shed(tile) {
  if (!tile) return;

  {
    var task = tile.task;
    tile.task = null;
    if (task) task.cancel();
    if (tile.canvas.parentNode) tile.canvas.parentNode.removeChild(tile.canvas);
    officeNightReleaseCanvas(tile.canvas);
  }
}

function dropTile(slot) {
  officeFinishReveals(slot);
  shed(slot.pending);
  shed(slot.tile);
  slot.pending = null;
  slot.tile = null;
}

function drawTile(req) {
  var slot = req.slot,nightLease = null,nightOutput = null;
  try {
    if (slot.state !== "drawn" || !slot.page || !slot.unscaled) return;


    {
      officeFinishReveals(slot);
      shed(slot.pending);
    }
    var mode = inverted;
    var paletteEpoch = officePaletteEpoch;
    var preserveImages = officePreserveImages;
    var canvas = officeTrackCanvas(slot, document.createElement("canvas"));
    canvas.className = "tile";
    canvas.setAttribute("aria-hidden", "true");

    {

      nightLease = officeNightClaim(req.cw, req.ch, slot, mode, canvas);
    }

    {
      canvas.width = req.cw;
      canvas.height = req.ch;

      var tile = {
        canvas: canvas,
        x: req.x, y: req.y, w: req.w, h: req.h, k: req.k, task: null
      };
      slot.pending = tile;

      var cssScale = PAGE_WIDTH / slot.unscaled.width;
      var ctx = canvas.getContext("2d");
    }

    {
      tile.task = slot.page.render({
        canvasContext: ctx,
        recordImages: mode,
        viewport: slot.unscaled.clone({ scale: cssScale * req.k }),
        transform: [1, 0, 0, 1, -req.x * req.k, -req.y * req.k]
      });

    }
    var rasterTask = tile.task;
    var rasterEntry = officeScheduleRaster(rasterTask, function () {
      return slot.pending === tile && tile.task === rasterTask;
    });
    var rasterComplete = false;

    inFlight++;

    tile.task.promise.then(function () {
      rasterComplete = true;
      return officeNightPrepare(nightLease, rasterEntry, canvas, { kind: "tile", slot: slot,
        preserveImages: preserveImages, pictures: slot.pictures || null,
        quads: imageQuads(slot.page, req.boxW * req.k, -req.x * req.k, req.boxH * req.k, -req.y * req.k) }, function () {
        return officeRenderCurrent(rasterEntry) && slot.pending === tile && tile.task === rasterTask &&
        mode === inverted && paletteEpoch === officePaletteEpoch;
      });
    }).then(function (output) {
      nightOutput = output;
      return officeRenderPhase(rasterEntry, function () {
        if (officeClosed || officePaused || slot.pending !== tile) {shed(tile);return;}
        if (officeCanvasLost(canvas, ctx)) throw officeRenderCancelled();
        if (mode !== inverted || paletteEpoch !== officePaletteEpoch) {shed(tile);return;}
        if (mode && !nightOutput) {
          var seen = slot.pictures;

          {
            recolour(ctx, imageQuads(
              slot.page,
              req.boxW * req.k, -req.x * req.k,
              req.boxH * req.k, -req.y * req.k
            ).filter(function (q, i) {
              if (!preserveImages) return false;
              return !seen || seen[i];
            }));

          }
        }

        {
          if (nightOutput) nightOutput.commit(ctx);
          tile.task = null;slot.pending = null;
          canvas.style.left = String(req.x / req.boxW * 100) + "%";
          canvas.style.top = String(req.y / req.boxH * 100) + "%";
          canvas.style.width = String(req.w / req.boxW * 100) + "%";
          canvas.style.height = String(req.h / req.boxH * 100) + "%";
          slot.box.insertBefore(canvas, slot.text);
          var previous = slot.tile;
          slot.tile = tile;
          officePresent(slot, canvas, previous);


        }
      });
    }).finally(function () {
      if (nightOutput) {nightOutput.dispose();nightOutput = null;}
    }).catch(function (error) {

      var retryLost = slot.pending === tile && officeCanvasLost(canvas, ctx);
      tile.task = null;
      if (slot.pending === tile) slot.pending = null;
      shed(tile);
      if (retryLost) scheduleRefine();
      if (rasterComplete && error && error.name !== "RenderingCancelledException") throw error;
    }).finally(function () {

      officeForgetRaster(rasterEntry);
      officeNightRetire(nightLease);
      inFlight--;
      officeVisible();
      pump();
    }).catch(function (e) {window.officePdfFailure(e);});
  } catch (error) {
    if (slot.pending === tile) slot.pending = null;
    shed(tile);officeNightRetire(nightLease);throw error;
  }
}

function refine() {
  if (officeClosed || officePaused || !doc || !slots.length) return;
  var vv = window.visualViewport;
  if (!vv || !(vv.width > 0) || !(vv.height > 0)) return;

  tileQueue.length = 0;

  var k = liveScale();
  var i;
  if (!(k > baseScale() * TILE_GATE)) {
    for (i = 0; i < slots.length; i++) dropTile(slots[i]);
    return;
  }

  var top = vv.offsetTop;
  var bottom = top + vv.height;
  var left = vv.offsetLeft;
  var right = left + vv.width;
  var middle = top + vv.height / 2;

  var lo = 0,hi = slots.length - 1,from = slots.length;
  while (lo <= hi) {
    var mid = lo + hi >> 1;
    if (officePageRect(slots[mid]).bottom > top) {
      from = mid;hi = mid - 1;
    } else {
      lo = mid + 1;
    }
  }

  var showing = [];
  for (i = from; i < slots.length; i++) {
    var r = officePageRect(slots[i]);
    if (r.top >= bottom) break;
    showing.push({
      slot: slots[i], box: r,
      away: Math.abs(r.top + r.height / 2 - middle)
    });
  }
  showing.sort(function (a, b) {return a.away - b.away;});
  var chosen = showing.slice(0, MAX_TILES);

  for (i = 0; i < slots.length; i++) {
    var keep = false;
    for (var c = 0; c < chosen.length; c++) if (chosen[c].slot === slots[i]) keep = true;
    if (!keep) dropTile(slots[i]);
  }

  for (i = 0; i < chosen.length; i++) {
    var slot = chosen[i].slot;
    var box = chosen[i].box;
    if (slot.state !== "drawn" || !slot.unscaled) {dropTile(slot);continue;}
    if (officeLostCanvases.has(slot.canvas)) {dropTile(slot);continue;}
    if (slot.tile && officeLostCanvases.get(slot.tile.canvas) === false) continue;

    var vx = Math.max(box.left, left) - box.left;
    var vy = Math.max(box.top, top) - box.top;
    var vw = Math.min(box.right, right) - box.left - vx;
    var vh = Math.min(box.bottom, bottom) - box.top - vy;
    if (!(vw > 0) || !(vh > 0)) {dropTile(slot);continue;}

    var room = Math.sqrt(TILE_MAX_PIXELS / (vw * k * vh * k));
    var m = Math.max(0, Math.min(TILE_MARGIN, (room - 1) / 2));
    var x = Math.max(0, vx - vw * m);
    var y = Math.max(0, vy - vh * m);
    var w = Math.min(box.width, vx + vw + vw * m) - x;
    var h = Math.min(box.height, vy + vh + vh * m) - y;

    var tk = k * within(w * k, h * k, TILE_MAX_PIXELS);

    var px = Math.max(0, Math.floor(x * tk));
    var py = Math.max(0, Math.floor(y * tk));
    var cw = Math.min(Math.ceil((x + w) * tk), Math.floor(box.width * tk)) - px;
    var ch = Math.min(Math.ceil((y + h) * tk), Math.floor(box.height * tk)) - py;
    if (!(cw > 0) || !(ch > 0)) {dropTile(slot);continue;}
    x = px / tk;y = py / tk;w = cw / tk;h = ch / tk;

    if (covers(slot.tile, vx, vy, vw, vh, tk) ||
    covers(slot.pending, vx, vy, vw, vh, tk)) continue;

    tileQueue.push({
      slot: slot, x: x, y: y, w: w, h: h, cw: cw, ch: ch, k: tk,
      boxW: box.width, boxH: box.height
    });
  }

  pump();
}

function covers(tile, vx, vy, vw, vh, k) {
  if (!tile || officeLostCanvases.has(tile.canvas)) return false;
  if (Math.abs(tile.k - k) > tile.k * 0.15) return false;
  return tile.x <= vx + 0.5 && tile.y <= vy + 0.5 &&
  tile.x + tile.w >= vx + vw - 0.5 && tile.y + tile.h >= vy + vh - 0.5;
}

var tileTimer = 0;
function scheduleRefine() {
  if (officeClosed || officePaused) return;
  if (tileTimer) clearTimeout(tileTimer);
  tileTimer = setTimeout(function () {tileTimer = 0;refine();}, TILE_SETTLE_MS);
}

function textLayerFor(slot, page, viewport, textJob) {
  if (!officeTextCurrent(textJob)) throw officeRenderCancelled();
  if (document.documentElement.classList.contains("office-protected")) slot.text.setAttribute("aria-hidden", "true");
  slot.text.style.setProperty(
    "--total-scale-factor", String(viewport.scale * (viewport.userUnit || 1)));
  var layer = new pdfjsLib.TextLayer({
    textContentSource: embedFonts(page.streamTextContent({ disableNormalization: true }), textJob),
    container: slot.text,
    viewport: viewport
  });
  slot.layer = layer;
  textJob.layer = layer;
  return layer.render().then(function () {
    return officeTextPadding(textJob, function () {
      if (!officeTextCurrent(textJob)) throw officeRenderCancelled();
      if (slot.layer !== layer) throw officeRenderCancelled();
      padRows(slot.text, layer.textDivs);
    });
  }, function () {
    if (officeTextCurrent(textJob) && slot.layer === layer) {
      slot.layer = null;
      slot.text.replaceChildren();
    }
  });
}


function byTopThenLeft(a, b) {return a.top - b.top || a.left - b.left;}
function byLeft(a, b) {return a.left - b.left;}

function padRows(container, divs) {

  {
    var layer = container.getBoundingClientRect();
    var mfs = parseFloat(container.style.getPropertyValue("--min-font-size")) || 1;

    var boxes = [];
    for (var i = 0; i < divs.length; i++) {
      var el = divs[i];
      var rot = el.style.getPropertyValue("--rotate");
      if (rot && parseFloat(rot)) continue;
      var r = el.getBoundingClientRect();
      if (!r.height) continue;
      boxes.push({
        el: el, top: r.top, bottom: r.bottom, left: r.left, right: r.right,
        sx: (parseFloat(el.style.getPropertyValue("--scale-x")) || 1) / mfs
      });
    }
    if (boxes.length < 2) return;
    boxes.sort(byTopThenLeft);

    var rows = [];
    var row = null;
    for (var j = 0; j < boxes.length; j++) {
      var b = boxes[j];
      if (!row || b.top - row.top > 2) {
        row = { top: b.top, bottom: b.bottom, items: [b] };
        rows.push(row);
      } else {
        row.bottom = Math.max(row.bottom, b.bottom);
        row.items.push(b);
      }
    }

    for (var v = 0; v < rows.length; v++) {
      var here = rows[v];
      var up = v ? (here.top - rows[v - 1].bottom) / 2 : here.top - layer.top;
      var floor = v + 1 < rows.length ?
      here.bottom + (rows[v + 1].top - here.bottom) / 2 :
      layer.bottom;

      var across = here.items;
      across.sort(byLeft);

      for (var x = 0; x < across.length; x++) {
        var it = across[x];
        var style = it.el.style;
        var down = floor - it.bottom;
        if (down > 0.5) style.paddingBottom = (down * mfs).toFixed(2) + "px";
        if (up > 0.5) {
          style.paddingTop = (up * mfs).toFixed(2) + "px";
          style.marginTop = (-up).toFixed(2) + "px";
        }
        var until = x + 1 < across.length ? across[x + 1].left : layer.right;
        var reach = until - it.right;
        if (reach > 1) style.paddingRight = (reach / it.sx).toFixed(2) + "px";
      }

      var first = across[0];
      var leftNeed = first.left - layer.left;
      if (leftNeed > 1) {
        first.el.style.paddingLeft = (leftNeed / first.sx).toFixed(2) + "px";
        first.el.style.marginLeft = (-leftNeed).toFixed(2) + "px";
      }
    }
  }
}

var SELECT_EDGE_DP = 50;
var SELECT_EDGE_MIN = 10;
var SELECT_GAP_MS = 700;
var selectPrevY = null;
var selectPrevT = 0;

function extentRect(sel) {
  try {
    var r = document.createRange();
    r.setStart(sel.focusNode, sel.focusOffset);
    r.collapse(true);
    var rects = r.getClientRects();
    if (rects.length) return rects[0];
    if (sel.focusNode.nodeType === 1) return sel.focusNode.getBoundingClientRect();
  } catch (e) {}
  return null;
}

function selectionEdgeScroll() {
  if (officeClosed || officePaused || document.documentElement.classList.contains("office-protected")) return;
  var sel = window.getSelection();
  if (!sel || !sel.rangeCount || sel.isCollapsed) {selectPrevY = null;return;}
  var rect = extentRect(sel);
  if (!rect) {selectPrevY = null;return;}
  var vv = window.visualViewport;
  if (!vv || !vv.height) return;

  var y = rect.bottom + window.scrollY;
  var now = performance.now();
  var dragging = selectPrevY !== null && now - selectPrevT < SELECT_GAP_MS;
  var travel = dragging ? y - selectPrevY : 0;
  selectPrevY = y;
  selectPrevT = now;
  if (!dragging) return;

  var band = SELECT_EDGE_DP / vv.scale;
  var low = vv.offsetTop + vv.height - band;
  var high = vv.offsetTop + band;
  if (travel >= -2 && rect.bottom > low) {
    window.scrollBy(0, Math.min(Math.max(rect.bottom - low, SELECT_EDGE_MIN), band));
  } else if (travel <= 2 && rect.top < high) {
    window.scrollBy(0, -Math.min(Math.max(high - rect.top, SELECT_EDGE_MIN), band));
  }
}

document.addEventListener("selectionchange", selectionEdgeScroll);

function embedFonts(stream, textJob) {
  if (typeof TransformStream !== "function" || !stream.pipeThrough) return stream;

  var families = null;

  return stream.pipeThrough(new TransformStream({
    transform: async function (chunk, controller) {

      {
        var styles = chunk && chunk.styles;
        for (var key in styles) {
          var style = styles[key];
          if (!style || !style.fontFamily) continue;
          if (!families) families = loadedFamilies();
          if (!families[key]) continue;
          style.fontFamily = '"' + key + '", ' + style.fontFamily;
        }
        if (!textJob || !officeDidReady || !Array.isArray(chunk.items) || !chunk.items.length) {
          if (textJob && !officeTextCurrent(textJob)) throw officeRenderCancelled();
          controller.enqueue(chunk);

          return;
        }
        for (var at = 0; at < chunk.items.length; at += 2) {
          await officeTextTurn(textJob);
          if (!officeTextCurrent(textJob)) throw officeRenderCancelled();
          controller.enqueue(Object.assign({}, chunk, { items: chunk.items.slice(at, at + 2) }));
        }

      }
    }
  }));
}

function loadedFamilies() {
  var families = Object.create(null);
  var set = document.fonts;
  if (!set || typeof set.forEach !== "function") return families;
  set.forEach(function (face) {
    if (face.status !== "loaded") return;
    var name = String(face.family || "").replace(/^(["'])([\s\S]*)\1$/, "$2");
    if (name) families[name] = true;
  });
  return families;
}


function buildIndex() {
  if (officeClosed || officePaused || !doc || finding.building) return;
  if (finding.pages && finding.built === doc.numPages) return;
  if (!finding.pages) finding.pages = new Array(doc.numPages);
  finding.building = true;
  var next = finding.built + 1,busy = false;
  function step() {
    officeIndexTimer = 0;
    if (officeClosed || officePaused || busy) return;
    if (next > doc.numPages) {finding.building = false;officeIndexResume = null;report();return;}
    busy = true;
    var n = next;

    doc.getPage(n).then(function (page) {
      return page.getTextContent({ disableNormalization: true }).then(function (content) {
        if (officeClosed) return;
        var text = officeSearch.extract(content.items);
        officeIndexBytes += text.bytes;
        if (officeIndexBytes > 8 * 1024 * 1024) {window.officePdfFailure(new Error('Text index budget'));return;}
        finding.pages[n - 1] = text;

        if (slots[n - 1] && slots[n - 1].state === 'blank') page.cleanup();
      });
    }).catch(function (error) {if (!officeClosed) window.officePdfFailure(error);}).then(function () {

      busy = false;
      if (officeClosed) return;
      finding.built = n;next = n + 1;
      if (finding.query) {
        var added = scanPage(n);
        if (!officePaused) {
          if (finding.active < 0 && finding.matches.length) stepTo(0);else
          {if (added && slots[n - 1].state === 'drawn') paint();report();}
        }
      }
      if (!officePaused) officeIndexTimer = setTimeout(step, 0);
    });
  }
  officeIndexResume = step;
  officeIndexTimer = setTimeout(step, 0);
}

function scanPage(n) {
  if (finding.capped || officeClosed || !finding.query || !finding.pages[n - 1]) return 0;
  var found = 0;

  {
    for (var match of officeSearch.matches(finding.pages[n - 1], finding.query)) {
      if (finding.matches.length >= OFFICE_MAX_MATCHES) {finding.capped = true;break;}
      finding.matches.push({ page: n, at: match.at, len: match.len });found++;
    }
    return found;
  }
}

function rerun() {
  finding.matches = [];finding.capped = false;
  if (finding.query && finding.pages) {
    for (var n = 1; n <= finding.pages.length; n++) scanPage(n);
  }
  if (finding.active >= finding.matches.length) {
    finding.active = finding.matches.length ? 0 : -1;
  }
  if (finding.active < 0 && finding.matches.length) finding.active = 0;
  paint();
  report();
}

var hitRanges = [];
var activeRanges = [];

function paint() {
  if (officeClosed || officePaused) return;
  if (!window.CSS || !CSS.highlights) return;

  {
    hitRanges = [];
    activeRanges = [];
    for (var i = 0; i < slots.length; i++) {
      if (slots[i].state === "drawn") markSlot(slots[i]);
    }
    flush();
  }
}

function flush() {
  if (!window.CSS || !CSS.highlights) return;
  if (hitRanges.length) CSS.highlights.set("vw-find", new Highlight(...hitRanges));else
  CSS.highlights.delete("vw-find");
  if (activeRanges.length) {
    CSS.highlights.set("vw-find-active", new Highlight(activeRanges[0]));
  } else {
    CSS.highlights.delete("vw-find-active");
  }
}

function markSlot(slot) {
  if (!finding.query || !slot.layer || !slot.textReady) return;
  var divs = slot.layer.textDivs,strs = slot.layer.textContentItemsStr;
  if (!divs || !strs || !divs.length) return;
  var retained = finding.matches.filter(function (m) {return m.page === slot.n;});
  if (!retained.length) return;
  try {
    var ranges = officeSearch.ranges(divs, strs, retained, document, finding.pages[slot.n - 1].glyphLength);
    for (var entry of ranges) {
      if (entry.match === finding.matches[finding.active]) activeRanges.push(entry.range);else
      hitRanges.push(entry.range);
    }
  } catch (error) {
    window.officePdfFailure(error);
  }
}

function stepTo(i) {officeStepTo(i);}
function reveal() {officeReveal();}

var searchPort = null;

function report() {
  if (officeClosed || officePaused) return;
  window.officePdfSearch(finding.matches.length, finding.matches.length ? finding.active : -1,
  !!finding.query && (!finding.pages || finding.building), !!finding.capped);
}

var lastPageSent = 0;

var locked = false;

function reportLocked() {
  if (searchPort && locked) searchPort.postMessage("locked");
}

function reportPage() {
  if (!officeClosed && !officePaused) window.officePdfMoved();
}

function pageOnScreen(vv) {
  if (vv.pageTop <= 1) return 1;
  if (vv.pageTop + vv.height >= officeDocumentExtent() - 1) {
    return slots.length;
  }
  var middle = vv.pageTop + vv.height / 2;

  var lo = 0,hi = slots.length - 1,at = 0;
  while (lo <= hi) {
    var mid = lo + hi >> 1;
    if (officePageRect(slots[mid]).top + window.scrollY <= middle) {
      at = mid;lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return at + 1;
}

var askedFor = null;
var officeNavigationEpoch = 0;
var officePageMotion = null;
var officeNavigationTarget = null;

function officeCancelNavigation() {
  askedFor = null;
  officeNavigationEpoch++;
  officeTextNavigation = null;
  officeScheduleTextDrain();
  if (officePageMotion) officePageMotion.finish(false);
  var target = officeNavigationTarget;
  officeNavigationTarget = null;
  if (target && (target.state === "queued" || target.state === "drawing")) {
    var rect = officePageRect(target),margin = Math.round(DRAW_PAGES * PAGE_PITCH);
    if (rect.bottom <= -margin || rect.top >= innerHeight + margin) {
      if (target.state === "drawing") target.navigationCancelled = true;
      unwant(target);
    }
  }
}

function officeRetainPageRequest(request) {
  var vv = window.visualViewport;
  if (!request || !vv || request.viewportScale !== vv.scale || Math.abs(request.x - vv.pageLeft) > 1) return null;
  var box = slots[request.index]?.box.getBoundingClientRect(),bounds = officeRevealBounds();
  if (!box || !(box.width > 0 && box.height > 0) || box.right <= bounds.left || box.left >= bounds.right ||
  box.bottom <= bounds.top || box.top >= bounds.bottom) return null;
  if (Math.abs(vv.pageTop - request.y) > 1) {
    var extent = officeDocumentExtent();
    var clamped = Math.max(0, Math.min(request.y, extent - vv.height));
    if (!(extent < request.extent && vv.height === request.viewportHeight &&
    vv.pageTop < request.y && Math.abs(vv.pageTop - clamped) <= 1)) return null;
    request.y = vv.pageTop;request.extent = extent;
  }
  return request;
}

function bringIntoView(slot, options) {
  var vv = window.visualViewport;
  var box = slot.box,style = box.style;
  var epoch = officeNavigationEpoch;
  var current = () => epoch === officeNavigationEpoch && !officeClosed && !officePaused && (!options?.isCurrent || options.isCurrent());
  var saved = ["scroll-margin-top", "scroll-margin-bottom"].map(function (name) {
    return [name, style.getPropertyValue(name), style.getPropertyPriority(name)];
  });
  var geometryCache = null,geometryObserver = null;
  if (typeof ResizeObserver === 'function') {
    try {
      geometryObserver = new ResizeObserver(function () {geometryCache = null;officeLayoutChanged();});
      geometryObserver.observe(pages);
      geometryObserver.observe(box);
      if (document.body) geometryObserver.observe(document.body, { box: 'border-box' });
    } catch (_) {
      if (geometryObserver) geometryObserver.disconnect();
      geometryObserver = null;
    }
  }
  function destination(force) {

    {
      var w = innerWidth,h = innerHeight;
      var changed = force || !geometryObserver || !geometryCache ||
      geometryCache.revision !== officeLayoutRevision || geometryCache.width !== w || geometryCache.viewportHeight !== h;
      if (changed) {

        var rect = officePageRect(slot, force);
        var extent = officeDocumentExtent(force);
        geometryCache = { top: rect.top + scrollY, pageHeight: rect.height, extent: extent,
          revision: officeLayoutRevision, width: w, viewportHeight: h };
      }
      var bounds = officeRevealBounds();
      var inset = bounds.top - (vv ? vv.offsetTop : 0);
      var height = bounds.bottom - bounds.top;
      var center = geometryCache.pageHeight <= height / 2 ? (height - geometryCache.pageHeight) / 2 : 0;
      var top = geometryCache.top,extent = geometryCache.extent;
      var result = { top: top, extent: extent, height: Math.max(1, height), y: Math.max(0, Math.min(top - inset - center,
        extent - (vv ? vv.height : innerHeight))) };
      return result;
    }
  }
  var usedMargins = false;
  function move(y, geometry) {

    {
      var layoutTop = y - (vv ? vv.offsetTop : 0);
      if (layoutTop >= 0 && layoutTop <= Math.max(0, geometry.extent - innerHeight)) {

        window.scrollTo({ top: layoutTop, left: scrollX, behavior: "instant" });
        return;
      }
      var rootPadding = geometryCache.rootPadding;
      if (rootPadding === undefined) rootPadding = geometryCache.rootPadding = parseFloat(getComputedStyle(document.documentElement).scrollPaddingTop) || 0;
      var margin = geometry.top - y - rootPadding;
      style.setProperty("scroll-margin-top", margin + "px", "important");
      style.setProperty("scroll-margin-bottom", -margin + "px", "important");
      usedMargins = true;
      box.scrollIntoView({ block: "start", inline: "nearest", behavior: "instant" });
    }
  }
  askedFor = null;

  return new Promise(function (resolve, reject) {
    var motion = { frame: 0, velocity: 0, timer: 0, done: false,
      finish: function (completed, error) {
        if (motion.done) return;
        motion.done = true;
        cancelAnimationFrame(motion.frame);
        clearTimeout(motion.timer);
        if (geometryObserver) {geometryObserver.disconnect();geometryObserver = null;}
        if (officePageMotion === motion) officePageMotion = null;
        officeScheduleTextDrain();
        if (usedMargins) saved.forEach(function (entry) {
          if (entry[1]) style.setProperty(entry[0], entry[1], entry[2]);else
          style.removeProperty(entry[0]);
        });
        if (completed && vv && !options?.isCurrent) askedFor = { index: slot.n - 1,
          x: vv.pageLeft, y: vv.pageTop, extent: officeDocumentExtent(),
          viewportHeight: vv.height, viewportScale: vv.scale };

        if (error) reject(error);else resolve();
      } };
    officePageMotion = motion;
    var smooth = options?.behavior === "smooth" && officeDidReady && !officeReducedMotion.matches;
    var segment,lastTime = performance.now(),frames = 0;
    function retarget(start, geometry, velocity) {
      var delta = geometry.y - start,distance = Math.abs(delta);
      var speed = Number.isFinite(velocity) && velocity * delta > 0 ? velocity : 0;
      var duration = Math.min(900, 220 + 180 * Math.sqrt(distance / geometry.height));
      if (speed) duration = Math.min(duration, 3 * distance / Math.abs(speed));
      var slope = speed * duration,magnitude = Math.abs(slope);
      var quadratic = 3 * magnitude - 6 * distance;
      var linear = 6 * distance - 4 * magnitude;
      var maximum = magnitude;
      if (quadratic < 0) {
        var peak = -linear / (2 * quadratic);
        if (peak > 0 && peak < 1) maximum = Math.max(maximum,
        magnitude + linear * peak + quadratic * peak * peak);
      }
      segment = { start: start, target: geometry.y, delta: delta, elapsed: 0,
        duration: duration, slope: slope, maximumDerivative: Math.max(distance, maximum),
        step: Math.max(0.12 * geometry.height, 0.04 * distance) };
      motion.velocity = speed;
    }
    function arrive(geometry, actual = vv ? vv.pageTop : scrollY) {
      if (Math.abs(actual - geometry.y) > 1) throw new Error("PDF owned navigation did not arrive");
      motion.velocity = 0;
      motion.finish(true);
    }
    function frame(now) {
      motion.frame = 0;
      if (motion.done) return;
      if (!current()) {motion.finish(false);return;}
      try {
        if (++frames > 2048) throw new Error("PDF owned navigation frame budget");
        var geometry = destination();
        if (officeReducedMotion.matches) {
          geometry = destination(true);
          move(geometry.y, geometry);arrive(geometry);return;
        }
        var clock = Math.max(lastTime, now);
        var elapsed = Math.min(32, clock - lastTime);
        lastTime = clock;
        if (Math.abs(geometry.y - segment.target) > 1) {
          retarget(vv ? vv.pageTop : scrollY, geometry, motion.velocity);
        }
        var distance = Math.abs(segment.delta);
        if (distance) elapsed = Math.min(elapsed, segment.step * segment.duration / segment.maximumDerivative);
        segment.elapsed = Math.min(segment.duration, segment.elapsed + elapsed);
        var p = distance ? segment.elapsed / segment.duration : 1,q = 1 - p;
        var y = segment.start + segment.delta * p * p * (3 - 2 * p) + segment.slope * p * q * q;
        motion.velocity = (6 * segment.delta * p * q + segment.slope * q * (1 - 3 * p)) / segment.duration;
        if (p > 0) move(y, geometry);
        if (p === 1) {
          var actual = vv ? vv.pageTop : scrollY;
          var finalGeometry = destination(true);
          if (Math.abs(finalGeometry.y - geometry.y) > 1) {
            retarget(actual, finalGeometry, motion.velocity);
            motion.frame = requestAnimationFrame(frame);
            return;
          }
          geometry = finalGeometry;
          if (Math.abs(actual - geometry.y) > 1 && geometry.y !== segment.target) {
            retarget(actual, geometry, motion.velocity);
            motion.frame = requestAnimationFrame(frame);
          } else arrive(geometry, actual);
        } else motion.frame = requestAnimationFrame(frame);
      } catch (error) {motion.finish(false, error);}
    }
    try {
      if (!current()) {motion.finish(false);return;}
      var geometry = destination();
      if (!smooth) {move(geometry.y, geometry);arrive(geometry);return;}
      retarget(vv ? vv.pageTop : scrollY, geometry, options?.velocity);
      motion.timer = setTimeout(function () {
        motion.finish(false, new Error("PDF owned navigation wall-clock budget"));
      }, 5000);
      motion.frame = requestAnimationFrame(frame);
    } catch (error) {motion.finish(false, error);}
  });
}

function place(slot, options) {
  var epoch = officeNavigationEpoch;
  var current = () => epoch === officeNavigationEpoch && !officeClosed && !officePaused && (!options?.isCurrent || options.isCurrent());
  var unshaped = [slot.n - 1, slot.n].filter(function (n) {
    return n >= 1 && !slots[n - 1].box.style.aspectRatio;
  });
  return Promise.all(unshaped.map(function (n) {
    return doc.getPage(n).then(function (page) {
      if (current()) shape(slots[n - 1].box, page.getViewport({ scale: 1 }));
    }, function () {

       });
  })).then(function () {if (current()) return bringIntoView(slot, options);});
}

var pageRaf = 0;
function schedulePageReport() {
  if (officeClosed || officePaused) return;
  if (pageRaf) return;
  pageRaf = requestAnimationFrame(function () {pageRaf = 0;officeVisible();reportPage();});
}

function moved() {
  schedulePageReport();
  scheduleRefine();
}

window.addEventListener("scroll", moved, { passive: true });
if (window.visualViewport) {
  window.visualViewport.addEventListener("scroll", moved, { passive: true });
  window.visualViewport.addEventListener("resize", moved, { passive: true });
}


function onCommand(msg) {
  if (vwBarSaid(msg)) return;
  var verb = msg.charAt(0);
  if (verb === "q") {
    var q = msg.slice(1);
    if (q === finding.query) return;
    finding.query = q;
    finding.active = -1;
    if (!q) {finding.matches = [];finding.capped = false;paint();report();return;}
    if (!finding.pages) {buildIndex();report();return;}
    rerun();
    stepTo(0);
  } else if (verb === "n") {
    stepTo(finding.active + 1);
  } else if (verb === "p") {
    stepTo(finding.active - 1);
  } else if (verb === "i") {
    setNight(msg.charAt(1) === "1");
  } else if (verb === "c") {
    finding.query = "";
    finding.matches = [];
    finding.active = -1;
    paint();
    report();
  } else if (verb === "g") {
    var n = Math.floor(Number(msg.slice(1)));
    if (!doc || !slots.length || !(n >= 1 && n <= slots.length)) return;
    var target = slots[n - 1];
    place(target).then(function () {
      want(target);
      pump();
      reportPage();
      scheduleRefine();
    });
  }
}

function vwAskPassword(updatePassword, reason) {
  if (!officeClosed) window.officePdfPassword(updatePassword, reason);
}

function vwUnlockable(task) {
  task.onPassword = vwAskPassword;
  return task.promise;
}

function vwAssetUrl(path) {
  return new URL(path, import.meta.url).href;
}

function vwWithAssets(opts) {
  opts.cMapUrl = vwAssetUrl("./lib/cmaps/");
  opts.cMapPacked = true;
  opts.wasmUrl = vwAssetUrl("./lib/wasm/");
  opts.standardFontDataUrl = vwAssetUrl("./lib/standard_fonts/");
  opts.iccUrl = vwAssetUrl("./lib/iccs/");
  opts.useWorkerFetch = true;
  opts.useWasm = true;
  return opts;
}

function vwOpenDoc() {

  return vwFetchDoc('buffer').then(function (buffer) {
    if (officeClosed) throw new Error('Closed');
    officeNativeWorker = new Worker(pdfjsLib.GlobalWorkerOptions.workerSrc, { type: 'module' });
    officeNativeWorker.addEventListener('error', function () {
      if (!officeClosed) window.officePdfWorkerFailure();
    });
    officePdfWorker = new pdfjsLib.PDFWorker({ port: officeNativeWorker });
    var task = officeLoadingTask = pdfjsLib.getDocument(vwWithAssets({ data: buffer,
      worker: officePdfWorker, password: window.officePdfTakePassword(),
      isEvalSupported: false, enableXfa: false, stopAtErrors: true,
      useSystemFonts: false, disableAutoFetch: true, disableStream: true }));
    task.onPassword = vwAskPassword;
    return task.promise.then(function (opened) {
      if (officeClosed) {task.destroy();throw new Error('Closed');}
      return opened.getPermissions().then(function (permissions) {
        window.officePdfMetadata(opened.numPages, permissions, opened.isPureXfa);
        return opened;
      });
    });
  });
}

function vwFailure(e) {
  if (/encryption|crypto/i.test(String(e && e.message))) {
    return [
    "This PDF is protected in a way Gander cannot open",
    "It is encrypted, but not with the password scheme its renderer reads, so " +
    "there is nothing you could type here that would open it. Every other " +
    "format Gander opens still works."];

  }
  switch (e && e.name) {
    case "PasswordException":
      return [
      "This PDF could not be unlocked",
      "The password was not accepted. If the file opens elsewhere, its " +
      "protection is of a kind Gander cannot read yet."];

    case "InvalidPDFException":
      return [
      "This file is not a PDF that can be read",
      "It is either damaged or not a PDF at all, whatever it happens to be " +
      "named. Every other format Gander opens still works."];

    case "ResponseException":
      return [
      "This PDF could not be read to the end",
      "Gander lost access to the file part way through loading it. Opening it " +
      "again usually settles this."];

    default:
      return ["Could not render this PDF", String(e)];
  }
}

function observePages() {
  if (officeClosed || officePaused || officeObservers.length) return;
  var draws = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (e.isIntersecting) want(e.target._slot);else unwant(e.target._slot);
    });
    pump();
  }, { rootMargin: Math.round(DRAW_PAGES * PAGE_PITCH) + "px 0px" });

  var keeps = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (!e.isIntersecting) release(e.target._slot);
    });
  }, { rootMargin: Math.round(KEEP_PAGES * PAGE_PITCH) + "px 0px" });

  officeObservers.push(draws, keeps);
  slots.forEach(function (slot) {
    draws.observe(slot.box);
    keeps.observe(slot.box);
  });
}

officeInstall();

if (!vwPdfBlocked && !officeClosed) {
  vwOpenDoc().
  then(function (opened) {

    if (officeClosed) return;
    doc = opened;



    {
      var frag = document.createDocumentFragment();
      for (var i = 1; i <= doc.numPages; i++) {
        var box = document.createElement("div");
        box.className = "pg";
        var c = document.createElement("canvas");
        c.width = c.height = 0;
        box.appendChild(c);
        var tl = document.createElement("div");
        tl.className = "textLayer";
        box.appendChild(tl);
        var gap = document.createElement("div");
        gap.className = "vw-gap";
        box.appendChild(gap);
        var slot = {
          n: i, box: box, canvas: c, text: tl,
          state: "blank", mode: null, pictures: null, task: null, page: null, layer: null,
          unscaled: null, tile: null, pending: null, retiring: null, workCanvas: null, palette: 0,
          textEpoch: 0, textJob: null, textReady: false
        };

        box._slot = slot;
        slots.push(slot);
        frag.appendChild(box);
      }
      pages.appendChild(frag);
    }

    return doc.getPage(1);
  }).
  then(function (page) {
    if (officeClosed || !page) return;
    var unscaled = page.getViewport({ scale: 1 });
    pages.style.setProperty(
      "--pg-ratio", String(unscaled.width / unscaled.height));
    PAGE_PITCH = PAGE_WIDTH * (unscaled.height / unscaled.width) + (
    parseFloat(getComputedStyle(document.documentElement).
    getPropertyValue("--pg-gap")) || 8);

    var first = slots[0];
    var resumeAt = Math.floor(Number(vwParams.get("resume")));
    if (resumeAt >= 2 && resumeAt <= slots.length) first = slots[resumeAt - 1];
    officeInitialSlot = first;
    officeInitialPlaced = first === slots[0];
    officeContinueInitialPage();
  }).
  catch(function (e) {

    window.officePdfFailure(e);
  });
}
