/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */
(() => {
  'use strict';











  const MiB = 1024 * 1024;
  const limits = Object.freeze({ sources: 64, elements: 256, sourceBytes: 8 * MiB,
    packageImageBytes: 16 * MiB, renderedImageBytes: 32 * MiB, pixelsPerImage: 8 * MiB,
    renderedPixels: 32 * MiB, side: 8192, xmlPartBytes: 2 * MiB, xmlBytes: 8 * MiB,
    parts: 128, jobs: 4096, cssRules: 5000, workMs: 10000 });
  const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
  const A = 'http://schemas.openxmlformats.org/drawingml/2006/main';
  const R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const V = 'urn:schemas-microsoft-com:vml';
  const MC = 'http://schemas.openxmlformats.org/markup-compatibility/2006';
  const imageError = (feature) => Object.assign(new Error('Image needs original reader'), { officeImageFeature: feature });
  const reject = (feature) => {throw imageError(feature);};
  const local = (node, name, ns) => node.localName === name && (!ns || node.namespaceURI === ns);
  const descendants = (node) => [...(node.getElementsByTagName?.('*') || [])];
  const attr = (node, name, ns) => ns ? node.getAttributeNS(ns, name) : node.getAttribute(name);
  const mime = { png: ['image/png', 'image/x-png'], jpeg: ['image/jpeg', 'image/jpg'], gif: ['image/gif'],
    bmp: ['image/bmp', 'image/x-ms-bmp', 'image/x-bmp'], webp: ['image/webp'] };
  const extension = (name) => {const ext = name.split('.').at(-1).toLowerCase();return ext === 'jpg' || ext === 'jpe' ? 'jpeg' : ext;};
  function isSelected(node, story) {
    for (let child = node; child && child !== story; child = child.parentElement) {
      if (local(child, 'Choice', MC)) return false;
      const parent = child.parentElement;
      if (parent && local(parent, 'Fallback', MC) && parent.firstElementChild !== child) return false;
    }
    return true;
  }
  function header(bytes) {
    const d = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    if (d.byteLength < 10) reject('imageCorruptHeader');
    const v = new DataView(d.buffer, d.byteOffset, d.byteLength),ascii = (at, n) => String.fromCharCode(...d.subarray(at, at + n));
    let width = 0,height = 0,type = '';
    if (d.length >= 24 && v.getUint32(0) === 0x89504e47 && v.getUint32(4) === 0x0d0a1a0a) {
      if (v.getUint32(8) !== 13 || ascii(12, 4) !== 'IHDR') reject('imageCorruptHeader');
      type = 'png';width = v.getUint32(16);height = v.getUint32(20);
    } else if (d[0] === 255 && d[1] === 216) {
      type = 'jpeg';let at = 2;
      while (at + 3 < d.length) {
        if (d[at++] !== 255) reject('imageCorruptHeader');while (d[at] === 255) at++;
        const marker = d[at++];if (marker === 217 || marker === 218) break;
        if (marker === 1 || marker === 216 || marker >= 208 && marker <= 215) continue;
        if (at + 2 > d.length) break;const size = v.getUint16(at);
        if (size < 2 || at + size > d.length) reject('imageCorruptHeader');
        if ([192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207].includes(marker)) {
          if (size < 8) reject('imageCorruptHeader');height = v.getUint16(at + 3);width = v.getUint16(at + 5);break;
        }at += size;
      }
    } else if (ascii(0, 6) === 'GIF87a' || ascii(0, 6) === 'GIF89a') {
      type = 'gif';width = v.getUint16(6, true);height = v.getUint16(8, true);
    } else if (ascii(0, 2) === 'BM' && d.length >= 26) {
      type = 'bmp';const dib = v.getUint32(14, true);
      if (dib === 12) {width = v.getUint16(18, true);height = v.getUint16(20, true);} else
      if (dib >= 40 && d.length >= 30) {width = v.getInt32(18, true);height = Math.abs(v.getInt32(22, true));}
    } else if (ascii(0, 4) === 'RIFF' && ascii(8, 4) === 'WEBP') {
      type = 'webp';const end = v.getUint32(4, true) + 8;if (end > d.length) reject('imageCorruptHeader');
      for (let at = 12; at + 8 <= end;) {
        const tag = ascii(at, 4),size = v.getUint32(at + 4, true),p = at + 8;if (p + size > end) reject('imageCorruptHeader');
        if (tag === 'VP8X' && size >= 10) {
          width = 1 + d[p + 4] + (d[p + 5] << 8) + (d[p + 6] << 16);height = 1 + d[p + 7] + (d[p + 8] << 8) + (d[p + 9] << 16);break;
        }
        if (tag === 'VP8 ' && size >= 10 && ascii(p + 3, 3) === '\x9d\x01\x2a') {
          width = v.getUint16(p + 6, true) & 16383;height = v.getUint16(p + 8, true) & 16383;break;
        }
        if (tag === 'VP8L' && size >= 5 && d[p] === 47) {const bits = v.getUint32(p + 1, true);width = (bits & 16383) + 1;height = (bits >>> 14 & 16383) + 1;break;}
        at = p + size + (size & 1);
      }
    } else reject('imageFormatRequiresLegacy');
    if (!(width > 0 && height > 0 && width <= limits.side && height <= limits.side && width * height <= limits.pixelsPerImage)) reject('imagePixelLimit');
    return { type, width, height, pixels: width * height };
  }
  function resolve(part, target) {
    if (!target || /[\\%?#\u0000-\u0020]/.test(target) || /^[a-z][a-z0-9+.-]*:/i.test(target)) reject('imageTargetPath');
    const out = target.startsWith('/') ? [] : part.split('/').slice(0, -1);
    for (const item of target.split('/')) {
      if (!item || item === '.') continue;
      if (item === '..') {if (!out.length) reject('imageTargetPath');out.pop();} else out.push(item);
    }
    return out.join('/');
  }
  function paperPngHeader(bytes, w, h) {
    if (bytes.length < 45 || bytes.length > 2 * MiB) return false;
    const signature = [137, 80, 78, 71, 13, 10, 26, 10];
    for (let i = 0; i < 8; i++) if (bytes[i] !== signature[i]) return false;
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);let sawData = false;
    for (let p = 8, chunks = 0; p <= bytes.length - 12 && chunks < 4096; chunks++) {
      const length = view.getUint32(p),type = String.fromCharCode(bytes[p + 4], bytes[p + 5], bytes[p + 6], bytes[p + 7]);
      if (length > bytes.length - p - 12 || type === 'acTL' || type === 'fdAT') return false;
      if (p === 8 && (type !== 'IHDR' || length !== 13 || view.getUint32(p + 8) !== w || view.getUint32(p + 12) !== h)) return false;
      if (type === 'IDAT') sawData = true;
      if (type === 'IEND') return length === 0 && sawData && p + 12 === bytes.length;
      p += length + 12;
    }
    return false;
  }
  function create({ guard, getSignal, fallback }) {
    const blobs = new Map();let headers = new WeakMap();
    let digestJobs = 0,digestBytes = 0,digestSpent = 0,digestEpoch = 0,digestPending = false,digestDisabled = false,digestCancel = null;
    function live() {guard();}
    function fail(error) {live();fallback(error?.officeImageFeature || 'imageReadinessFailure');throw error;}
    async function fingerprint(data, end, epoch) {
      if (digestDisabled || digestPending || epoch !== digestEpoch) return null;
      const delay = Math.min(40, 200 - digestSpent, end - performance.now());
      if (!(delay > 0)) return null;
      const signal = getSignal?.();if (signal?.aborted) return null;
      const start = performance.now();let operation;
      try {
        const subtle = globalThis.crypto?.subtle;
        if (typeof subtle?.digest !== 'function') {digestDisabled = true;return null;}
        operation = Promise.resolve(subtle.digest('SHA-256', data));digestPending = true;
      } catch (_) {digestDisabled = true;return null;}
      return new Promise((resolve) => {
        let settled = false,timer;
        const finish = (value) => {
          if (settled) return;settled = true;clearTimeout(timer);
          signal?.removeEventListener('abort', cancel);
          if (digestCancel === cancel) digestCancel = null;
          digestSpent += Math.max(0, performance.now() - start);resolve(value);
        };
        const cancel = () => {digestDisabled = true;finish(null);};digestCancel = cancel;
        timer = setTimeout(cancel, delay);signal?.addEventListener('abort', cancel, { once: true });
        operation.then((value) => {
          digestPending = false;if (settled) return;
          if (epoch !== digestEpoch || signal?.aborted) {finish(null);return;}
          try {
            live();if (!(value instanceof ArrayBuffer) || value.byteLength !== 32) throw Error('identity');
            finish(Array.from(new Uint8Array(value), (v) => v.toString(16).padStart(2, '0')).join(''));
          } catch (_) {digestDisabled = true;finish(null);}
        }, () => {digestPending = false;digestDisabled = true;finish(null);});
        if (signal?.aborted) cancel();
      });
    }
    function bounded(promise, end) {
      live();const signal = getSignal?.();const delay = end - performance.now();
      if (!(delay > 0)) return Promise.reject(imageError('imageTimeLimit'));
      return new Promise((resolve, rejectPromise) => {
        let timer;const abort = () => finish(false, imageError('imageCancelled'));
        const finish = (ok, value) => {clearTimeout(timer);signal?.removeEventListener('abort', abort);ok ? resolve(value) : rejectPromise(value);};
        timer = setTimeout(() => finish(false, imageError('imageTimeLimit')), delay);
        if (signal?.aborted) {abort();return;}signal?.addEventListener('abort', abort, { once: true });
        Promise.resolve(promise).then((v) => finish(true, v), (e) => finish(false, e));
      });
    }
    async function admit(archive) {
      const end = performance.now() + limits.workMs,documents = new Map(),relations = new Map(),seen = new Set(),images = new Map();
      let xmlBytes = 0,imageBytes = 0,sourcePixels = 0;
      async function xml(name, required = true) {
        live();if (documents.has(name)) return documents.get(name);
        const file = archive.file(name);if (!file) {if (required) reject('imageRelationshipPartMissing');return null;}
        if (file._data?.uncompressedSize > limits.xmlPartBytes) reject('imageXmlLimit');
        const data = await bounded(file.async('uint8array'), end);xmlBytes += data.byteLength;
        if (data.byteLength > limits.xmlPartBytes || xmlBytes > limits.xmlBytes || documents.size >= limits.parts) reject('imageXmlLimit');
        const text = new TextDecoder().decode(data);if (/<!DOCTYPE|<!ENTITY/i.test(text)) reject('imageXmlDeclaration');
        const doc = new DOMParser().parseFromString(text, 'application/xml');
        if (doc.getElementsByTagName('parsererror').length) reject('imageXmlInvalid');documents.set(name, doc);return doc;
      }
      async function rels(part) {
        if (relations.has(part)) return relations.get(part);
        const at = part.lastIndexOf('/'),name = part.slice(0, at + 1) + '_rels/' + part.slice(at + 1) + '.rels';
        const doc = await xml(name, false),map = new Map();
        for (const node of doc ? descendants(doc) : []) {if (!local(node, 'Relationship')) continue;
          const id = attr(node, 'Id');if (!id || map.has(id)) reject('imageRelationshipDuplicate');
          map.set(id, { type: attr(node, 'Type') || '', target: attr(node, 'Target') || '', external: (attr(node, 'TargetMode') || '').toLowerCase() === 'external' });
        }relations.set(part, map);return map;
      }
      try {
        const types = await xml('[Content_Types].xml'),defaults = new Map(),overrides = new Map();
        for (const node of descendants(types)) {
          if (local(node, 'Default')) defaults.set((attr(node, 'Extension') || '').toLowerCase(), (attr(node, 'ContentType') || '').toLowerCase());
          if (local(node, 'Override')) overrides.set((attr(node, 'PartName') || '').replace(/^\//, ''), (attr(node, 'ContentType') || '').toLowerCase());
        }
        async function source(part, id) {
          const relation = (await rels(part)).get(id);
          if (!relation || !id) reject('imageRelationshipMissing');
          if (relation.external) reject('imageExternalTarget');
          if (!/\/image$/.test(relation.type)) reject('imageRelationshipType');
          const target = resolve(part, relation.target),file = archive.file(target);
          if (!file) reject('imageTargetMissing');if (images.has(target)) return;
          const ext = extension(target),declared = overrides.get(target) || defaults.get(target.split('.').at(-1).toLowerCase());
          if (!mime[ext] || !declared || !mime[ext].includes(declared)) reject('imageFormatRequiresLegacy');
          if (file._data?.uncompressedSize > limits.sourceBytes) reject('imageByteLimit');
          const data = await bounded(file.async('uint8array'), end);imageBytes += data.byteLength;
          if (data.byteLength > limits.sourceBytes || imageBytes > limits.packageImageBytes || images.size >= limits.sources) reject('imageByteLimit');
          const metadata = header(data);if (metadata.type !== ext) reject('imageFormatMismatch');sourcePixels += metadata.pixels;
          if (sourcePixels > limits.renderedPixels) reject('imagePixelLimit');images.set(target, metadata);
        }
        const queue = [{ part: 'word/document.xml' }],queued = new Set(['word/document.xml#']),scopeRoots = new Map(),usedNumbering = new Set();
        async function related(part, kind, id, scope) {
          const map = await rels(part),relation = id ? map.get(id) : [...map.values()].find((r) => r.type.endsWith('/' + kind));
          if (!relation || !relation.type.endsWith('/' + kind) || relation.external) reject('imageStoryRelationship');
          const target = resolve(part, relation.target),key = target + '#' + (scope ? scope.name + ':' + scope.id : '');
          if (queued.has(key)) return;if (queue.length >= limits.jobs) reject('imagePartLimit');queued.add(key);queue.push({ part: target, scope });
        }
        for (let next = 0; next < queue.length; next++) {
          live();const job = queue[next],key = job.part + '#' + (job.scope ? job.scope.name + ':' + job.scope.id : '');
          if (seen.has(key)) continue;seen.add(key);const doc = await xml(job.part);
          if (job.scope && !scopeRoots.has(job.part)) {
            const map = new Map();for (const n of descendants(doc)) if (n.namespaceURI === W && ['footnote', 'endnote'].includes(n.localName)) map.set(n.localName + ':' + attr(n, 'id', W), n);scopeRoots.set(job.part, map);
          }
          const selected = job.scope ? scopeRoots.get(job.part).get(job.scope.name + ':' + job.scope.id) : doc.documentElement;
          const roots = selected ? [selected] : [];
          if (!roots.length) reject('imageStoryMissing');
          for (const story of roots) for (const node of [story, ...descendants(story)]) {
            if (!isSelected(node, story)) continue;
            if (local(node, 'AlternateContent', MC) && ![...node.children].some((n) => local(n, 'Fallback', MC) && n.firstElementChild) && descendants(node).some((n) => local(n, 'blip', A))) reject('imageAlternateContentRequiresLegacy');
            if (local(node, 'blip', A)) {
              const id = attr(node, 'embed', R);if (attr(node, 'link', R) || !id) reject('imageRelationshipMissing');await source(job.part, id);
            }
            if (local(node, 'headerReference', W)) await related(job.part, 'header', attr(node, 'id', R));
            if (local(node, 'footerReference', W)) await related(job.part, 'footer', attr(node, 'id', R));
            if (local(node, 'footnoteReference', W)) await related(job.part, 'footnotes', null, { name: 'footnote', id: attr(node, 'id', W) });
            if (local(node, 'endnoteReference', W)) await related(job.part, 'endnotes', null, { name: 'endnote', id: attr(node, 'id', W) });
            if (local(node, 'numId', W)) usedNumbering.add(attr(node, 'val', W));
            if (local(node, 'imagedata', V)) await source(job.part, attr(node, 'id', R));
          }
        }
        if (usedNumbering.size) {
          const map = await rels('word/document.xml'),r = [...map.values()].find((v) => v.type.endsWith('/numbering'));
          if (r && !r.external) {
            const part = resolve('word/document.xml', r.target),doc = await xml(part),abstracts = new Set(),pictures = new Set();
            for (const num of descendants(doc).filter((n) => local(n, 'num', W) && usedNumbering.has(attr(n, 'numId', W))))
            for (const n of descendants(num)) if (local(n, 'abstractNumId', W)) abstracts.add(attr(n, 'val', W));
            for (const num of descendants(doc).filter((n) => local(n, 'abstractNum', W) && abstracts.has(attr(n, 'abstractNumId', W))))
            for (const n of descendants(num)) if (local(n, 'lvlPicBulletId', W)) pictures.add(attr(n, 'val', W));
            for (const picture of descendants(doc).filter((n) => local(n, 'numPicBullet', W) && pictures.has(attr(n, 'numPicBulletId', W))))
            for (const n of descendants(picture)) if (local(n, 'imagedata', V)) await source(part, attr(n, 'id', R));
          }
        }
        live();return { count: images.size, bytes: imageBytes, pixels: sourcePixels };
      } catch (error) {fail(error);}
    }
    async function wait(container) {

      {
        const end = performance.now() + limits.workMs,epoch = digestEpoch,identities = [],items = [...container.querySelectorAll('img')].map((image) => ({ image, src: image.getAttribute('src') }));
        const cssSources = new Set();let rules = 0;
        function css(style) {for (const property of ['background-image', 'list-style-image', 'border-image-source']) {
            const value = style.getPropertyValue(property) || '';
            for (const m of value.matchAll(/url\(\s*(?:"([^"]*)"|'([^']*)'|([^)]*))\s*\)/gi)) cssSources.add((m[1] ?? m[2] ?? m[3]).trim());
          }}
        function scan(list) {for (const rule of list) {if (++rules > limits.cssRules) reject('imageCssLimit');
            if (rule.cssRules?.length) scan(rule.cssRules);if (!rule.style || !rule.selectorText) continue;
            const selector = rule.selectorText.replace(/::?(before|after|marker)\b/g, '');
            let matched = false;try {matched = !!container.querySelector(selector);} catch (_) {}
            if (matched) css(rule.style);
          }}
        try {
          live();
          for (const el of container.querySelectorAll('svg image')) cssSources.add(el.getAttribute('href') || el.getAttribute('xlink:href') || '');
          for (const el of container.querySelectorAll('[style]')) css(el.style);
          for (const el of container.querySelectorAll('style')) if (el.sheet) scan(el.sheet.cssRules);
          if (items.length + cssSources.size > limits.elements) reject('imageElementLimit');
          for (const src of cssSources) {const image = new Image();items.push({ image, src, css: true });}
          let bytes = 0,pixels = 0;const distinct = new Set();
          for (const item of items) {
            live();const blob = blobs.get(item.src);if (!blob) reject('imageUrlMissing');
            if (!distinct.has(item.src)) {bytes += blob.size;distinct.add(item.src);}
            if (blob.size > limits.sourceBytes || bytes > limits.renderedImageBytes) reject('imageByteLimit');
            let metadata = headers.get(blob);
            if (!metadata) {
              const data = new Uint8Array(await bounded(blob.arrayBuffer(), end));metadata = header(data);
              metadata.paperPng = metadata.type === 'png' && metadata.pixels <= 350000 && metadata.width <= 2048 && metadata.height <= 2048 && paperPngHeader(data, metadata.width, metadata.height);
              if (epoch === digestEpoch) {
                headers.set(blob, metadata);
                if (!digestDisabled && !digestPending && digestJobs < 32 && data.byteLength === blob.size && data.byteLength <= 2 * MiB && data.byteLength <= 4 * MiB - digestBytes &&
                metadata.width >= 8 && metadata.height >= 8 && metadata.width <= 2048 && metadata.height <= 2048 && metadata.pixels <= 350000 && (metadata.paperPng || metadata.type === 'jpeg')) {
                  digestJobs++;digestBytes += data.byteLength;identities.push({ data, metadata });
                }
              }
            }
            pixels += metadata.pixels;if (pixels > limits.renderedPixels) reject('imagePixelLimit');
          }
          for (const item of items) {
            live();const image = item.image;if (item.css) image.src = item.src;
            const complete = typeof image.decode === 'function' ? image.decode() : new Promise((resolve, rejectPromise) => {
              if (image.complete) {image.naturalWidth ? resolve() : rejectPromise(imageError('imageDecodeFailure'));return;}
              image.addEventListener('load', resolve, { once: true });image.addEventListener('error', () => rejectPromise(imageError('imageDecodeFailure')), { once: true });
            });
            try {await bounded(complete, end);} catch (error) {if (error?.officeImageFeature) throw error;reject('imageDecodeFailure');}
            live();if (!image.naturalWidth || !image.naturalHeight || image.naturalWidth * image.naturalHeight > limits.pixelsPerImage) reject('imageDecodeFailure');
            if (!item.css && image.getAttribute('src') !== item.src) reject('imageOwnerChanged');
            if (item.css) image.removeAttribute('src');
          }
          for (const identity of identities) {
            const digest = await fingerprint(identity.data, end, epoch);identity.data = null;
            if (epoch === digestEpoch) identity.metadata.digest = digest;
          }
          live();
        } catch (error) {fail(error);}
      }

    }
    function paper(image) {
      try {
        live();const src = image.getAttribute('src'),blob = blobs.get(src),metadata = blob && headers.get(blob);
        if (!metadata || !['png', 'jpeg'].includes(metadata.type) || !image.complete || image.hasAttribute('srcset') || image.currentSrc !== src ||
        image.naturalWidth !== metadata.width || image.naturalHeight !== metadata.height) return null;
        return Object.freeze({ bytes: blob.size, width: metadata.width, height: metadata.height, blob, type: metadata.type === 'png' ? 'image/png' : 'image/jpeg', provenance: 'archive-raster', digest: metadata.digest || null });
      } catch (_) {return null;}
    }
    return Object.freeze({ admit, wait, paper, track: (url, blob) => blobs.set(url, blob), release: (url) => blobs.delete(url), clear: () => {digestEpoch++;digestDisabled = true;digestCancel?.();blobs.clear();headers = new WeakMap();} });
  }
  globalThis.OfficeDocxImages = Object.freeze({ create, header, resolve, limits, paperPngHeader });
})();
