/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */
(function () {
  'use strict';
  var root = document.documentElement;
  var active = false,started = false,paused = false,ready = false,finished = false;
  var nativeAllowCopy = false,pdfAllowCopy = true,allowCopy = false;
  var generation = 0,count = 0,index = 0,initialIndex = 0,scale = 1,baseScale = 1;
  var topInset = 0,bottomInset = 0,queryRevision = 0,query = '',appliedRevision = -1,pendingSteps = 0;
  var frameTask = 0,readyTask = 0,frameId = 0,awaitingAck = 0,frameDirty = true;
  var abort = null,loadTimer = 0,passwordUpdate = null,passwordRequest = 0;
  var paletteKey = '',openedBytes = 0,preserveImages = true,initialPassword;
  var motionEnabled = true,queryReveal = false,findReveal = false;
  var MAX_BYTES = 24 * 1024 * 1024;
  var cancelled = new Error('Document session cancelled');
  var fetchResolve, fetchReject;
  var fetchGate = new Promise(function (resolve, reject) {fetchResolve = resolve;fetchReject = reject;});
  fetchGate.catch(function () {});


  window.vwFetchDoc = function () {return fetchGate;};
  window.officePdfTakePassword = function () {var value = initialPassword;initialPassword = undefined;return value;};
  function cancelReveal() {
    queryReveal = findReveal = false;
    if (window.OfficePdf) OfficePdf.cancelReveal();
  }
  ['pointerdown', 'touchstart', 'wheel'].forEach(function (type) {
    addEventListener(type, cancelReveal, { passive: true, capture: true });
  });
  window.officePdfVisibleBounds = function () {
    var vv = window.visualViewport,factor = vv && vv.scale > 0 ? 1 / vv.scale : 1;
    var top = (vv ? vv.offsetTop : 0) + topInset * factor;
    var bottom = (vv ? vv.offsetTop + vv.height : innerHeight) - bottomInset * factor;
    var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    return { top: top, bottom: Math.max(top + 1, bottom), left: vv ? vv.offsetLeft : 0,
      right: vv ? vv.offsetLeft + vv.width : innerWidth, behavior: motionEnabled && !reduced ? 'smooth' : 'instant' };
  };

  function post(message) {
    if (!active && message.type !== 'bridgeReady') return;
    try {if (window.OfficeHost) window.OfficeHost.postMessage(JSON.stringify(message));} catch (_) {}
  }
  function clamp(value, lo, hi) {return Math.max(lo, Math.min(hi, Number(value) || 0));}
  function cssColor(value) {return '#' + ('000000' + (Number(value) >>> 0).toString(16)).slice(-6);}
  function visible() {return active && !paused && !document.hidden;}
  function clearLoadTimeout() {clearTimeout(loadTimer);loadTimer = 0;}
  function armLoadTimeout() {
    clearLoadTimeout();
    if (visible() && !ready && !passwordUpdate) loadTimer = setTimeout(function () {fail(5, ['openingTimeout']);}, 45000);
  }
  function protection(value) {
    nativeAllowCopy = value === true;
    allowCopy = nativeAllowCopy && pdfAllowCopy;
    root.classList.toggle('office-protected', !allowCopy);
    Array.from(document.querySelectorAll('.textLayer')).forEach(function (layer) {
      if (allowCopy) layer.removeAttribute('aria-hidden');else layer.setAttribute('aria-hidden', 'true');
    });
    if (!allowCopy && window.getSelection()) window.getSelection().removeAllRanges();
  }
  ['copy', 'cut', 'contextmenu', 'dragstart', 'selectstart'].forEach(function (type) {
    document.addEventListener(type, function (e) {if (!allowCopy) e.preventDefault();}, true);
  });
  document.addEventListener('selectionchange', function () {
    var selection = window.getSelection();
    if (!allowCopy && selection && selection.rangeCount) selection.removeAllRanges();
  });
  document.addEventListener('click', function (e) {
    if (e.target.closest && e.target.closest('a,area')) {e.preventDefault();e.stopImmediatePropagation();}
  }, true);
  document.addEventListener('submit', function (e) {e.preventDefault();e.stopImmediatePropagation();}, true);
  window.print = function () {};
  window.open = function () {return null;};
  window.vwDisarmLinks = function (parent) {
    Array.from(parent.querySelectorAll('a,area')).forEach(function (a) {
      a.removeAttribute('href');a.removeAttribute('target');a.removeAttribute('download');
    });
  };

  function colors(m) {
    pageMotionSettings(m);
    if (Number.isFinite(m.background)) root.style.setProperty('--vw-ground', cssColor(m.background));
    if (Number.isFinite(m.foreground)) root.style.setProperty('--office-ink', cssColor(m.foreground));
    if (Number.isFinite(m.readingPaper)) root.style.setProperty('--office-paper', cssColor(m.readingPaper));
    if (Number.isFinite(m.accent)) {
      root.style.setProperty('--office-accent', cssColor(m.accent));
      root.style.setProperty('--office-find', cssColor(m.accent) + '33');
      root.style.setProperty('--office-find-active', cssColor(m.accent) + '55');
    }
    if (typeof m.readingDark === 'boolean') vwNight = m.readingDark;
    preserveImages = m.preserveImages !== false;
    root.classList.toggle('vw-night', vwNight);
    var style = getComputedStyle(root);
    var p = style.getPropertyValue('--office-paper').trim() || '#161616';
    var i = style.getPropertyValue('--office-ink').trim() || '#eeeeee';
    var key = p + '/' + i;
    var changed = key !== paletteKey;
    paletteKey = key;
    function rgb(c) {return [parseInt(c.slice(1, 3), 16) / 255, parseInt(c.slice(3, 5), 16) / 255, parseInt(c.slice(5, 7), 16) / 255];}
    var paper = rgb(p),ink = rgb(i);
    var matrix = [[.574, -1.430, -.144], [-.426, -.430, -.144], [-.426, -1.430, .856]];
    var values = [];
    for (var row = 0; row < 3; row++) {
      var delta = ink[row] - paper[row];
      matrix[row].forEach(function (n) {values.push(n * delta);});
      values.push(0, ink[row]);
    }
    values.push(0, 0, 0, 1, 0);
    document.getElementById('office-night-matrix').setAttribute('values', values.join(' '));
    if (window.OfficePdf) {
      if (!motionEnabled) OfficePdf.finishPresentation();
      OfficePdf.readingMode(vwNight, preserveImages, changed);
    }
  }
  function insets(m) {
    if (Number.isFinite(m.top)) topInset = clamp(m.top, 0, 4096);
    if (Number.isFinite(m.bottom)) bottomInset = clamp(m.bottom, 0, 4096);
    vwTopDp = topInset;vwPlaceTop();
    var vv = window.visualViewport;
    var across = vv && vv.scale > 0 ? vv.width * vv.scale : 0;
    var perDp = across > 0 && innerWidth > 0 ? innerWidth / across : 1;
    root.style.setProperty('--office-bottom', bottomInset * perDp + 'px');
    if (window.officePdfLayoutChanged) window.officePdfLayoutChanged();
    scheduleFrame();
  }
  function scheduleFrame() {
    frameDirty = true;
    if (!visible() || !ready || awaitingAck || frameTask) return;
    frameTask = requestAnimationFrame(function () {
      frameTask = 0;
      if (!visible() || !ready) return;
      var vv = window.visualViewport;
      var nextScale = clamp((vv ? vv.scale : baseScale) / baseScale, .1, 10);
      if (Math.abs(nextScale - scale) > .001) {scale = nextScale;post({ type: 'scale', scale: scale });}
      index = currentPage();
      post({ type: 'position', count: count, index: index, label: index + 1 + ' / ' + count });
      frameDirty = false;awaitingAck = ++frameId;
      post({ type: 'frame', generation: generation, frameId: frameId, coordinateRevision: 0,
        x: vv ? vv.pageLeft : scrollX, y: vv ? vv.pageTop : scrollY,
        width: vv ? vv.width : innerWidth, scale: scale });
    });
  }
  window.officePdfMoved = scheduleFrame;
  addEventListener('scroll', scheduleFrame, { passive: true });
  if (window.visualViewport) {
    visualViewport.addEventListener('scroll', scheduleFrame, { passive: true });
    visualViewport.addEventListener('resize', pageViewportResize, { passive: true });
  }
  addEventListener('resize', function () {if (active) insets({});});


  let pageRequest = null,enginePageRequest = null,pageTask = 0;
  const pageMotion = matchMedia('(prefers-reduced-motion: reduce)');
  function pagePoint() {
    const vv = window.visualViewport;
    return { x: vv?.pageLeft ?? scrollX, y: vv?.pageTop ?? scrollY };
  }
  function cancelPageNavigation() {
    pageRequest = enginePageRequest = null;
    cancelAnimationFrame(pageTask);pageTask = 0;
    window.OfficePdf?.cancelNavigation?.();
  }
  function requestedPage() {
    if (!pageRequest) return null;
    if (!pageRequest.moving && !OfficePdf.retainPageRequest(pageRequest)) pageRequest = null;
    return pageRequest?.index ?? null;
  }
  function navigatePage(at, smooth, move) {
    if (pageRequest?.moving && pageRequest.index === at && pageRequest.smooth === smooth) return;
    const request = pageRequest = { index: at, moving: true, viewportScale: window.visualViewport?.scale, viewportHeight: window.visualViewport?.height, extent: root.scrollHeight, ...pagePoint() };
    request.smooth = smooth;request.move = move;
    if (!pageTask) pageTask = requestAnimationFrame(function () {
      pageTask = 0;
      const request = pageRequest;
      if (!request || !visible() || !ready) return;
      if (enginePageRequest?.moving && enginePageRequest.index === request.index && enginePageRequest.smooth === request.smooth) {
        pageRequest = enginePageRequest;
        scheduleFrame();
        return;
      }
      enginePageRequest = request;
      const current = () => enginePageRequest === request && active && ready && !paused && !document.hidden;
      const animated = request.smooth && motionEnabled && !pageMotion.matches;
      Promise.resolve(request.move(animated ? 'smooth' : 'instant', current)).then(() => {
        if (!current()) return;
        Object.assign(request, pagePoint(), { moving: false, extent: root.scrollHeight, viewportHeight: window.visualViewport?.height });
        if (pageRequest === request) scheduleFrame();
      }).catch(() => {
        if (!current()) return;
        request.moving = false;
        if (pageRequest === request) {cancelPageNavigation();scheduleFrame();}
      });
    });
    scheduleFrame();
  }
  function pageViewportResize() {
    if (pageRequest && pageRequest.viewportScale !== window.visualViewport?.scale) cancelPageNavigation();
    scheduleFrame();
  }
  function pageMotionChanged() {
    if (pageRequest?.moving && (!motionEnabled || pageMotion.matches)) gotoPage(pageRequest.index, false);
  }
  function pageMotionSettings(message) {
    if (typeof message.motionEnabled === 'boolean') motionEnabled = message.motionEnabled;
    pageMotionChanged();
  }
  pageMotion.addEventListener?.('change', pageMotionChanged);
  for (const type of ['pointerdown', 'touchstart', 'wheel', 'keydown']) {
    addEventListener(type, () => {cancelPageNavigation();scheduleFrame();}, { capture: true, passive: true });
  }

  function currentPage() {
    return requestedPage() ?? (window.OfficePdf ? clamp(OfficePdf.position(), 0, count - 1) : index);
  }
  function gotoPage(next, smooth = true) {
    if (!ready || !visible() || !window.OfficePdf) return;
    cancelReveal();
    index = Math.floor(clamp(next, 0, count - 1));
    const target = index;
    navigatePage(target, smooth, (behavior, isCurrent) => OfficePdf.goto(target + 1, { behavior, isCurrent }));
  }
  function zoom(operation) {
    cancelPageNavigation();
    if (!ready || !visible()) return;
    cancelReveal();
    post({ type: 'nativeZoom', operation: operation, factor: operation === 'reset' ? 1 / scale : operation === 'in' ? 1.25 : .8 });
  }

  function cancel(clear) {
    cancelPageNavigation();
    active = false;ready = false;appliedRevision = -1;
    cancelReveal();
    clearLoadTimeout();passwordUpdate = null;initialPassword = undefined;
    if (abort) abort.abort();
    fetchReject(cancelled);
    cancelAnimationFrame(frameTask);cancelAnimationFrame(readyTask);
    frameTask = readyTask = awaitingAck = 0;
    if (window.OfficePdf) OfficePdf.close();
    if (window.CSS && CSS.highlights) {CSS.highlights.delete('vw-find');CSS.highlights.delete('vw-find-active');}
    if (clear !== false) document.getElementById('pages').replaceChildren();
  }
  function fail(code, features) {
    if (!active) return;
    post({ type: 'error', code: code, renderer: 'gander-pdf', fallbackRequired: true, featureCodes: features || [] });
    var status = document.getElementById('vw-status');
    status.className = 'vw-status vw-error';status.style.display = '';
    status.textContent = code === 4 ? 'This PDF needs the native reader for its protection.' : 'Opening this PDF in the native reader is required.';
    cancel(false);
  }
  window.officePdfFailure = function (e) {
    if (!active) return;
    var password = e && (e.name === 'PasswordException' || /encryption|crypto/i.test(String(e.message || '')));
    fail(password ? 4 : 3, [password ? 'unsupportedEncryption' : 'pdfRenderFailure']);
  };
  window.officePdfWorkerFailure = function () {fail(3, ['dedicatedWorkerUnavailable']);};
  window.vwError = function () {fail(3, ['pdfRenderFailure']);};
  window.onerror = function () {if (active) fail(3, ['viewerExecutionFailure']);return true;};
  window.officePdfPassword = function (update, reason) {
    if (!active) return;
    passwordUpdate = update;clearLoadTimeout();
    post({ type: 'password', code: 4, requestId: ++passwordRequest,
      reason: reason === 2 ? 'incorrect' : 'required', retry: reason === 2 });
    vwStatus('Password required');
  };
  window.officePdfMetadata = function (pages, permissions, pureXfa) {
    if (!active) throw cancelled;
    if (pureXfa || pages < 1 || pages > 2000) {fail(3, [pureXfa ? 'xfaRequiresNativeReader' : 'candidatePageLimit']);throw cancelled;}
    count = pages;
    pdfAllowCopy = permissions === null || !!(permissions && (
    typeof permissions.has === 'function' ? permissions.has(16) : Array.isArray(permissions) && permissions.indexOf(16) >= 0));
    protection(nativeAllowCopy);
  };
  window.officePdfModuleReady = function () {
    if (!active) {OfficePdf.close();return;}
    OfficePdf.readingMode(vwNight, preserveImages, false);
    if (!visible()) OfficePdf.setPaused(true);
  };
  var oldDone = vwStatusDone;
  window.vwStatusDone = function () {
    if (!active || ready) return;
    finished = true;
    cancelAnimationFrame(readyTask);readyTask = 0;
    if (!visible()) return;
    readyTask = requestAnimationFrame(function () {
      readyTask = requestAnimationFrame(function () {
        readyTask = 0;
        if (!visible()) return;
        oldDone();clearLoadTimeout();
        baseScale = window.visualViewport ? visualViewport.scale : 1;
        ready = true;
        index = Math.floor(clamp(initialIndex, 0, count - 1));
        post({ type: 'ready', count: count, index: index, names: [], renderer: 'gander-pdf', copyAllowed: allowCopy });
        post({ type: 'scale', scale: scale });
        sendQuery();scheduleFrame();
      });
    });
  };
  window.officePdfSearch = function (hits, at, pending, truncated) {
    if (!visible() || !ready || appliedRevision !== queryRevision) return;
    post({ type: 'search', revision: queryRevision, count: hits, index: at, pending: pending, truncated: truncated });
    scheduleFrame();
  };
  function sendQuery() {
    if (!visible() || !ready || !window.OfficePdf) return;
    appliedRevision = queryRevision;
    OfficePdf.query(query, queryReveal);queryReveal = false;
    if (pendingSteps) {OfficePdf.find(pendingSteps, findReveal);pendingSteps = 0;findReveal = false;}
  }

  async function resources() {

    {
      var response = await fetch('resources.json', { signal: abort.signal, credentials: 'omit', redirect: 'error', cache: 'no-store' });
      if (!response.ok) throw new Error('Asset manifest unavailable');
      var manifest = await response.json();
      if (manifest.version !== '6.3.289' || !Array.isArray(manifest.files) || !Array.isArray(manifest.missing) || manifest.missing.length) throw new Error('Incomplete assets');
      var next = 0;
      async function check() {
        while (next < manifest.files.length) {
          var item = manifest.files[next++];
          if (!active || !/^[A-Za-z0-9_./-]+$/.test(item.path) || item.path.indexOf('..') >= 0 || item.path.charAt(0) === '/') throw cancelled;
          var r = await fetch(item.path, { signal: abort.signal, credentials: 'omit', redirect: 'error', cache: 'force-cache' });
          if (!r.ok) throw new Error('Asset unavailable');
          var bytes = await r.arrayBuffer();
          if (bytes.byteLength !== item.bytes) throw new Error('Asset length mismatch');
          var digest = await crypto.subtle.digest('SHA-256', bytes);
          var actual = Array.from(new Uint8Array(digest)).map(function (n) {return ('0' + n.toString(16)).slice(-2);}).join('');
          if (actual !== item.sha256) throw new Error('Asset hash mismatch');
          if (/\.wasm$/.test(item.path)) await WebAssembly.compile(bytes);
        }
      }
      await Promise.all([check(), check(), check(), check()]);

    }
  }
  async function readDocument() {

    {
      var r = await fetch('/document', { signal: abort.signal, credentials: 'omit', redirect: 'error', cache: 'no-store' });
      if (!r.ok) throw new Error('Document unavailable');
      var size = Number(r.headers.get('Content-Length'));
      if (size > MAX_BYTES) {fail(2, ['wholeBuffer24MiBLimit']);throw cancelled;}
      if (!r.body || !r.body.getReader) {fail(3, ['boundedStreamRequired']);throw cancelled;}
      var reader = r.body.getReader(),parts = [],held = 0;
      try {
        while (true) {
          var chunk = await reader.read();
          if (!active) throw cancelled;
          if (chunk.done) break;
          held += chunk.value.byteLength;
          if (held > MAX_BYTES) {await reader.cancel();fail(2, ['wholeBuffer24MiBLimit']);throw cancelled;}
          parts.push(chunk.value);
        }
      } finally {reader.releaseLock();}
      var data = new Uint8Array(held),at = 0;
      parts.forEach(function (p) {data.set(p, at);at += p.byteLength;});
      parts.length = 0;openedBytes = held;
      if (held < 8 || new TextDecoder('ascii').decode(data.subarray(0, 1024)).indexOf('%PDF-') < 0) throw new Error('Invalid PDF');

      return data.buffer;
    }
  }
  async function open(m) {
    if (started) return;
    started = active = true;generation++;
    if (typeof m.password === 'string') {
      if (m.password.length > 4096) {fail(4, ['passwordLengthLimit']);return;}
      initialPassword = m.password;
    }
    protection(m.allowCopy);colors(m);insets(m);
    if (m.format !== 'pdf' || root.dataset.officeRenderer !== 'gander-pdf') {fail(3, ['explicitPdfRouteRequired']);return;}
    var unavailable = OfficePdfCapabilities(m);
    if (unavailable) {fail(3, [unavailable]);return;}
    initialIndex = Number.isInteger(m.index) ? m.index : 0;
    vwParams.set('resume', String(initialIndex + 1));
    root.dataset.officeNativeGestures = 'true';
    abort = new AbortController();armLoadTimeout();
    try {await resources();} catch (_) {if (active) fail(3, ['pdfAssetSetIncomplete']);return;}
    try {
      var bytes = await readDocument();
      if (!active) return;
      post({ type: 'capabilities', renderer: 'gander-pdf', nativeGestures: true,
        notes: ['wholeBuffer24MiB', 'rasterImagesPreserved', 'noPdfScriptingOrForms', 'iccQcmsEnabled'],
        passwordBridge: true, sourceBytes: openedBytes });
      var script = document.createElement('script');script.type = 'module';
      script.src = 'engine/gander/pdf.mjs';
      script.onerror = function () {fail(3, ['pdfModuleUnavailable']);};
      document.body.appendChild(script);
      fetchResolve(bytes);
    } catch (_) {if (active) fail(1, ['documentReadFailure']);}
  }
  function suspend() {
    cancelPageNavigation();
    clearLoadTimeout();cancelAnimationFrame(frameTask);frameTask = 0;awaitingAck = 0;frameDirty = true;
    if (window.OfficePdf) OfficePdf.setPaused(true);
  }
  function resume() {
    if (!visible()) return;
    if (window.OfficePdf) OfficePdf.setPaused(false);
    if (finished && !ready) vwStatusDone();
    armLoadTimeout();sendQuery();scheduleFrame();
  }
  function command(m) {
    if (!m || typeof m.type !== 'string') return;
    if (m.type === 'open') {open(m);return;}
    if (m.type === 'close' || m.type === 'cancel') {cancel(true);return;}
    if (!active) return;
    switch (m.type) {
      case 'colors':cancelReveal();colors(m);scheduleFrame();break;
      case 'insets':insets(m);break;
      case 'protection':protection(m.allowCopy);break;
      case 'query':
        if (!Number.isInteger(m.revision) || m.revision < queryRevision) break;
        cancelPageNavigation();
        queryRevision = m.revision;pendingSteps = 0;
        findReveal = false;
        query = String(m.query || '');
        var end = Math.min(query.length, 4096);
        if (end < query.length && end > 0 && /[\uD800-\uDBFF]/.test(query[end - 1]) && /[\uDC00-\uDFFF]/.test(query[end])) --end;
        query = query.slice(0, end).replace(/\s+/g, ' ').trim();
        queryReveal = !!query;
        if (!query) cancelReveal();
        if (!ready || !visible()) post({ type: 'search', revision: queryRevision, count: 0, index: -1, pending: true, truncated: false });
        sendQuery();break;
      case 'find':
        if (m.revision !== queryRevision) break;
        cancelPageNavigation();
        pendingSteps = clamp(pendingSteps + (m.forward === false ? -1 : 1), -1000, 1000);
        findReveal = true;
        if (visible() && ready && appliedRevision === queryRevision) {OfficePdf.find(pendingSteps, true);pendingSteps = 0;findReveal = false;}
        break;
      case 'goto':gotoPage(m.index);break;
      case 'previous':gotoPage(currentPage() - 1);break;
      case 'next':gotoPage(currentPage() + 1);break;
      case 'zoomIn':zoom('in');break;
      case 'zoomOut':zoom('out');break;
      case 'reset':zoom('reset');break;
      case 'nativeScale':
        if (ready && Number.isFinite(m.scale) && m.scale >= .1 && m.scale <= 10) {
          scale = m.scale;baseScale = (window.visualViewport ? visualViewport.scale : 1) / scale;
          post({ type: 'scale', scale: scale });scheduleFrame();
        }
        break;
      case 'frameAck':
        if (m.generation === generation && m.frameId === awaitingAck) {awaitingAck = 0;if (frameDirty) scheduleFrame();}
        break;
      case 'pause':paused = true;suspend();break;
      case 'resume':pageMotionSettings(m);paused = false;resume();break;
      case 'password':
        if (!passwordUpdate || m.requestId !== passwordRequest || typeof m.password !== 'string' || m.password.length > 4096) break;
        var update = passwordUpdate;passwordUpdate = null;
        vwStatus('Opening document…');armLoadTimeout();update(m.password);break;
      case 'passwordCancel':
        if (m.requestId === passwordRequest) cancel(true);
        break;
    }
  }
  addEventListener('pagehide', function () {cancel(true);});
  document.addEventListener('visibilitychange', function () {if (document.hidden) suspend();else resume();});
  protection(false);
  window.OfficeViewer = Object.freeze({ command: command, stopScrolling: function () {cancelPageNavigation();cancelReveal();scheduleFrame();}, getNativeScale: function () {return visible() && ready && window.visualViewport ? visualViewport.scale / baseScale : 0;}, getNativeZoomState: function () {return [visible() && ready && window.visualViewport ? visualViewport.scale / baseScale : 0, window.visualViewport ? visualViewport.scale : 0, window.devicePixelRatio || 1];} });
  post({ type: 'bridgeReady' });
})();
