/* Modifications Copyright (C) 2026 Ettacent */

export class RasterCanvasFactory {
  constructor({ ownerDocument = document, enableHWA = false, canRetain = () => true } = {}) {
    this.document = ownerDocument;
    this.enableHWA = enableHWA;
    this.pool = [];
    this.cachedPixels = 0;
    this.budgetPixels = 0;
    this.canRetain = canRetain;
    this.closed = false;
  }
  create(width, height) {
    if (!(width > 0 && height > 0)) throw new Error('Invalid canvas size');
    let item;
    while (this.pool.length) {
      item = this.pool.pop();
      this.cachedPixels -= item.canvas.width * item.canvas.height;
      if (!item.context.isContextLost?.()) break;
      item.canvas.width = item.canvas.height = 0;
      item = null;
    }
    if (!item) item = { canvas: this.document.createElement('canvas'), context: null };
    this.reset(item, width, height);
    if (!item.context) item.context = item.canvas.getContext('2d', { willReadFrequently: !this.enableHWA });
    return item;
  }
  reset(item, width, height) {
    if (!item.canvas || !(width > 0 && height > 0)) throw new Error('Invalid canvas reset');
    item.canvas.width = width;
    item.canvas.height = height;
  }
  destroy(item) {
    const canvas = item.canvas;
    if (!canvas) throw new Error('Canvas is not specified');
    const pixels = canvas.width * canvas.height;
    const target = this.cachedPixels + pixels;
    const reservation = Math.max(this.budgetPixels, Math.ceil(target / 16384) * 16384);
    let retain = !this.closed && this.pool.length < 4 && pixels > 0 && pixels <= 65536 && target <= 262144
      && item.context && !item.context.isContextLost?.();
    if (retain && reservation > this.budgetPixels) {
      try { retain = this.canRetain(reservation - this.budgetPixels) === true; } catch (_) { retain = false; }
    }
    if (retain) {
      canvas.width = canvas.width;
      this.pool.push({ canvas, context: item.context });
      this.cachedPixels = target;
      this.budgetPixels = reservation;
    } else canvas.width = canvas.height = 0;
    item.canvas = null;
    item.context = null;
  }
  trim() {
    for (const item of this.pool) item.canvas.width = item.canvas.height = 0;
    this.pool.length = 0;
    this.cachedPixels = 0;
    this.budgetPixels = 0;
  }
  close() { this.closed = true; this.trim(); }
}

export class RasterWorkBudget {
  constructor(now, moving) {
    this.now = now;
    this.moving = moving;
    this.pointers = new Set();
    this.until = 0;
  }
  value() { return this.pointers.size || this.now() < this.until || this.moving() ? 3 : 8; }
  move() { this.until = this.now() + 150; }
  clear() { this.pointers.clear(); this.until = 0; }
  listen(target, viewport) {
    const bindings = [];
    const on = (view, name, callback) => {
      if (!view) return;
      view.addEventListener(name, callback, { passive: true, capture: true });
      bindings.push([view, name, callback]);
    };
    on(target, 'pointerdown', e => { this.pointers.add(e.pointerId); this.move(); });
    for (const name of ['pointerup', 'pointercancel']) on(target, name, e => { this.pointers.delete(e.pointerId); this.move(); });
    for (const name of ['pointermove', 'wheel', 'scroll', 'resize']) on(target, name, () => this.move());
    for (const name of ['scroll', 'resize']) on(viewport, name, () => this.move());
    on(target, 'blur', () => this.clear());
    return () => { for (const [view, name, callback] of bindings) view.removeEventListener(name, callback, true); this.clear(); };
  }
}
