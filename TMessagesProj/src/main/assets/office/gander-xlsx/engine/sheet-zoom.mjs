/* Modifications Copyright (C) 2026 Ettacent */

export function sheetZoom(reader, options = {}) {
  const host = reader.host,win = reader.doc.defaultView,clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const reduced = win.matchMedia?.('(prefers-reduced-motion: reduce)');
  let gesture = null,frame = 0,closed = false;

  const live = () => !closed && !reader.destroyed && !reader.paused && options.live?.() !== false;
  const smooth = () => options.motionEnabled?.() !== false && !reduced?.matches;
  const point = (event) => {const a = event.touches[0],b = event.touches[1];return { x: (a.clientX + b.clientX) / 2, y: (a.clientY + b.clientY) / 2, d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) };};
  function restore(g) {if (g.will) reader.sheetDiv.style.willChange = g.will;else reader.sheetDiv.style.removeProperty('will-change');}
  function cancel() {
    win.cancelAnimationFrame(frame);frame = 0;const g = gesture;if (!g) return;
    gesture = null;reader.zoomPreview = false;restore(g);
    if (!reader.destroyed) {reader.sheetDiv.style.transform = 'scale(' + reader.scale + ')';reader.updateExtent();if (live()) reader.scheduleVisible();}
    options.onPreview?.();
  }
  function failed(error) {
    try {cancel();} catch (_) {gesture = null;reader.zoomPreview = false;win.cancelAnimationFrame(frame);frame = 0;}
    options.onError?.(error);
  }
  function run(fn) {try {return fn();} catch (error) {failed(error);return false;}}
  function valid(g) {return gesture === g && live() && g.generation === reader.showGeneration;}
  function paint(g, value, p = g.latest) {
    if (!valid(g)) {cancel();return;}
    g.presented = clamp(value, g.minScale, g.maxScale);g.latest = p;
    const x = p.x - g.rect.left,y = p.y - g.rect.top;
    const width = reader.ceilExtent(Math.max(g.viewportWidth, g.sheetWidth * g.presented + 32));
    const viewportHeight = g.viewportHeight - (width > g.viewportWidth ? g.scrollbarSize : 0);
    const height = reader.ceilExtent(Math.max(viewportHeight, g.top + g.sheetHeight * g.presented + g.bottom));
    const left = (width - g.sheetWidth * g.presented) / 2;
    const scrollX = clamp(left + g.anchorX * g.presented - x, 0, width - g.viewportWidth);
    const scrollY = clamp(g.top + g.anchorY * g.presented - y, 0, height - viewportHeight);
    g.dx = host.scrollLeft + left - scrollX - g.left;
    g.dy = host.scrollTop - scrollY;
    reader.sheetDiv.style.transform = 'matrix(' + g.presented + ',0,0,' + g.presented + ',' + g.dx + ',' + g.dy + ')';
    options.onPreview?.();
  }
  function begin(p, owner, bounds) {
    if (!live() || !reader.model || !(p.d > 0)) return false;
    options.onIntent?.();const rect = host.getBoundingClientRect(),x = p.x - rect.left,y = p.y - rect.top;
    const viewportWidth = bounds?.viewportWidth ?? host.clientWidth,maxScale = bounds?.maxScale ?? reader.getMaximumScale(),minScale = bounds?.minScale ?? reader.getMinimumScale(reader.sheetIndex, viewportWidth),sheet = reader.model.sheets[reader.sheetIndex];
    gesture = { owner, rect, scale: reader.scale, presented: reader.scale, target: reader.scale, distance: p.d, latest: p,
      anchorX: (host.scrollLeft + x - reader.sheetLeft) / reader.scale, anchorY: (host.scrollTop + y - reader.topInset) / reader.scale,
      left: reader.sheetLeft, top: reader.topInset, bottom: reader.bottomInset,
      sheetWidth: sheet.width, sheetHeight: sheet.pieces.reduce((sum, p) => sum + p.height, 0),
      viewportWidth, viewportHeight: host.offsetHeight || host.clientHeight,
      scrollbarSize: Math.max(0, (host.offsetWidth || viewportWidth) - viewportWidth),
      dx: 0, dy: 0, generation: reader.showGeneration, minScale, maxScale,
      will: reader.sheetDiv.style.getPropertyValue('will-change') };
    reader.zoomPreview = true;reader.sheetDiv.style.willChange = 'transform';return true;
  }
  function reanchor(g, p, owner) {
    win.cancelAnimationFrame(frame);frame = 0;options.onIntent?.();
    const rect = host.getBoundingClientRect(),x = p.x - rect.left,y = p.y - rect.top;
    g.anchorX = (host.scrollLeft + x - g.left - g.dx) / g.presented;
    g.anchorY = (host.scrollTop + y - g.top - g.dy) / g.presented;
    g.rect = rect;g.scale = g.presented;g.distance = p.d;g.latest = p;g.owner = owner;
  }
  function commit(g, value = g.presented) {
    if (!valid(g)) {cancel();return;}
    win.cancelAnimationFrame(frame);frame = 0;
    gesture = null;reader.zoomPreview = false;reader.scale = clamp(value, g.minScale, g.maxScale);reader.updateExtent();
    const x = g.latest.x - g.rect.left,y = g.latest.y - g.rect.top;
    const desiredX = reader.sheetLeft + g.anchorX * reader.scale - x,desiredY = reader.topInset + g.anchorY * reader.scale - y;
    const maxX = Math.max(0, parseFloat(reader.spacer.style.width) - host.clientWidth),maxY = Math.max(0, parseFloat(reader.spacer.style.height) - host.clientHeight);
    const left = clamp(desiredX, 0, maxX),top = clamp(desiredY, 0, maxY);
    host.scrollTo({ left, top, behavior: 'instant' });restore(g);


    options.onWrite?.();reader.scheduleVisible();options.onCommit?.();
  }
  function stop() {return run(() => {const g = gesture;if (g?.owner === 'button') commit(g);else cancel();});}
  function touchPreview() {frame = 0;run(() => {const g = gesture;if (!g || g.owner !== 'touch') return;
      g.target = clamp(g.scale * g.latest.d / g.distance, g.minScale, g.maxScale);paint(g, g.target);});}
  function animate(now) {
    frame = 0;run(() => {const g = gesture;if (!g || g.owner !== 'button') return;if (!valid(g)) {cancel();return;}
      if (!smooth()) {paint(g, g.target);commit(g, g.target);return;}
      if (now - g.started > 1600) {commit(g);return;}
      g.elapsed += Math.min(48, Math.max(0, now - g.last));g.last = now;
      const t = Math.min(1, g.elapsed / 180),ease = t * t * (3 - 2 * t);
      paint(g, t === 1 ? g.target : g.from + (g.target - g.from) * ease);
      if (t === 1) commit(g, g.target);else if (valid(g)) frame = win.requestAnimationFrame(animate);
    });
  }
  function setAt(value, center) {return run(() => {
      if (!live() || !reader.model || !Number.isFinite(value) || center && (center.x !== undefined && !Number.isFinite(center.x) || center.y !== undefined && !Number.isFinite(center.y))) return false;let g = gesture;
      let bounds;
      if (!g) {
        const viewportWidth = host.clientWidth,maxScale = reader.getMaximumScale(),minScale = reader.getMinimumScale(reader.sheetIndex, viewportWidth);
        bounds = { viewportWidth, minScale, maxScale };
        if (clamp(value, minScale, maxScale) === reader.scale) return true;
      } else if (g.owner === 'button' && clamp(value, g.minScale, g.maxScale) === g.target &&
      (center?.x ?? g.latest.x) === g.latest.x && (center?.y ?? g.latest.y) === g.latest.y) return true;
      const r = g?.rect || host.getBoundingClientRect();
      const p = { x: center?.x ?? g?.latest.x ?? r.left + host.clientWidth / 2,
        y: center?.y ?? g?.latest.y ?? r.top + reader.topInset + Math.max(0, host.clientHeight - reader.topInset - reader.bottomInset) / 2, d: 1 };
      if (!g) {if (!begin(p, 'button', bounds)) return;g = gesture;} else
      {if (g.owner !== 'button') {reanchor(g, p, 'button');} else if (center) reanchor(g, p, 'button');win.cancelAnimationFrame(frame);frame = 0;}
      g.target = clamp(value, g.minScale, g.maxScale);g.from = g.presented;g.started = g.last = win.performance.now();g.elapsed = 0;
      if (!smooth()) {paint(g, g.target);commit(g, g.target);} else frame = win.requestAnimationFrame(animate);
      return true;
    });}
  function step(factor) {return setAt((gesture?.target ?? reader.scale) * factor);}
  function start(event) {run(() => {
      if (!live()) return;
      if (event.touches.length > 2) {cancel();return;}
      if (event.touches.length === 1) {if (gesture?.owner === 'button') stop();return;}
      if (event.touches.length !== 2) return;
      if (event.cancelable) event.preventDefault();
      const p = point(event),g = gesture;
      if (g?.owner === 'button') {reanchor(g, p, 'touch');g.target = g.presented;} else
      if (!g) begin(p, 'touch');
    });}
  function move(event) {run(() => {
      const g = gesture;if (!g || g.owner !== 'touch') return;
      if (event.touches.length > 2) {cancel();return;}if (event.touches.length !== 2) return;
      if (event.cancelable) event.preventDefault();g.latest = point(event);if (!frame) frame = win.requestAnimationFrame(touchPreview);
    });}
  function end(event) {run(() => {const g = gesture;if (g?.owner === 'touch' && event.touches.length < 2) {win.cancelAnimationFrame(frame);frame = 0;touchPreview();if (gesture === g) commit(g);}});}
  function interrupted() {run(cancel);}
  function scrolled() {const g = gesture;if (g?.owner === 'touch') {if (!frame) frame = win.requestAnimationFrame(touchPreview);}}
  function preferenceChanged() {if (!smooth() && gesture?.owner === 'button') run(() => {const g = gesture;paint(g, g.target);commit(g, g.target);});}
  host.addEventListener('touchstart', start, { passive: false });host.addEventListener('touchmove', move, { passive: false });
  host.addEventListener('touchend', end, { passive: true });host.addEventListener('touchcancel', interrupted, { passive: true });host.addEventListener('scroll', scrolled, { passive: true });
  host.addEventListener('wheel', stop, { passive: true });reduced?.addEventListener?.('change', preferenceChanged);
  return { setAt, step, stop, cancel: () => run(cancel), motionChanged: preferenceChanged, get active() {return !!gesture;},

    destroy() {if (closed) return;run(cancel);closed = true;host.removeEventListener('touchstart', start);host.removeEventListener('touchmove', move);
      host.removeEventListener('touchend', end);host.removeEventListener('touchcancel', interrupted);host.removeEventListener('scroll', scrolled);host.removeEventListener('wheel', stop);reduced?.removeEventListener?.('change', preferenceChanged);} };
}
