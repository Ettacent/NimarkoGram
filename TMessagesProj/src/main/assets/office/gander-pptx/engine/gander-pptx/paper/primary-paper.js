/* Modifications Copyright (C) 2026 Ettacent */


(() => {
  'use strict';
  const workerURL = new URL('shared-paper-worker.js', document.currentScript.src).href;
  const cancelled = () => Object.assign(new Error('Paper owner cancelled'), { name: 'AbortError' });

























  function createRunner(host) {
    let worker = null,capable = false,disabled = false,active = null,timer = 0;
    let nextId = 0,spent = 0;
    let budget = null;
    const queue = [];
    const clock = () => performance.now();




















    function suspendBudget() {
      clearTimeout(timer);timer = 0;
      if (budget?.started !== null && budget?.started !== undefined) {
        const elapsed = Math.max(0, clock() - budget.started);
        budget.remaining -= elapsed;
        if (budget.kind === 'job') spent += elapsed;
        budget.started = null;
      }
    }
    function armBudget() {
      if (!budget || disabled || host.paused() || budget.started !== null) return;
      if (!host.live() || budget.remaining <= 0) {stop(new Error('Paper active-time deadline'));return;}
      budget.started = clock();
      timer = setTimeout(() => {
        suspendBudget();
        if (!host.paused()) stop(new Error('Paper active-time deadline'));
      }, budget.remaining);
    }
    function beginBudget(kind, remaining) {suspendBudget();budget = { kind, remaining, started: null };armBudget();}
    function stop(error = cancelled()) {
      if (disabled) return;
      disabled = true;
      suspendBudget();budget = null;
      worker?.terminate();worker = null;
      if (active) {active.reject(error);active = null;}
      for (const task of queue.splice(0)) {task.reject(error);}
    }
    function pump() {
      if (disabled || active || !capable || host.paused() || !queue.length) return;
      if (!host.live() || spent >= 8000 || nextId >= 256) {stop(undefined);return;}
      active = queue.shift();active.id = ++nextId;
      beginBudget('job', Math.min(2000, 8000 - spent));
      try {worker.postMessage({ ...active.job, id: active.id });}
      catch (error) {stop(error);}
    }
    function start() {
      try {
        worker = host.makeWorker ? host.makeWorker(workerURL) : new Worker(workerURL);

        beginBudget('startup', 1500);
        worker.onerror = () => stop(new Error('Paper worker unavailable'));
        worker.onmessage = (event) => {
          const result = event.data;
          if (disabled) return;
          if (result?.type === 0 && !capable && !active) {
            suspendBudget();budget = null;
            if (result.capable !== true) {stop(undefined);return;}
            capable = true;pump();return;
          }
          if (!active || result?.id !== active.id || ![2, 3].includes(result.type)) {
            stop(new Error('Paper worker correlation'));return;
          }
          suspendBudget();budget = null;
          const task = active;active = null;


          if (result.type === 2) task.resolve(result);else
          task.reject(new Error('Paper worker rejected optional job'));
          pump();
        };
      } catch (error) {stop(error);}
    }
    function run(job) {
      if (disabled || !host.live() || host.paused()) {return Promise.reject(cancelled());}
      if (queue.length + Number(!!active) >= 12) {return Promise.reject(new Error('Paper queue limit'));}
      const promise = new Promise((resolve, reject) => {
        queue.push({ job, resolve, reject });

      });
      if (!worker) start();
      pump();return promise;
    }
    return Object.freeze({ run, pause: () => {suspendBudget();}, resume: () => {armBudget();pump();}, close: stop
    });
  }

  function create(host) {
    if (!host || typeof host.live !== 'function' || typeof host.epoch !== 'function' || typeof host.paused !== 'function') throw TypeError('Paper host');
    const epoch = host.epoch(),assets = [];
    let closed = false,attached = false,printing = false,root = null,running = null,revision = 0;
    let ready = false,wanted = { readingDark: false, readingPaper: 0xff242424 },binding = null;
    let committed = '';
    const ownedURLs = new Set(),domAbort = new AbortController();
    const createURL = (blob) => {
      const url = host.createURL ? host.createURL(blob) : URL.createObjectURL(blob);
      ownedURLs.add(url);return url;
    };
    const revokeURL = (url) => {
      if (!ownedURLs.delete(url)) return;
      if (host.revokeURL) host.revokeURL(url);else URL.revokeObjectURL(url);
    };
    const live = () => !closed && host.live() && host.epoch() === epoch;
    const paused = () => printing || host.paused();
    const key = () => JSON.stringify([!!wanted.readingDark, wanted.readingPaper >>> 0]);








    const runner = createRunner({ live, paused, makeWorker: host.makeWorker });
    const session = OfficePaperSession.create({ live, paused, epoch: () => host.epoch(),
      current: (palette) => live() && palette === key(),
      run: (job) => runner.run(job), cancel: () => runner.close() });
    function owned(use, asset) {
      const image = use.image,src = image.getAttribute('src'),selected = image.currentSrc;
      const valid = !use.retired && image.isConnected === true && root?.isConnected === true && root.contains(image) &&
      !image.hasAttribute('srcset') && !image.closest?.('picture') && src === use.last && (
      !selected || selected === src || selected === use.original || selected === asset.url);
      if (!valid) {use.retired = true;asset.blocked = true;}
      return valid;
    }
    function writeSource(use, uri) {
      if (use.image.getAttribute('src') !== uri) use.image.setAttribute('src', uri);
      use.last = uri;
    }
    function release(asset) {
      if (asset.url) revokeURL(asset.url);
      asset.url = null;asset.variantBlob = null;
    }
    function paperRGB() {return [16, 8, 0].map((shift) => wanted.readingPaper >>> 0 >>> shift & 255);}
    function paperL() {
      const channels = paperRGB().map((value) => value / 255).
      map((value) => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
      return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
    }
    function admit(part, width, height) {
      if (!live() || attached || !part || !/^image\/(png|jpeg)$/i.test(part.type) || assets.length === 12) {return false;}
      const length = part.data?.byteLength;
      if (!Number.isInteger(length) || length < 10 || length > 2097152) {return false;}
      const stats = session.stats(),pixels = width * height;
      if (!Number.isInteger(width) || !Number.isInteger(height) || width < 8 || height < 8 ||
      width > 2048 || height > 2048 || pixels > 350000 || pixels > 1000000 - stats.pixels ||
      length > 4194304 - stats.sourceBytes) {return false;}
      const blob = new Blob([part.data], { type: part.type.toLowerCase() });
      const id = assets.length + 1;
      if (!session.admit(id, { blob, width, height, provenance: 'archive-raster' })) {return false;}
      assets.push({ id, blob, width, height, type: blob.type, uses: [], url: null, variantBlob: null });
      return true;
    }
    function base64(bytes) {
      const parts = [];
      for (let at = 0; at < bytes.length; at += 16384) parts.push(String.fromCharCode(...bytes.subarray(at, at + 16384)));
      return btoa(parts.join(''));
    }
    async function bind(container) {
      root = container;
      const images = [...container.querySelectorAll('img')];
      if (images.length > 512) {attached = true;return;}


      const claimed = new Set();
      for (const asset of assets) {
        if (!live()) return;
        const expected = 'data:' + asset.type + ';base64,' + base64(new Uint8Array(await asset.blob.arrayBuffer()));
        if (!live()) return;
        let originalURI = null;
        for (const image of images) {
          if (!claimed.has(image) && image.getAttribute('src') === expected &&
          image.naturalWidth === asset.width && image.naturalHeight === asset.height) {
            if (originalURI === null) originalURI = image.getAttribute('src');
            const use = { image, original: originalURI, last: originalURI, retired: false };
            if (owned(use, asset)) {asset.uses.push(use);claimed.add(image);}
          }
        }
      }
      attached = true;
    }
    async function decodedURL(blob, asset) {
      if (asset.variantBlob === blob && asset.url) return { url: asset.url, fresh: false };
      const url = createURL(blob);
      try {
        const image = host.decode ? null : new Image();
        if (image) image.src = url;
        try {
          await new Promise((resolve, reject) => {
            let settled = false;
            const finish = (complete, value) => {
              if (settled) return;
              settled = true;clearTimeout(timer);
              domAbort.signal.removeEventListener('abort', cancel);complete(value);
            };
            const cancel = () => finish(reject, cancelled());
            const timer = setTimeout(() => finish(reject, new Error('Paper variant decode deadline')), 1500);
            domAbort.signal.addEventListener('abort', cancel, { once: true });
            try {
              const operation = host.decode ? host.decode(url, asset.width, asset.height) : image.decode();
              Promise.resolve(operation).then((value) => finish(resolve, value), (error) => finish(reject, error));
            } catch (error) {finish(reject, error);}
            if (domAbort.signal.aborted) cancel();
          });
          if (image && (image.naturalWidth !== asset.width || image.naturalHeight !== asset.height)) throw Error('Paper variant dimensions');
        } finally {image?.removeAttribute('src');}
        return { url, fresh: true };
      } catch (error) {revokeURL(url);throw error;}
    }
    async function flush() {
      while (live() && attached && !paused()) {
        if (committed === key()) {
          host.commitColors?.({ ...wanted, preserveImages: true });ready = true;host.onSettled?.();break;
        }
        const turn = revision,palette = key(),value = { ...wanted },changes = [];
        ready = false;
        for (const asset of assets) {
          if (!asset.uses.length) continue;
          if (!live() || paused() || turn !== revision) break;
          let result = session.original(asset.id),prepared = null;
          if (value.readingDark && !asset.blocked) result = await session.select(asset.id, paperL(), palette, { paperRGB: paperRGB() });
          if (!live() || paused() || turn !== revision) break;
          if (result?.status === 'ready' && result.variant === 'backed') {
            try {prepared = await decodedURL(result.blob, asset);}
            catch (_) { }
          }
          changes.push({ asset, result, prepared });
        }
        if (!live() || paused() || turn !== revision) {
          for (const change of changes) if (change.prepared?.fresh) revokeURL(change.prepared.url);
          if (!live() || paused()) break;
          continue;
        }
        for (const { asset, result, prepared } of changes) {
          const old = asset.url;
          const uses = asset.uses.filter((use) => owned(use, asset));
          if (asset.blocked) {
            if (prepared?.fresh) revokeURL(prepared.url);
            for (const use of uses) writeSource(use, use.original);

            continue;
          }

          for (const use of uses) writeSource(use, prepared ? prepared.url : use.original);
          if (prepared) {asset.url = prepared.url;asset.variantBlob = result.blob;} else
          {asset.url = null;asset.variantBlob = null;}
          if (old && old !== asset.url) revokeURL(old);
        }
        committed = palette;
        host.commitColors?.({ ...wanted, preserveImages: true });
        ready = true;host.onSettled?.();
        if (turn === revision) break;
      }
    }
    function schedule() {
      if (!live() || paused() || !attached) return Promise.resolve();
      if (!running) {
        running = Promise.resolve().then(flush).then(() => {
          running = null;
          if (live() && !paused() && attached && (!ready || committed !== key())) return schedule();
        }, (error) => {running = null;throw error;});
      }
      return running;
    }
    function colors(value) {
      const previous = key();wanted = { ...wanted, ...value };
      if (previous !== key()) revision++;
      ready = false;
      if (!attached) {host.commitColors?.({ ...wanted, preserveImages: true });return Promise.resolve();}
      if (live() && !paused() && committed === key()) {
        host.commitColors?.({ ...wanted, preserveImages: true });ready = true;host.onSettled?.();return Promise.resolve();
      }
      return schedule();
    }
    async function attach(container) {
      binding = bind(container);
      try {await binding;} catch (_) {attached = true;}
      binding = null;
      await schedule();
    }
    function pause() {revision++;ready = false;runner.pause();}
    async function resume() {
      if (!live() || paused()) return;
      if (binding) await binding;
      runner.resume();
      if (committed === key()) {
        host.commitColors?.({ ...wanted, preserveImages: true });ready = true;host.onSettled?.();return;
      }
      if (running) await running;
      await schedule();
    }
    function close() {
      if (closed) return;
      closed = true;revision++;ready = false;
      domAbort.abort();
      session.close();
      for (const asset of assets) {
        for (const use of asset.uses) if (owned(use, asset)) writeSource(use, use.original);
        release(asset);asset.uses.length = 0;asset.blob = null;
      }
      for (const url of [...ownedURLs]) revokeURL(url);
      assets.length = 0;root = null;
    }
    window.addEventListener?.('beforeprint', () => {
      if (!live() || printing) return;
      printing = true;revision++;ready = false;committed = '';runner.pause();
      for (const asset of assets) for (const use of asset.uses) if (owned(use, asset)) writeSource(use, use.original);
      host.commitColors?.({ ...wanted, readingDark: false, preserveImages: true });
    }, { signal: domAbort.signal });
    window.addEventListener?.('afterprint', () => {
      if (!live() || !printing) return;
      printing = false;void resume();
    }, { signal: domAbort.signal });
    return Object.freeze({ admit, attach, colors, pause, resume, close,
      isReady: () => live() && !paused() && ready && committed === key()

    });
  }
  window.OfficePptxPaper = Object.freeze({ create, createRunner });
})();
