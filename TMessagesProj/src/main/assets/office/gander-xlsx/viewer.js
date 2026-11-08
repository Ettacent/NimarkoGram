/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */
import { XlsxViewer } from './engine/gander-xlsx.mjs';
import { sheetZoom } from './engine/sheet-zoom.mjs';
const root = document.documentElement,container = document.getElementById('viewer'),status = document.getElementById('status');
const MAX = 24 * 1024 * 1024;
let started = false,active = false,ready = false,paused = false,allowCopy = false,generation = 0,reader = null,abort = null;
let top = 0,bottom = 0,baseScale = 1,scale = 1,frame = 0,frameId = 0,awaiting = 0,dirty = false;
let layoutTop = -1,layoutBottom = -1;
let insetTimer = 0,insetFrame = 0,insetRun = null,insetViewport = null,touchCount = 0,motionEnabled = true;
const insetMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
let sheetMap = [],index = 0,navigation = 0,queryRevision = 0,query = '',queryClipped = false,searchEpoch = 0;
let pendingSearch = false,searchRunning = false,matchCount = 0,matchIndex = -1,truncated = false,findSteps = 0,findIntent = null;
let searchRevealIntent = null;
let navigationRunning = false,wantedSheet = null,opening = false,motion = 0;
let viewportPoint = null;const activePointers = new Set();
let verticalTimer = 0,verticalFrame = 0,verticalRun = null,verticalViewport = null,verticalStopped = false,verticalZoomUntil = 0;
let verticalNativeBusy = false,verticalNativeRevision = -1;
let zoom = null;
const palette = { background: 0xffe8e8e8, foreground: 0xff222222, accent: 0xff2481cc, readingPaper: 0xff242424, readingDark: false };
const cssColor = (value) => '#' + (Number(value) >>> 0).toString(16).padStart(8, '0').slice(-6);
const clamp = (value, lo, hi) => Math.max(lo, Math.min(hi, value));
const live = () => active && !paused && !document.hidden;
function send(value) {if (!active && value.type !== 'bridgeReady') return;try {window.OfficeHost?.postMessage(JSON.stringify(value));} catch (_) {}}





function accentInk(value) {const c = [16, 8, 0].map((s) => (value >>> s & 255) / 255).map((v) => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4);return c[0] * .2126 + c[1] * .7152 + c[2] * .0722 > .179 ? '#000000' : '#ffffff';}
function colors(message) {

  {
    for (const key of ['background', 'foreground', 'accent', 'readingPaper']) if (Number.isInteger(message[key])) palette[key] = message[key];
    if (typeof message.readingDark === 'boolean') palette.readingDark = message.readingDark;
    root.dataset.readingDark = String(palette.readingDark);root.style.colorScheme = palette.readingDark ? 'dark' : 'light';
    root.style.setProperty('--office-desk', cssColor(palette.background));
    root.style.setProperty('--office-ui-ink', cssColor(palette.foreground));
    root.style.setProperty('--office-paper', palette.readingDark ? cssColor(palette.readingPaper) : '#ffffff');
    root.style.setProperty('--office-ink', palette.readingDark ? cssColor(palette.foreground) : '#222222');
    root.style.setProperty('--office-border', palette.readingDark ? cssColor(palette.foreground) + '55' : '#cfd8dc');
    root.style.setProperty('--office-accent', cssColor(palette.accent));root.style.setProperty('--office-accent-ink', accentInk(palette.accent));
    root.style.setProperty('--office-find', cssColor(palette.accent) + '66');scheduleFrame();

  }
}
function protection(value) {allowCopy = value === true;root.classList.toggle('office-protected', !allowCopy);if (!allowCopy) getSelection()?.removeAllRanges();}
for (const type of ['copy', 'cut', 'paste', 'contextmenu', 'dragstart', 'selectstart']) document.addEventListener(type, (event) => {if (!allowCopy) event.preventDefault();}, true);
document.addEventListener('selectionchange', () => {if (!allowCopy && getSelection()?.rangeCount) getSelection().removeAllRanges();});
document.addEventListener('click', (event) => {if (event.target.closest?.('a,area')) {event.preventDefault();event.stopImmediatePropagation();}}, true);
function insets(message = {}) {
  if (Number.isFinite(message.top)) top = clamp(message.top, 0, 4096);if (Number.isFinite(message.bottom)) bottom = clamp(message.bottom, 0, 4096);
  if (!live()) return;
  if (!ready) applyInsets();else queueInsets();
}
function visibleInsets() {
  const actual = window.visualViewport?.scale || 1;
  return { top: top / actual, bottom: bottom / actual };
}
function cancelInsets() {
  cancelVertical();
  clearTimeout(insetTimer);insetTimer = 0;cancelAnimationFrame(insetFrame);insetFrame = 0;
  insetRun = null;insetViewport = null;
}
function insetPoint() {const vv = window.visualViewport;return [...readViewport(), vv?.width || innerWidth, vv?.height || innerHeight];}
function sameInsetPoint(a, b) {return !!a && a.every((value, i) => value === b[i]);}
function insetIdle() {return live() && ready && !activePointers.size && !touchCount && !verticalNativeBusy && !navigationRunning && wantedSheet === null && !searchRunning;}
function queueInsets() {
  cancelInsets();
  if (!live() || !ready || activePointers.size || touchCount) return;
  insetTimer = setTimeout(() => {insetTimer = 0;
    if (insetIdle()) applyInsets();
  }, 160);
}
function applyInsets(force = false) {
  if (!live()) return;
  const safe = visibleInsets(),vv = window.visualViewport;
  const atTop = (reader?.getScrollState?.().y ?? vv?.pageTop ?? scrollY) <= 1 / (vv?.scale || 1);
  const cssTop = force || !ready || atTop ? Math.ceil(safe.top * 64) / 64 : layoutTop;
  const neededBottom = Math.ceil(safe.bottom * 64) / 64;
  const cssBottom = force || !ready || atTop ? neededBottom : Math.max(layoutBottom, neededBottom);
  cancelInsets();
  if (cssTop === layoutTop && cssBottom === layoutBottom) {queueVertical();return;}
  if (force || !ready || !motionEnabled || insetMotion?.matches) {writeInsets(cssTop, cssBottom);return;}
  const run = { top: layoutTop, bottom: layoutBottom, targetTop: cssTop, targetBottom: cssBottom, start: performance.now(), point: insetPoint() };
  insetRun = run;
  const step = (now) => {
    insetFrame = 0;if (insetRun !== run) return;
    if (!insetIdle()) {cancelInsets();return;}
    if (!sameInsetPoint(run.point, insetPoint())) {queueInsets();return;}
    const progress = clamp((now - run.start) / 180, 0, 1),ease = progress * progress * (3 - 2 * progress);
    writeInsets(run.top + (run.targetTop - run.top) * ease, run.bottom + (run.targetBottom - run.bottom) * ease);
    run.point = insetViewport;
    if (progress < 1) insetFrame = requestAnimationFrame(step);else {insetRun = null;queueVertical();}
  };
  insetFrame = requestAnimationFrame(step);
}
function writeInsets(cssTop, cssBottom) {

  {
    if (cssTop !== layoutTop) {layoutTop = cssTop;root.style.setProperty('--office-top', cssTop + 'px');}
    if (cssBottom !== layoutBottom) {layoutBottom = cssBottom;root.style.setProperty('--office-bottom', cssBottom + 'px');}
    reader?.setInsets(cssTop, cssBottom);
    root.getBoundingClientRect();insetViewport = insetPoint();rememberViewportWrite();
    reader?.scheduleVisible();scheduleFrame();queueVertical();
  }
}
function cancelVertical() {
  clearTimeout(verticalTimer);verticalTimer = 0;cancelAnimationFrame(verticalFrame);verticalFrame = 0;
  verticalRun = null;verticalViewport = null;
}
function verticalIdle() {return insetIdle() && !insetTimer && !insetFrame && !insetRun && !verticalStopped && !verticalNativeBusy && performance.now() >= verticalZoomUntil;}
function verticalBounds() {

  {
    const vv = window.visualViewport,rect = reader?.getReadingRects()[0];if (!vv || !rect) return null;
    const end = rect.bottom + scrollY,safe = visibleInsets();
    return { end, goal: Math.max(0, end + safe.bottom - vv.height), x: vv.pageLeft, y: vv.pageTop, scale: vv.scale, width: vv.width, height: vv.height };
  }
}
function verticalSmooth() {return motionEnabled && !insetMotion?.matches;}
function verticalTarget(run, current, ideal, limit = Infinity) {
  const eps = 1 / run.scale,minimum = current.y - limit / run.scale;
  if (ideal <= run.goal + eps) {
    const upper = Math.ceil(run.goal),final = upper - run.goal <= eps ? upper : Math.floor(run.goal);
    if (Math.abs(final - run.goal) > eps) return ideal;
    if (final >= minimum && final <= current.y) return final;
  }
  const integer = Math.ceil(ideal);
  return integer >= run.goal && integer >= minimum && integer <= current.y ? integer : null;
}
function placeVertical(run, current, y, limit = Infinity, settle = false) {
  if (!reader.scrollViewportTo(run.x, y)) return null;
  const actual = verticalBounds(),eps = 1 / run.scale;
  if (!actual || actual.end !== run.end || actual.goal !== run.goal || Math.abs(actual.x - run.x) > eps || actual.scale !== run.scale || actual.y < run.goal - eps || actual.y > current.y + eps || Math.abs(actual.y - y) > eps || (current.y - actual.y) * run.scale > limit || settle && Math.abs(actual.y - run.goal) > eps) return null;
  verticalViewport = insetPoint();insetViewport = verticalViewport;rememberViewportWrite();scheduleFrame();return actual;
}
function queueVertical() {
  cancelVertical();
  if (reader?.logicalZoom) return;
  if (!insetIdle() || insetTimer || insetFrame || insetRun || verticalStopped || verticalNativeBusy) return;
  const point = insetPoint();
  verticalTimer = setTimeout(() => {
    verticalTimer = 0;
    if (!verticalIdle()) return;
    if (!sameInsetPoint(point, insetPoint())) {queueInsets();return;}
    const b = verticalBounds();if (!b || b.y <= b.goal + 1 / b.scale) return;
    if (!verticalSmooth()) {if (placeVertical(b, b, verticalTarget(b, b, b.goal), Infinity, true)) queueInsets();return;}
    const run = { ...b, start: performance.now(), point };verticalRun = run;
    const step = (now) => {
      verticalFrame = 0;if (verticalRun !== run) return;
      if (!verticalSmooth()) {cancelVertical();queueVertical();return;}
      if (!verticalIdle() || !sameInsetPoint(run.point, insetPoint())) {queueInsets();return;}
      const current = verticalBounds();
      if (!current || current.end !== run.end || current.goal !== run.goal || current.scale !== run.scale || current.width !== run.width || current.height !== run.height) {queueInsets();return;}
      if (now - run.start > 1600) {cancelVertical();return;}
      const progress = clamp((now - run.start) / 420, 0, 1),ease = progress * progress * (3 - 2 * progress);
      const ideal = Math.min(current.y, Math.max(run.goal, current.y - 96 / run.scale, run.y - (run.y - run.goal) * ease));
      const y = verticalTarget(run, current, ideal, 96);
      if (y === null || y === current.y) {verticalFrame = requestAnimationFrame(step);return;}
      const eps = 1 / run.scale,actual = placeVertical(run, current, y, 96, Math.abs(y - run.goal) <= eps);
      if (!actual) {cancelVertical();return;}run.point = verticalViewport;
      if (actual.y > run.goal + eps) verticalFrame = requestAnimationFrame(step);else
      {cancelVertical();queueInsets();}
    };
    verticalFrame = requestAnimationFrame(step);
  }, Math.max(160, verticalZoomUntil - performance.now() + 160));
}
function nativeZoomState() {
  return [live() && ready ? (window.visualViewport?.scale || 0) / baseScale : 0, window.visualViewport?.scale || 0, window.devicePixelRatio || 1];
}
function stopScrolling() {verticalStopped = true;verticalZoomUntil = 0;motion++;cancelInsets();}
function insetViewportChanged() {

  {
    scheduleFrame();
    if (sameInsetPoint(verticalViewport, insetPoint())) {return;}
    if (sameInsetPoint(insetViewport, insetPoint())) {return;}

    if (ready) verticalStopped = true;
    sampleViewportIntent();if (!ready) applyInsets();else queueInsets();
  }
}
function insetMotionChanged() {
  if (!verticalSmooth() && (verticalRun || verticalFrame || verticalTimer)) {cancelVertical();queueVertical();}
  if (!insetRun || motionEnabled && !insetMotion?.matches) return;
  const run = insetRun,current = insetIdle() && sameInsetPoint(run.point, insetPoint());cancelInsets();
  if (current) writeInsets(run.targetTop, run.targetBottom);else queueInsets();
}
insetMotion?.addEventListener?.('change', insetMotionChanged);
function motionSettings(message) {
  if (typeof message.motionEnabled === 'boolean') motionEnabled = message.motionEnabled;
  zoom?.motionChanged();
  insetMotionChanged();
}
function prepareReveal() {
  if (!live() || activePointers.size || touchCount) return false;
  cancelInsets();applyInsets(true);rememberViewportWrite();return true;
}
function reportPosition() {if (ready) send({ type: 'position', count: sheetMap.length, index, label: reader.sheetNames[sheetMap[index]] });}
function scheduleFrame() {
  dirty = true;if (!live() || !ready || awaiting || frame) return;
  frame = requestAnimationFrame(() => {frame = 0;if (!live() || !ready) return;
    const vv = window.visualViewport,next = reader?.getScale() || 1;
    if (Math.abs(next - scale) > .001) {scale = next;send({ type: 'scale', scale });}
    dirty = false;awaiting = ++frameId;send({ type: 'frame', generation, frameId, coordinateRevision: navigation,
      x: reader?.host.scrollLeft || 0, y: reader?.host.scrollTop || 0, width: reader?.host.clientWidth || innerWidth, scale });
  });
}
function searchState(pending = false) {send({ type: 'search', revision: queryRevision, index: matchIndex, count: matchCount, truncated, pending });}
function readViewport() {return [scrollX, scrollY, reader?.host.scrollLeft || 0, reader?.host.scrollTop || 0, reader?.getScale() || 1];}
function sampleViewportIntent() {const next = readViewport();if (viewportPoint && next.some((value, i) => value !== viewportPoint[i])) motion++;viewportPoint = next;}
function rememberViewportWrite() {viewportPoint = readViewport();}
const fallbackReasons = [
'unsupported-workbook-features', 'unsupported-freeze-panes', 'unsupported-rich-text',
'unsupported-sheet-feature-conditionalFormatting', 'unsupported-sheet-feature-drawing', 'unsupported-sheet-feature-legacyDrawing',
'unsupported-sheet-feature-tableParts', 'unsupported-sheet-feature-extLst', 'unsupported-sheet-feature-picture',
'unsupported-sheet-feature-oleObjects', 'unsupported-sheet-feature-controls', 'unsupported-right-to-left-sheet',
'unsupported-base-style-inheritance', 'unsupported-style-element', 'unsupported-style-extension', 'unsupported-pattern-fill',
'unsupported-diagonal-border', 'unsupported-text-rotation', 'unsupported-shrink-to-fit', 'unsupported-alignment',
'unsupported-disabled-style', 'unsupported-row-style-inheritance', 'row-metadata-outside-range', 'column-metadata-outside-range',
'styled-cell-outside-range', 'row-layout-outside-range', 'column-style-outside-range', 'native-visible-dom-budget',
'native-piece-dom-budget', 'hidden-merge-layout', 'sheetjs-load-or-parse', 'gander-worker-failed', 'gander-worker-timeout',
'native-layout-extent', 'style-part-missing', 'style-sheet-mapping', 'sheet-path', 'duplicate-sheet-relationship',
'style-row-contract', 'missing-cell-style', 'missing-inherited-style', 'missing-row-style', 'missing-style-component',
'invalid-style-number', 'unsafe-font', 'unsafe-color', 'unsafe-theme-color', 'missing-base-style', 'uncached-formula',

'unsupported-indexed-palette'];

function fail(error) {
  if (!active) return;const code = Number.isInteger(error?.officeCode) ? error.officeCode : 1;
  const featureCode = fallbackReasons.indexOf(error?.message) + 1;
  send({ type: 'error', code, featureCode, renderer: 'gander-xlsx', fallbackRequired: code === 3 && !ready });
  status.hidden = false;status.textContent = code === 3 ? 'This spreadsheet needs the original reader.' : 'Could not open this spreadsheet.';dispose(false);
}
function dispose(hide = true) {
  if (!active) return;active = false;ready = false;++searchEpoch;++navigation;
  cancelInsets();activePointers.clear();touchCount = 0;
  abort?.abort();cancelAnimationFrame(frame);cancelAnimationFrame(readyFrame);frame = 0;readyFrame = 0;awaiting = 0;
  zoom?.destroy();zoom = null;
  reader?.destroy();reader = null;
  if (hide) status.hidden = true;
}
async function bytes(signal) {
  const response = await fetch('/document', { signal, credentials: 'omit', cache: 'no-store', redirect: 'error' });
  if (!response.ok) throw Error('local document unavailable');
  const declared = Number(response.headers.get('content-length'));if (declared > MAX) throw { officeCode: 2 };
  const input = response.body?.getReader();if (!input) throw { officeCode: 3 };const chunks = [];let length = 0;
  try {for (;;) {const chunk = await input.read();if (chunk.done) break;length += chunk.value.byteLength;if (length > MAX) throw { officeCode: 2 };chunks.push(chunk.value);}}
  catch (error) {await input.cancel().catch(() => {});throw error;} finally {input.releaseLock();}
  if (!length) throw { officeCode: 1 };const all = new Uint8Array(length);let offset = 0;for (const chunk of chunks) {all.set(chunk, offset);offset += chunk.length;}return all.buffer;
}
async function navigate(target) {
  if (!ready || !live() || !Number.isFinite(target)) return;
  zoom?.stop();
  cancelInsets();
  ++navigation;wantedSheet = clamp(Math.trunc(target), 0, sheetMap.length - 1);await drainNavigation();
}
async function drainNavigation() {
  if (!ready || !live() || navigationRunning || wantedSheet === null) return;
  navigationRunning = true;
  try {while (live() && wantedSheet !== null) {const next = wantedSheet;wantedSheet = null;await reader.goToSheet(sheetMap[next]);if (!active) return;index = sheetMap.indexOf(reader.sheetIndex);reportPosition();scheduleFrame();}}
  catch (error) {fail(error);} finally {navigationRunning = false;queueInsets();}
}
function setQuery(message) {
  if (!Number.isInteger(message.revision) || message.revision < queryRevision || message.revision > 2147483647) return;
  zoom?.stop();
  sampleViewportIntent();
  queryRevision = message.revision;const value = typeof message.query === 'string' ? message.query : '';let end = Math.min(4096, value.length);
  if (end < value.length && /[\uD800-\uDBFF]/.test(value[end - 1]) && /[\uDC00-\uDFFF]/.test(value[end])) end--;
  query = value.slice(0, end).replace(/\s+/g, ' ').trim();queryClipped = end < value.length;++searchEpoch;reader?.clearFind();
  matchCount = 0;matchIndex = -1;truncated = queryClipped;findSteps = 0;findIntent = null;searchRevealIntent = { navigation, motion };pendingSearch = true;
  if (ready) {searchState(!!query);void drainSearch();}
}
async function reveal(delta, epoch) {
  sampleViewportIntent();
  const nav = navigation,at = motion,current = () => {sampleViewportIntent();return live() && epoch === searchEpoch && nav === navigation && at === motion;};
  const match = await (delta > 0 ? reader.findNext({ isCurrent: current }) : reader.findPrev({ isCurrent: current }));
  if (!current() || !match) {if (active && epoch === searchEpoch) searchState();return;}matchIndex = match.matchIndex;index = sheetMap.indexOf(reader.sheetIndex);reportPosition();searchState();scheduleFrame();
}
async function drainSearch() {
  if (searchRunning || !ready || !live()) return;searchRunning = true;
  try {while (live() && (pendingSearch || findSteps)) {
      const epoch = searchEpoch;
      if (pendingSearch) {pendingSearch = false;sampleViewportIntent();
        if (!searchRevealIntent) searchRevealIntent = { navigation, motion };
        const wanted = query,nav = searchRevealIntent.navigation,at = searchRevealIntent.motion;
        if (!wanted) {reader.clearFind();matchCount = 0;matchIndex = -1;searchState();continue;}
        const found = await reader.findText(wanted);if (!active) return;if (epoch !== searchEpoch) continue;
        matchCount = Math.min(1000, found.length);truncated = queryClipped || found.length > 1000;matchIndex = -1;
        if (!live()) {pendingSearch = true;continue;}
        sampleViewportIntent();
        if (!searchRevealIntent.consumed && nav === navigation && at === motion && matchCount) {searchRevealIntent.consumed = true;await reveal(1, epoch);} else searchState();
      } else {
        sampleViewportIntent();
        if (!findIntent || findIntent.navigation !== navigation || findIntent.motion !== motion) {findSteps = 0;continue;}
        const delta = findSteps > 0 ? 1 : -1;findSteps -= delta;if (matchCount) {if (searchRevealIntent) searchRevealIntent.consumed = true;await reveal(delta, epoch);}
      }
    }} catch (error) {if (active) fail(error);} finally {searchRunning = false;if (live() && pendingSearch) void drainSearch();queueInsets();}
}
let readyFrame = 0;
function announce(initial) {
  if (!active || !opening || !live()) return;readyFrame = requestAnimationFrame(() => {readyFrame = requestAnimationFrame(() => {
      readyFrame = 0;if (!active || !opening || !live()) return;
      try {if (!reader.hasVisibleCoverage()) {reader.scheduleVisible();announce(initial);return;}} catch (error) {fail(error);return;}
      opening = false;ready = true;baseScale = window.visualViewport?.scale || 1;scale = reader.getScale();
      index = clamp(initial, 0, sheetMap.length - 1);status.hidden = true;send({ type: 'ready', count: sheetMap.length, index, names: sheetMap.map((i) => reader.sheetNames[i]) });
      send({ type: 'scale', scale });reportPosition();scheduleFrame();pendingSearch = true;void drainSearch();queueInsets();
    });});
}
async function open(message) {
  if (started) return;started = active = true;++generation;
  try {
    if (message.format !== 'xlsx' || message.gestureMode !== 'xlsx-local-v1' || message.nativeGestures !== false || !window.visualViewport || !window.CSS?.highlights || typeof Highlight !== 'function') throw { officeCode: 3 };
    protection(message.allowCopy);colors(message);insets(message);abort = new AbortController();
    reader = new XlsxViewer(container, { onError: fail, getVisibleInsets: visibleInsets, prepareReveal, onViewportWrite: rememberViewportWrite, onViewportChange: insetViewportChanged, onGeometryChange: () => {zoom?.cancel();if (ready) {scheduleFrame();queueVertical();}}, onSheetChange: (sheet) => {zoom?.cancel();cancelInsets();applyInsets(true);rememberViewportWrite();if (ready) {index = sheetMap.indexOf(sheet);reportPosition();scheduleFrame();}} });
    reader.setInsets(layoutTop, layoutBottom);
    zoom = sheetZoom(reader, { live: () => live() && ready, motionEnabled: () => motionEnabled, onError: fail, onIntent: () => {motion++;cancelInsets();}, onWrite: rememberViewportWrite, onPreview: scheduleFrame, onCommit: () => {navigation++;scheduleFrame();queueInsets();} });
    reader.setPaused(paused || document.hidden);const data = await bytes(abort.signal);if (!active) return;await reader.load(data);if (!active) return;
    sheetMap = reader.model.sheets.filter((s) => !s.hidden).map((s) => s.index);const initial = clamp(Number.isInteger(message.index) ? message.index : 0, 0, sheetMap.length - 1);
    if (reader.sheetIndex !== sheetMap[initial]) await reader.goToSheet(sheetMap[initial]);if (!active) return;
    index = initial;insets({});opening = true;announce(initial);
  } catch (error) {if (active) fail(error);}
}
function suspendSearch() {if (searchRunning) {++searchEpoch;pendingSearch = true;findSteps = 0;findIntent = null;}}
function pause() {zoom?.cancel();paused = true;suspendSearch();cancelInsets();activePointers.clear();touchCount = 0;reader?.setPaused(true);cancelAnimationFrame(frame);frame = 0;awaiting = 0;dirty = true;}
function resume() {paused = false;reader?.setPaused(document.hidden);insets({});if (opening && !ready) announce(index);else {void drainNavigation();void drainSearch();scheduleFrame();queueInsets();}}
function command(message) {
  if (!message || typeof message !== 'object') return;const type = message.type || message.command;
  if (type === 'open' || type === 'resume' || type === 'colors') motionSettings(message);
  if (type === 'open') {void open(message);return;}if (type === 'pause') {pause();return;}
  if (type === 'dispose' || type === 'cancel' || type === 'close') {dispose();return;}if (!active) return;
  switch (type) {
    case 'nativeZoomActivity':if (reader?.logicalZoom) break;{

        {
          if (Number.isSafeInteger(message.revision) && message.revision > verticalNativeRevision && typeof message.active === 'boolean') {
            if (message.active && !live()) {break;}
            verticalNativeRevision = message.revision;if (message.active) verticalStopped = true;
            verticalNativeBusy = message.active;verticalZoomUntil = 0;cancelInsets();if (!verticalNativeBusy) queueInsets();
            send({ type: 'nativeZoomActivityAck', revision: verticalNativeRevision, active: verticalNativeBusy });
          }
        }
      }break;
    case 'resume':resume();break;
    case 'colors':colors(message);break;
    case 'insets':insets(message);break;
    case 'protection':protection(message.allowCopy);break;
    case 'query':setQuery(message);break;
    case 'find':if (message.revision === queryRevision) {
        zoom?.stop();
        sampleViewportIntent();
        if (!findIntent || findIntent.navigation !== navigation || findIntent.motion !== motion) findSteps = 0;
        findIntent = { navigation, motion };findSteps = clamp(findSteps + (message.forward === false ? -1 : 1), -1000, 1000);void drainSearch();
      }break;
    case 'goto':void navigate(message.index);break;
    case 'previous':void navigate((wantedSheet ?? index) - 1);break;
    case 'next':void navigate((wantedSheet ?? index) + 1);break;
    case 'zoomIn':case 'zoomOut':case 'reset':if (ready && live()) {if (type === 'reset') zoom?.setAt(1);else zoom?.step(type === 'zoomIn' ? 1.25 : .8);}break;
    case 'nativeScale':break;
    case 'frameAck':if (message.generation === generation && message.frameId === awaiting) {awaiting = 0;if (dirty) scheduleFrame();}break;
  }
}
for (const type of ['pointerdown', 'touchstart', 'wheel', 'keydown']) addEventListener(type, () => {if (type === 'keydown') zoom?.stop();verticalStopped = true;motion++;cancelInsets();}, { passive: true });
for (const type of ['wheel', 'keydown']) addEventListener(type, queueInsets, { passive: true });
addEventListener('pointerdown', (event) => {activePointers.add(event.pointerId);cancelInsets();}, { passive: true });
for (const type of ['pointerup', 'pointercancel']) addEventListener(type, (event) => {activePointers.delete(event.pointerId);queueInsets();}, { passive: true });
addEventListener('pointermove', (event) => {
  if (event.pointerType !== 'touch' && event.buttons === 0 && activePointers.delete(event.pointerId)) queueInsets();
  if (activePointers.has(event.pointerId) || event.buttons || event.pointerType === 'touch' || event.pointerType === 'pen') {
    if (event.buttons || event.pointerType === 'touch') activePointers.add(event.pointerId);
    motion++;queueInsets();
  }
}, { passive: true });
addEventListener('touchmove', () => {motion++;}, { passive: true });
for (const type of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) addEventListener(type, (event) => {touchCount = event.touches.length;queueInsets();}, { passive: true });
window.visualViewport?.addEventListener('resize', insetViewportChanged, { passive: true });
window.visualViewport?.addEventListener('scroll', insetViewportChanged, { passive: true });
addEventListener('scroll', insetViewportChanged, { passive: true });
addEventListener('resize', insetViewportChanged, { passive: true });addEventListener('pagehide', () => dispose());
document.addEventListener('visibilitychange', () => {if (document.hidden) {zoom?.cancel();suspendSearch();cancelInsets();activePointers.clear();touchCount = 0;reader?.setPaused(true);cancelAnimationFrame(frame);frame = 0;awaiting = 0;} else
  if (active && !paused) {reader?.setPaused(false);insets({});if (opening && !ready) announce(index);else {void drainNavigation();void drainSearch();scheduleFrame();queueInsets();}}});
protection(false);window.OfficeViewer = Object.freeze({ command, stopScrolling: () => {zoom?.stop();if (reader) {const s = reader.getScrollState();reader.host.scrollTo({ left: s.x, top: s.y, behavior: 'instant' });}stopScrolling();}, getLogicalScale: () => reader?.getScale() || 0, getNativeScale: () => live() && ready ? window.visualViewport?.scale || 0 : 0, getNativeZoomState: () => [live() && ready ? window.visualViewport?.scale || 0 : 0, window.visualViewport?.scale || 0, devicePixelRatio] });send({ type: 'bridgeReady', gestureMode: 'xlsx-local-v1' });
