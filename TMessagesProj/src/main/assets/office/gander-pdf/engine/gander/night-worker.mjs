/* Modifications Copyright (C) 2026 Ettacent */



import { pageSample, looksLikeDiagram, releaseProbe, CORE_SHA256 } from './night-core.mjs';
import { STRIP_ROWS, CONTRACT, normalize, sameIds, clipOutside, remap, failure } from './night-protocol.mjs';
import { renderClipCanary, clipAllowed } from './night-clip.mjs';
let active = null,closing = false;
const closeBitmap = (value) => {if (value instanceof ImageBitmap) ImageBitmap.prototype.close.call(value);};
const dropCanvas = (value) => {if (value) value.width = value.height = 0;};
const turnChannel = typeof MessageChannel === 'function' ? new MessageChannel() : null;
let turnResolve = null;
if (turnChannel) turnChannel.port1.onmessage = () => {
  const resolve = turnResolve;turnResolve = null;resolve?.();
};
const turn = () => new Promise((resolve) => {
  if (!turnChannel) {setTimeout(resolve, 0);return;}
  if (turnResolve) throw failure('yield-ownership');
  turnResolve = resolve;turnChannel.port2.postMessage(0);
});
const check = (job) => {if (closing || job.cancelled) throw failure('cancelled');};
async function run(job, bitmap, r) {
  let output = null,out = null,source = null,strip = null;


  let result,fallbackPictures = null;
  try {
    await turn();check(job);
    out = new OffscreenCanvas(r.width, r.height);
    const dst = out.getContext('2d', { colorSpace: 'srgb' });
    if (!dst) throw failure('offscreen-context');
    dst.drawImage(bitmap, 0, 0);
    let sample = null;

    if (r.kind === 'base' && r.preserveImages && r.quads.length) {
      try {sample = pageSample({ canvas: out }, r.quads);} catch (_) { }
    }


    const pictures = !r.preserveImages ? r.quads.map(() => false) : r.kind === 'tile' ?
    r.pictures || r.quads.map(() => true) : r.quads.map((q) => !looksLikeDiagram(sample, q));

    const sampled = sample !== null;
    sample = null;releaseProbe();
    const keep = r.quads.filter((q, i) => pictures[i]);
    if (!clipAllowed(keep, r.clipCompatible)) {
      if (r.kind === 'base' && sampled) fallbackPictures = pictures;
      throw failure('unsupported-clip');
    }

    await turn();check(job);
    source = new OffscreenCanvas(r.width, r.height);
    const src = source.getContext('2d', { willReadFrequently: true, colorSpace: 'srgb' });
    if (!src) throw failure('offscreen-context');
    src.drawImage(bitmap, 0, 0);closeBitmap(bitmap);bitmap = null;
    const rows = Math.min(STRIP_ROWS, r.height);
    strip = new OffscreenCanvas(r.width, rows);
    const upload = strip.getContext('2d', { willReadFrequently: true, colorSpace: 'srgb' });
    if (!upload) throw failure('offscreen-context');
    dst.save();
    try {
      dst.setTransform(1, 0, 0, 1, 0, 0);dst.globalAlpha = 1;dst.globalCompositeOperation = 'source-over';
      clipOutside(dst, keep, r.width, r.height);
      for (let y = 0; y < r.height; y += rows) {
        check(job);
        const h = Math.min(rows, r.height - y);
        let data = src.getImageData(0, y, r.width, h);

        for (let o = 3; o < data.data.length; o += 4) if (data.data[o] !== 255) throw failure('unsupported-alpha');
        remap(data.data, r.matrix);
        upload.putImageData(data, 0, 0);
        dst.drawImage(strip, 0, 0, r.width, h, 0, y, r.width, h);
        data = null;
        await turn();check(job);
      }
    } finally {dst.restore();}
    dropCanvas(source);source = null;dropCanvas(strip);strip = null;
    check(job);output = out.transferToImageBitmap();dropCanvas(out);out = null;

    result = { type: 'result', jobId: job.id, ids: r.ids, bitmap: output, pictures, contract: CONTRACT, core: CORE_SHA256 };
  } catch (error) {
    result = { type: 'error', jobId: job.id, ids: r.ids, code: error.code || 'worker-error' };
    if (error.code === 'unsupported-clip' && fallbackPictures && !job.cancelled && !closing) {
      result.pictures = fallbackPictures;
      result.contract = CONTRACT;
      result.core = CORE_SHA256;
    }
  } finally {
    closeBitmap(bitmap);dropCanvas(out);dropCanvas(source);dropCanvas(strip);releaseProbe();
    active = null;
  }
  if (result.type === 'result' && !closing && !job.cancelled) {
    try {self.postMessage(result, [output]);output = null;} finally {closeBitmap(output);}
  } else {
    closeBitmap(output);
    self.postMessage(result.type === 'result' ? { type: 'error', jobId: job.id, ids: r.ids, code: 'cancelled' } : result);
  }
  if (closing) self.close();
}
self.onmessage = (event) => {
  const m = event.data || {};
  if (m.type === 'cancel') {if (active && m.jobId === active.id && sameIds(m.ids, active.ids)) active.cancelled = true;return;}
  if (m.type === 'close') {closing = true;if (active) active.cancelled = true;else self.close();return;}
  if (m.type !== 'run') return;
  try {
    if (active || closing) throw failure('busy-or-closed');
    if (!Number.isSafeInteger(m.jobId) || m.jobId < 1 || !(m.bitmap instanceof ImageBitmap)) throw failure('input');
    const request = normalize(m);
    if (m.bitmap.width !== request.width || m.bitmap.height !== request.height) throw failure('bitmap-size');
    active = { id: m.jobId, ids: request.ids, cancelled: false };
    void run(active, m.bitmap, request);
  } catch (error) {
    closeBitmap(m.bitmap);self.postMessage({ type: 'error', jobId: m.jobId, ids: m.ids, code: error.code || 'message' });
  }
};
let clipCanary = null;
try {clipCanary = renderClipCanary((w, h) => new OffscreenCanvas(w, h));} catch (_) { }
self.postMessage({ type: 'ready', core: CORE_SHA256, offscreen: typeof OffscreenCanvas === 'function', clipCanary }, clipCanary ? [clipCanary.buffer] : []);
clipCanary = null;
