/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */
import { CONTRACT, normalize, sameIds, failure } from './night-protocol.mjs';
import { CORE_SHA256 } from './night-core.mjs';
import { renderClipCanary, compareClipCanaries } from './night-clip.mjs';
const releaseBitmap = (value) => {
  if (!(value instanceof ImageBitmap)) return false;
  try {ImageBitmap.prototype.close.call(value);} catch (_) {}
  return true;
};


















export class NightPipeline {
  constructor() {
    this.worker = null;this.active = null;this.nextId = 0;
    this.closed = false;this.disabled = false;this.reason = null;

    this.clipState = 0;



  }
  available() {
    if (this.closed || this.disabled) return false;
    if (typeof Worker !== 'function' || typeof OffscreenCanvas !== 'function' || typeof createImageBitmap !== 'function') {
      this.disabled = true;this.reason = 'capability';return false;
    }
    return true;
  }
  current(job) {
    try {return !this.closed && !job.cancelled && job.current() === true;} catch (_) {return false;}
  }

















































  ensureWorker() {
    if (this.worker) return this.ready;

    this.ready = new Promise((resolve, reject) => {this.readyResolve = resolve;this.readyReject = reject;});
    try {
      const worker = this.worker = new Worker(new URL('./night-worker.mjs', import.meta.url), { type: 'module', name: 'pdf-night' });
      worker.onmessage = (event) => {
        if (worker !== this.worker) {this.discardBitmap(event.data?.bitmap);return;}
        const m = event.data || {};
        if (m.type === 'ready') {
          this.discardBitmap(m.bitmap);
          if (m.core !== CORE_SHA256 || m.offscreen !== true) {this.fail('worker-capability');return;}
          if (!this.clipState) {


            try {
              const dom = renderClipCanary((w, h) => {const c = document.createElement('canvas');c.width = w;c.height = h;return c;});
              const comparison = compareClipCanaries(dom, m.clipCanary);
              this.clipState = comparison.compatible ? 1 : 2;

            } catch (_) {this.clipState = 2;}

          }
          this.readyResolve?.();this.readyResolve = this.readyReject = null;return;
        }
        this.receive(m);
      };
      worker.onerror = (event) => {event.preventDefault();if (worker === this.worker) this.fail('worker-error');};
      worker.onmessageerror = () => {if (worker === this.worker) this.fail('worker-message');};
    } catch (_) {this.fail('worker-construction');}
    return this.ready;
  }
  prepare(source, spec, current) {

    if (!this.available()) {return Promise.reject(failure('disabled'));}
    if (this.active) {return Promise.reject(failure('busy'));}
    let request;
    try {
      request = normalize({ ...spec, width: source.width, height: source.height });
      const attributes = source.getContext('2d')?.getContextAttributes?.();
      if (attributes?.colorSpace && attributes.colorSpace !== 'srgb' || attributes?.colorType && attributes.colorType !== 'unorm8') throw failure('source-format');
    } catch (error) {this.disabled = true;this.reason = error.code || 'input';return Promise.reject(error);}
    const job = { id: ++this.nextId, request, source, current, phase: 'starting', cancelled: false, releases: [], output: null };
    const promise = new Promise((resolve, reject) => {job.resolve = resolve;job.reject = reject;});
    this.active = job;

    job.timer = setTimeout(() => {this.fail('timeout');}, 10000);
    void this.capture(job);return promise;
  }
  async capture(job) {
    let snapshot = null;
    try {
      await this.ensureWorker();
      if (!this.current(job) || this.active !== job) throw failure('cancelled');
      job.phase = 'capturing';
      const pending = createImageBitmap(job.source);


      snapshot = await pending;
      job.phase = 'captured';this.releaseDeferred(job);
      if (!this.current(job) || this.active !== job || !this.worker) throw failure(job.code || 'cancelled');
      if (snapshot.width !== job.request.width || snapshot.height !== job.request.height) throw failure('snapshot-size');
      this.worker.postMessage({ type: 'run', jobId: job.id, ...job.request, clipCompatible: this.clipState === 1, bitmap: snapshot }, [snapshot]);
      snapshot = null;job.phase = 'worker';
    } catch (error) {
      releaseBitmap(snapshot);
      job.phase = 'failed';this.releaseDeferred(job);
      if (error.code !== 'cancelled' && !job.cancelled) {this.disabled = true;this.reason = error.code || 'snapshot';this.terminate();}
      this.finish(job, failure(job.code || error.code || 'snapshot'));
    }
  }
  receive(m) {
    const job = this.active;
    if (!job || m.jobId !== job.id) {this.discardBitmap(m.bitmap);return;}
    if (m.type === 'error') {
      this.discardBitmap(m.bitmap);
      if (!sameIds(m.ids, job.request.ids)) {this.fail('error-identity');return;}
      if (m.code === 'unsupported-clip' && !job.cancelled) {} else
      if (m.code !== 'cancelled' && !job.cancelled) {this.disabled = true;this.reason = m.code || 'job-error';this.terminate();}
      const error = failure(job.cancelled ? 'cancelled' : m.code || 'job-error');
      if (m.code === 'unsupported-clip' && job.request.kind === 'base' && this.current(job) &&
      m.contract === CONTRACT && m.core === CORE_SHA256 && Array.isArray(m.pictures) &&
      m.pictures.length === job.request.quads.length && m.pictures.every((v) => typeof v === 'boolean')) {
        error.pictures = Object.freeze(m.pictures.slice());
      }
      this.finish(job, error);return;
    }

    if (m.type !== 'result' || !(m.bitmap instanceof ImageBitmap) || m.bitmap.width !== job.request.width ||
    m.bitmap.height !== job.request.height || !sameIds(m.ids, job.request.ids) || m.contract !== CONTRACT || m.core !== CORE_SHA256 ||
    !Array.isArray(m.pictures) || m.pictures.length !== job.request.quads.length || !m.pictures.every((v) => typeof v === 'boolean')) {
      releaseBitmap(m.bitmap);
      this.fail('result-contract');return;
    }
    if (!this.current(job)) {releaseBitmap(m.bitmap);this.finish(job, failure('cancelled'));return;}
    clearTimeout(job.timer);job.output = m.bitmap;job.phase = 'prepared';

    const handle = {
      pictures: m.pictures,
      commit: (ctx) => {
        if (!this.current(job) || this.active !== job || !job.output ||
        ctx.canvas.width !== job.request.width || ctx.canvas.height !== job.request.height) throw failure('cancelled');
        ctx.save();
        try {
          ctx.setTransform(1, 0, 0, 1, 0, 0);ctx.globalAlpha = 1;ctx.globalCompositeOperation = 'copy';ctx.filter = 'none';
          ctx.drawImage(job.output, 0, 0);
        } finally {ctx.restore();}
      },
      dispose: () => {this.closeOutput(job);this.finish(job);}
    };
    job.handle = handle;

    job.resolve(handle);
  }
  closeOutput(job) {releaseBitmap(job.output);job.output = null;}
  discardBitmap(value) {releaseBitmap(value);}
  releaseDeferred(job) {const releases = job.releases.splice(0);for (const canvas of releases) canvas.width = canvas.height = 0;}
  deferRelease(canvas) {
    const job = this.active;
    if (job?.phase !== 'capturing' || job.source !== canvas) return false;
    if (!job.releases.includes(canvas)) job.releases.push(canvas);
    return true;
  }
  cancel() {
    const job = this.active;if (!job || job.cancelled) return;
    job.cancelled = true;job.code = 'cancelled';
    if (job.phase === 'prepared') {job.handle.dispose();return;}
    if (job.phase === 'worker') {
      try {this.worker.postMessage({ type: 'cancel', jobId: job.id, ids: job.request.ids });}
      catch (_) {this.fail('cancel-post');return;}
      clearTimeout(job.timer);
      job.timer = setTimeout(() => {this.fail('cancel-timeout');}, 1500);
    } else if (job.phase === 'starting') {
      clearTimeout(job.timer);job.timer = setTimeout(() => this.fail('cancel-timeout'), 1500);
    } else if (job.phase !== 'capturing') {this.terminate();this.finish(job, failure('cancelled'));}
  }
  terminate() {
    this.worker?.terminate();this.worker = null;
    this.readyReject?.(failure('terminated'));this.readyResolve = this.readyReject = null;
  }
  fail(code) {
    this.disabled = true;this.reason = code;const job = this.active;this.terminate();
    if (!job) return;
    job.code = job.cancelled ? 'cancelled' : code;job.cancelled ||= this.closed;
    this.closeOutput(job);
    if (job.phase !== 'capturing') this.finish(job, failure(job.code));
  }
  finish(job, error) {
    if (this.active !== job) return;

    clearTimeout(job.timer);this.closeOutput(job);this.releaseDeferred(job);
    this.active = null;job.source = null;
    if (error) job.reject(error);
  }
  close() {if (this.closed) return;this.closed = true;this.cancel();this.fail('closed');}





}
