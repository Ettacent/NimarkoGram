/* Modifications Copyright (C) 2026 Ettacent */



(function (global) {if (global.OfficePaperConsumer || typeof document === "undefined") return;
  "use strict";
  var VW_MATTE_PIXELS = 350000;

  function vwPaperAssessment(exposure, luminance) {
    var result = { choice: 2, paper: luminance, exposed: 0, poor: 0, upper: 0, required: 0, achieved: 0, colour: null };
    if (!Array.isArray(exposure) || exposure.length !== 32 || !Number.isFinite(luminance) || luminance < 0 || luminance > 1) return result;
    var total = 0,poor = 0,upper = 0;
    for (var i = 0; i < 32; i++) {
      var count = exposure[i];
      if (!Number.isInteger(count) || count < 0 || count > VW_MATTE_PIXELS) return result;
      total += count;
      if (i > 6 || !count) continue;
      var nearest = Math.max(i / 32, Math.min((i + 1) / 32, luminance));
      if ((Math.max(nearest, luminance) + .05) / (Math.min(nearest, luminance) + .05) < 3) {
        poor += count;upper = Math.max(upper, (i + 1) / 32);
      }
    }
    if (!total || total > VW_MATTE_PIXELS) return result;
    result.exposed = total;result.poor = poor;result.upper = upper;
    if (!poor) {result.choice = 0;return result;}
    if (poor < Math.max(4, Math.ceil(total * .01))) return result;
    var required = 3 * (upper + .05) - .05;
    var encoded = required <= .0031308 ? required * 12.92 : 1.055 * Math.pow(required, 1 / 2.4) - .055;
    var gray = Math.min(255, Math.ceil(encoded * 255)),channel = gray / 255;
    var actual = channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    var hex = gray.toString(16).padStart(2, "0");
    result.choice = 1;result.required = required;result.achieved = (actual + .05) / (upper + .05);
    result.colour = "#" + hex + hex + hex;
    return result;
  }

  function vwPaperBacking(exposure, luminance) {
    return vwPaperAssessment(exposure, luminance).choice;
  }

  "use strict";

  var OfficePaperSession = function () {
    function create(owner) {
      if (!owner || ["run", "live", "epoch", "paused", "current"].some(function (k) {return typeof owner[k] !== "function";})) throw new TypeError("Owner contract");
      var epoch = owner.epoch(),closed = false,sources = new Map(),pixels = 0,bytes = 0,derived = 0;
      function live() {return !closed && owner.live() && owner.epoch() === epoch;}
      function original(source, status, reason) {return { status: status || "preserve", reason: reason || 0, blob: source.blob, width: source.width, height: source.height, variant: "original" };}
      function status(source, options, palette) {
        if (!live() || options?.signal?.aborted || palette !== undefined && !owner.current(palette)) return original(source, "cancelled", 1);
        if (owner.paused()) return original(source, "paused", 0);
        return null;
      }
      function admit(id, source) {
        if (!live() || !Number.isInteger(id) || id < 1 || id > 12 || !source || source.provenance !== "archive-raster" || !(source.blob instanceof Blob)) return false;
        if (sources.has(id)) {var s = sources.get(id);return s.blob === source.blob && s.width === source.width && s.height === source.height;}
        var w = source.width,h = source.height,n = w * h;
        if (sources.size >= 12 || !Number.isInteger(w) || !Number.isInteger(h) || w < 8 || h < 8 || w > 2048 || h > 2048 || n > 350000 || n > 1000000 - pixels || source.blob.size < 10 || source.blob.size > 2097152 || source.blob.size > 4194304 - bytes) return false;
        sources.set(id, { blob: source.blob, width: w, height: h, prepared: null, pending: null, night: null, composing: null, request: 0 });pixels += n;bytes += source.blob.size;return true;
      }
      function valid(result, s) {return result && result.type === 2 && result.width === s.width && result.height === s.height && typeof result.accepted === "boolean";}
      function output(result) {return result.blob instanceof Blob && result.blob.size > 0 && result.blob.size <= 2100000 && result.blob.type === "image/png";}
      function ensure(s) {
        if (s.prepared || s.pending) return s.pending || Promise.resolve();
        var task = async function () {
          try {
            var result = await owner.run({ op: "prepare", provenance: "archive-raster", blob: s.blob, w: s.width, h: s.height });
            if (!live()) return;
            if (result?.status === "paused" || result?.status === "cancelled") return;
            if (!valid(result, s)) throw new Error("result");
            if (result.accepted) {
              if (!output(result) || !Array.isArray(result.exposure) || result.exposure.length !== 32 || result.exposure.some(function (n) {return !Number.isInteger(n) || n < 0 || n > s.width * s.height;}) || result.exposure.reduce(function (a, b) {return a + b;}, 0) > s.width * s.height) throw new Error("matte");
              if (result.darkInkRevision !== 1 || !Number.isInteger(result.inkPixels) || result.inkPixels < 0 || result.inkPixels > s.width * s.height ||
              !Array.isArray(result.inkMin) || result.inkMin.length !== 3 || result.inkMin.some(function (v) {return !Number.isInteger(v) || v < 0 || v > 255;}) || (
              result.inkPixels ? !output({ blob: result.inkBlob }) : result.inkBlob !== null)) throw new Error("ink layer");
              var size = result.blob.size + (result.inkBlob?.size || 0);
              if (size > 6291456 - derived) {s.prepared = { accepted: false, reason: 21 };return;}
              derived += size;s.prepared = { accepted: true, blob: result.blob, inkBlob: result.inkBlob, inkMin: Object.freeze(result.inkMin.slice()), exposure: Object.freeze(result.exposure.slice()) };
            } else s.prepared = { accepted: false, reason: result.reason || 20 };
          } catch (error) {
            if (live() && !owner.paused() && error?.name !== "AbortError") s.prepared = { accepted: false, reason: 22 };
          }
        }();
        s.pending = task;task.finally(function () {if (s.pending === task) s.pending = null;});return task;
      }
      async function prepare(id, options) {
        var s = sources.get(id);if (!s) return { status: live() ? "preserve" : "cancelled", reason: 1 };
        var stopped = status(s, options);if (stopped) return stopped;
        await ensure(s);stopped = status(s, options);if (stopped) return stopped;
        return s.prepared?.accepted ? { status: "prepared" } : original(s, "preserve", s.prepared?.reason || 24);
      }
      function compose(s, plan) {
        var revision = ++s.request;
        var task = async function () {try {
            var rgb = [1, 3, 5].map(function (at) {return parseInt(plan.colour.slice(at, at + 2), 16);});
            var result = await owner.run({ op: "compose", provenance: "prepared-matte", blob: s.prepared.blob, inkBlob: s.prepared.inkBlob, inkMin: s.prepared.inkMin, w: s.width, h: s.height, rgb: rgb,
              gray: rgb[0] === rgb[1] && rgb[1] === rgb[2] ? rgb[0] : undefined });
            if (!live() || revision !== s.request) return;
            if (!valid(result, s) || !result.accepted || !output(result)) throw new Error("composite");
            var prior = s.night?.blob.size || 0;if (result.blob.size > 6291456 - derived + prior) return;
            derived += result.blob.size - prior;s.night = { key: plan.colour, blob: result.blob };
          } catch (_) {}}();
        s.composing = task;task.finally(function () {if (s.composing === task) s.composing = null;});return task;
      }
      async function select(id, paperL, palette, options) {
        if (Array.isArray(options?.paperRGB)) options = { ...options, paperRGB: options.paperRGB.slice() };
        var s = sources.get(id);if (!s) return { status: live() ? "preserve" : "cancelled", reason: 1 };
        var stopped = status(s, options, palette);if (stopped) return stopped;
        var prepared = await prepare(id, options);if (prepared.status !== "prepared") return prepared;
        stopped = status(s, options, palette);if (stopped) return stopped;
        var rgb = options?.paperRGB;
        if (!Array.isArray(rgb) || rgb.length !== 3 || !rgb.every(function (v) {return Number.isInteger(v) && v >= 0 && v <= 255;})) return original(s, "preserve", 25);
        var channels = rgb.map(function (v) {v /= 255;return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);});
        var actual = .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
        if (!Number.isFinite(paperL) || Math.abs(paperL - actual) > 1e-6 || actual > .04) return original(s, "preserve", 26);
        var plan = { choice: 3, paper: actual, colour: "#" + rgb.map(function (v) {return v.toString(16).padStart(2, "0");}).join(""), backingKind: "dark-artwork", inkRevision: 1 };
        if (s.night?.key === plan.colour) return { status: "ready", blob: s.night.blob, width: s.width, height: s.height, variant: "backed", contrast: plan };
        if (s.composing) {await s.composing;return select(id, paperL, palette, options);}
        await compose(s, plan);stopped = status(s, options, palette);if (stopped) return stopped;
        return s.night?.key === plan.colour ? { status: "ready", blob: s.night.blob, width: s.width, height: s.height, variant: "backed", contrast: plan } : original(s, "preserve", 22);
      }
      function close() {closed = true;owner.cancel?.();sources.clear();pixels = bytes = derived = 0;}
      return Object.freeze({ admit: admit, prepare: prepare, select: select, original: function (id) {var s = sources.get(id);return s ? original(s) : null;}, close: close,
        stats: function () {return { sources: sources.size, pixels: pixels, sourceBytes: bytes, derivedBytes: derived, closed: closed };} });
    }
    return Object.freeze({ create: create });
  }();

  function createOfficePaperConsumer(global) {
    if (typeof document === "undefined") throw new TypeError("Host service requires window; use remote factory in render worker");
    var workerRealm = false,active = true,paused = false,epoch = 1,paletteKey = "",paletteKnown = false,worker = null,workerReady = false,job = null,queue = [],sequence = 0;
    var remaining = 5000,deadline = 0,started = 0,records = new Map(),attemptBytes = 0,slots = 0,decodedPixels = 0,pendingPixels = 0,bitmaps = new Set();
    var stats = { disabled: 0 };
    function isPaused() {return paused || !workerRealm && document.hidden;}





























































    function stopWorker() {if (worker) {worker.terminate();}worker = null;workerReady = false;}
    function stopTimer() {if (deadline) {clearTimeout(deadline);deadline = 0;remaining = Math.max(0, remaining - (performance.now() - started));}}
    function fail() {stats.disabled = 1;stopTimer();stopWorker();var pending = job;job = null;if (pending) {pending.resolve({ status: "cancelled" });}for (var item of queue.splice(0)) {item.resolve({ status: "cancelled" });}}
    function arm() {if (!deadline && active && !isPaused() && (job || queue.length)) {started = performance.now();deadline = setTimeout(function () {fail();}, remaining);}}
    function completed(result) {
      if (!job) return;var pending = job;job = null;

      pending.resolve(result);if (!queue.length) {stopTimer();if (isPaused()) stopWorker();}pump();
    }
    function startWorker() {
      if (typeof Worker !== "function") {fail();return;}
      try {
        var url = global.OfficePaperWorkerURL || new URL("engine/office-paper-worker.js", global.location.href).href;
        if (new URL(url).origin !== global.location.origin) throw new Error("worker origin");
        var instance = worker = new Worker(url);
        instance.onerror = function (e) {e.preventDefault();if (worker === instance) fail();};instance.onmessageerror = function () {if (worker === instance) fail();};
        instance.onmessage = function (e) {if (worker !== instance || !active) return;var r = e.data;if (r?.type === 0) {if (r.capable !== true) {fail();return;}workerReady = true;pump();} else if (job && r?.id === job.data.id) completed(r);else fail();};
      } catch (_) {fail();}
    }
    function pump() {
      if (!active || stats.disabled || isPaused() || job || !queue.length) return;arm();
      if (!workerRealm && !workerReady) {if (!worker) startWorker();return;}
      job = queue.shift();
      if (workerRealm) officePaperExecute(job.data).then(completed, function () {completed({ type: 3, code: 2 });});else
      try {worker.postMessage(job.data);} catch (_) {fail();}
    }
    function run(data) {
      if (!active || stats.disabled) {return Promise.resolve({ status: "cancelled" });}
      if (isPaused()) {return Promise.resolve({ status: "paused" });}
      if (queue.length >= 12 || sequence >= 32768) {return Promise.resolve({ status: "cancelled" });}
      data = { ...data, id: ++sequence };
      return new Promise(function (resolve) {queue.push({ data: data, resolve: resolve });pump();});
    }
    var session = OfficePaperSession.create({ run: run, live: function () {return active && !stats.disabled;}, epoch: function () {return epoch;}, paused: isPaused, current: function (key) {return key === paletteKey;}, cancel: fail });
    function pause(value) {
      paused = !!value;if (isPaused()) {stopTimer();if (!job) stopWorker();for (var item of queue.splice(0)) item.resolve({ status: "paused" });} else
      pump();
    }
    function close() {if (!active) return;active = false;epoch++;session.close();for (var bitmap of bitmaps) bitmap.close();bitmaps.clear();records.clear();decodedPixels = attemptBytes = slots = 0;}
    function current(request) {try {return active && !stats.disabled && !isPaused() && (!request.current || request.current());} catch (_) {return false;}}
    function palette(value) {
      if (!value || value.dark !== true || !Array.isArray(value.matrix) || value.matrix.length !== 20 || !value.matrix.every(Number.isFinite)) return null;
      var rgb = [0, 1, 2].map(function (row) {var sum = 0;for (var i = 0; i < 5; i++) sum += value.matrix[row * 5 + i];return Math.max(0, Math.min(1, sum));});
      rgb = rgb.map(function (c) {return Math.round(c * 255);});
      var linear = rgb.map(function (c) {c /= 255;return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);});
      return { key: JSON.stringify(value.matrix), rgb: rgb, luminance: .2126 * linear[0] + .7152 * linear[1] + .0722 * linear[2] };
    }
    function internalPath(path) {return typeof path === "string" && path.length <= 512 && /^(word|ppt|xl)\/media\//.test(path) && !/[\\?#%\u0000-\u0020]/.test(path) && !/(^|\/)\.\.(\/|$)/.test(path);}
    function setPalette(value) {var p = palette(value);paletteKnown = true;paletteKey = p ? p.key : "";}
    function wanted(p) {if (!paletteKnown) {paletteKnown = true;paletteKey = p.key;}return p.key === paletteKey;}
    async function register(request) {
      var record = records.get(request.path);if (record && (record.registered || record.denied)) return record;if (record?.pending) return record.pending;
      if (!record) {if (slots >= 12) {return null;}record = { id: ++slots, pending: null, denied: false, registered: false, blob: null, counted: false, metadata: null, variants: new Map(), decoding: new Map() };records.set(request.path, record);}
      var pending = async function () {try {
          var blob = record.blob || request.blob,probe;
          if (request.data && !blob) {
            if (!record.counted) {var estimate = Math.ceil(request.data.length * 3 / 4);if (estimate > 2097200 || estimate > 4194304 - attemptBytes) {throw new Error("bytes");}attemptBytes += estimate;record.counted = true;}
            probe = await run({ op: "data-probe", provenance: "archive-raster", data: request.data });if (probe?.status) return record;blob = probe.sourceBlob;
          } else
          {blob = blob || (await request.fetch(request.path, request.mime));if (!(blob instanceof Blob)) {if (blob instanceof ArrayBuffer || ArrayBuffer.isView(blob)) blob = new Blob([blob], { type: request.mime });else throw new Error("source");}}
          if (!active || !(blob instanceof Blob) || blob.size > 2097152) {throw new Error("bytes");}
          if (!record.counted) {if (blob.size > 4194304 - attemptBytes) {throw new Error("bytes");}attemptBytes += blob.size;record.counted = true;}record.blob = blob;
          if (!probe) probe = await run({ op: "probe", provenance: "archive-raster", blob: blob });
          if (probe?.status) return record;
          if (!active || !probe?.probed || !session.admit(record.id, { provenance: "archive-raster", blob: blob, width: probe.width, height: probe.height })) {throw new Error("admission");}
          record.metadata = { width: probe.width, height: probe.height };record.registered = true;return record;
        } catch (_) {if (active && !isPaused()) record.denied = true;return record;}}();record.pending = pending;pending.finally(function () {if (record.pending === pending) record.pending = null;});return pending;
    }
    async function resolve(request) {
      var original = request.original,p = palette(request.palette);
      if (!p || !current(request) || !internalPath(request.path) || !/^image\/(png|jpeg)$/.test(request.mime || "") || !request.fetch && !request.blob && !request.data) {return original;}
      if (!wanted(p)) {return original;}
      var record = await register(request);if (!record?.registered || record.denied || !current(request)) {return original;}
      var selected = await session.select(record.id, p.luminance, p.key, { paperRGB: p.rgb });if (selected.status !== "ready" || !current(request) || paletteKey !== p.key) {return original;}
      var key = selected.variant + ":" + (selected.contrast?.colour || "clear"),cached = record.variants.get(key);
      if (cached) {return cached;}
      var pending = record.decoding.get(key),count = record.metadata.width * record.metadata.height;
      if (!pending) {
        if (count > 2000000 - decodedPixels - pendingPixels) {return original;}
        pendingPixels += count;
        pending = async function () {
          try {
            var bitmap = await createImageBitmap(selected.blob);
            if (!active || stats.disabled || bitmap.width !== record.metadata.width || bitmap.height !== record.metadata.height) {bitmap.close();return null;}
            decodedPixels += count;bitmaps.add(bitmap);record.variants.set(key, bitmap);return bitmap;
          } catch (_) {return null;} finally
          {pendingPixels -= count;}
        }();
        record.decoding.set(key, pending);
        pending.then(function () {if (record.decoding.get(key) === pending) record.decoding.delete(key);});
      }
      var bitmap = await pending;
      if (!bitmap || !current(request) || paletteKey !== p.key) {return original;}
      return bitmap;
    }
    async function blob(request) {
      var p = palette(request.palette);if (!p || !current(request) || !internalPath(request.path) || !wanted(p)) {return { status: "preserve" };}
      var record = await register(request);if (!record?.registered || record.denied || !current(request)) {return { status: "preserve" };}
      var selected = await session.select(record.id, p.luminance, p.key, { paperRGB: p.rgb });
      if (!current(request) || paletteKey !== p.key) {return { status: "preserve" };}


      return selected;
    }
    if (workerRealm) {
      global.addEventListener("message", function (event) {if (event.data?.type !== "office-paper-control") return;event.stopImmediatePropagation();if (event.data.closed) close();else pause(event.data.paused);});
      global.postMessage({ type: "office-paper-runtime-ready" });
    } else {
      global.addEventListener("pagehide", close, { once: true });document.addEventListener("visibilitychange", function () {pause(global.OfficePaperNativePaused || document.hidden);});
    }
    return Object.freeze({ resolve: resolve, blob: blob, setPalette: setPalette, pause: pause, close: close });
  }

  global.OfficePaperCreateConsumer = createOfficePaperConsumer;global.OfficePaperConsumer = createOfficePaperConsumer(global);})(globalThis);
