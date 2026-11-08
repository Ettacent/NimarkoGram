/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent. Gander MIT, LICENSE_GANDER_MIT.txt. */
export const STRIP_ROWS = 32;
export const SAMPLE_PIXELS = 65536;
export const CONTRACT = 'opaque-pdf-cpu-strips-v1';
export const identityKeys = ['document', 'palette', 'slot', 'render'];
export const failure = code => Object.assign(new Error(code), {code});
export const sameIds = (a, b) => !!a && !!b && identityKeys.every(k => a[k] === b[k]);
export function extraPixels(width, height) {
  const pixels = width * height;
  return Math.max(3 * pixels,
    2 * pixels + 2 * width * Math.min(STRIP_ROWS, height),
    2 * pixels + 2 * SAMPLE_PIXELS + 8192);
}
export function normalize(m) {
  const {width, height, kind} = m;
  if (!identityKeys.every(k => Number.isSafeInteger(m.ids?.[k]) && m.ids[k] >= 0)) throw failure('identities');
  if (![width, height].every(v => Number.isSafeInteger(v) && v > 0 && v <= 8192) ||
      !['base', 'tile'].includes(kind) || width * height > (kind === 'base' ? 4e6 : 3e6)) throw failure('dimensions');
  if (!Array.isArray(m.quads) || m.quads.length > 512) throw failure('quads');
  const quads = m.quads.map(q => {
    if (!Array.isArray(q) || q.length !== 8 || !q.every(v => Number.isFinite(v) && Math.abs(v) <= 1e7)) throw failure('quad');
    return q.slice();
  });
  if (!Array.isArray(m.matrix) || m.matrix.length !== 20 || !m.matrix.every(v => Number.isFinite(v) && Math.abs(v) <= 4) ||
      ![0, 0, 0, 1, 0].every((v, i) => m.matrix[15 + i] === v)) throw failure('matrix');
  if (kind === 'tile' && m.pictures !== null &&
      (!Array.isArray(m.pictures) || m.pictures.length !== quads.length || !m.pictures.every(v => typeof v === 'boolean'))) throw failure('pictures');
  return {width, height, kind, quads, ids: {...m.ids}, matrix: m.matrix.slice(),
    preserveImages: m.preserveImages !== false, pictures: kind === 'tile' ? m.pictures : null, clipCompatible: m.clipCompatible === true};
}
export function clipOutside(ctx, keep, w, h) {
  for (const q of keep) {
    ctx.beginPath(); ctx.rect(0, 0, w, h); ctx.moveTo(q[0], q[1]);
    for (let i = 2; i < 8; i += 2) ctx.lineTo(q[i], q[i + 1]);
    ctx.closePath(); ctx.clip('evenodd');
  }
}
export function remap(data, m) {
  for (let o = 0; o < data.length; o += 4) {
    const r = data[o] / 255, g = data[o + 1] / 255, b = data[o + 2] / 255;
    for (let row = 0; row < 3; row++) {
      const i = row * 5;
      data[o + row] = Math.max(0, Math.min(1, m[i] * r + m[i + 1] * g + m[i + 2] * b + m[i + 3] + m[i + 4])) * 255;
    }
  }
}
