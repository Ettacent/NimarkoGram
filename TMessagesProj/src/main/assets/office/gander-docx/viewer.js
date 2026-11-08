/* Modifications Copyright (C) 2026 Ettacent */
(() => {
  'use strict';











  const container = document.getElementById('container');
  const root = document.documentElement;
  const MAX_BYTES = 24 * 1024 * 1024;
  const urls = new Set();
  let paper = null,armInitialReady = null;
  const cancelled = new Error('Document session cancelled');
  let started = false,active = false,ready = false,finished = false;
  let paused = false,allowCopy = false,motionEnabled = true;
  let generation = 0,count = 1,index = 0,scale = 1,initialIndex = 0;
  let topInset = 0,bottomInset = 0,queryRevision = 0,query = '';
  let appliedRevision = -1,appliedQuery = '',pendingFindSteps = 0;
  let abort = null,frameTask = 0,readyTask = 0,frameId = 0,readyRevision = 0;
  let awaitingAck = 0,frameDirty = false,baseViewportScale = 1;
  let fetchResolve, fetchReject;
  const fetchGate = new Promise((resolve, reject) => {fetchResolve = resolve;fetchReject = reject;});
  window.vwFetchDoc = () => fetchGate;
  window.officeDocxGuard = () => {if (!active) throw cancelled;};

  function post(message) {
    if (!active && message.type !== 'bridgeReady') return;
    try {window.OfficeHost?.postMessage(JSON.stringify(message));} catch (_) {}
  }
  const clamp = (value, low, high) => Math.max(low, Math.min(high, Number(value) || 0));
  const cssColor = (value) => '#' + (Number(value) >>> 0).toString(16).padStart(8, '0').slice(-6);
  function accentInk(value) {
    const channels = [16, 8, 0].map((shift) => (value >>> shift & 255) / 255).
    map((v) => v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4));
    return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722 > .179 ? '#000000' : '#ffffff';
  }

  const imageGate = OfficeDocxImages.create({ guard: window.officeDocxGuard, getSignal: () => abort?.signal,
    fallback: (feature) => fail(3, [feature]) });
  window.officeDocxImagesReady = (node) => imageGate.wait(node);
  window.officeDocxPaper = Object.freeze({
    info: (image) => active && container.contains(image) ? imageGate.paper(image) : null,
    active: () => active && !paused && !document.hidden
  });
  const revokeURL = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = (url) => {imageGate.release(url);urls.delete(url);revokeURL(url);};
  const createURL = URL.createObjectURL.bind(URL);
  URL.createObjectURL = (blob) => {
    const url = createURL(blob);
    if (active) {urls.add(url);imageGate.track(url, blob);} else URL.revokeObjectURL(url);
    return url;
  };

  function colors(message) {
    pageMotionSettings(message);
    if (paper) void paper.colors(message);else commitColors(message);
  }
  function commitColors(message) {
    if (Number.isFinite(message.background)) root.style.setProperty('--vw-ground', cssColor(message.background));
    if (Number.isFinite(message.foreground)) {
      root.style.setProperty('--office-foreground', cssColor(message.foreground));
      root.style.setProperty('--vw-ink', cssColor(message.foreground));
    }
    if (Number.isFinite(message.readingPaper)) {
      root.style.setProperty('--office-night-paper', cssColor(message.readingPaper));
      vwSetPaperTheme(message.readingPaper);
    }
    if (Number.isFinite(message.accent)) {
      const accent = cssColor(message.accent);
      root.style.setProperty('--vw-accent', accent);
      root.style.setProperty('--office-find', accent + '66');
      root.style.setProperty('--office-find-active', accent);
      root.style.setProperty('--office-find-active-ink', accentInk(message.accent));
    }
    if (typeof message.preserveImages === 'boolean') {
      root.classList.toggle('office-preserve-images', message.preserveImages);
      root.classList.toggle('office-night-images', !message.preserveImages);
    }
    if (typeof message.readingDark === 'boolean') vwSetNight(message.readingDark);
  }

  function protection(value) {
    allowCopy = value === true;
    root.classList.toggle('office-protected', !allowCopy);
    if (!allowCopy) window.getSelection()?.removeAllRanges();
  }
  ['copy', 'cut', 'contextmenu', 'dragstart', 'selectstart'].forEach((type) => {
    document.addEventListener(type, (event) => {if (!allowCopy) event.preventDefault();}, true);
  });
  document.addEventListener('selectionchange', () => {
    if (!allowCopy && window.getSelection()?.rangeCount) window.getSelection().removeAllRanges();
  });
  document.addEventListener('click', (event) => {
    if (event.target.closest?.('a,area')) {event.preventDefault();event.stopImmediatePropagation();}
  }, true);
  window.vwDisarmLinks = (parent) => {
    parent.querySelectorAll('a,area').forEach((link) => {
      link.removeAttribute('href');link.removeAttribute('target');link.removeAttribute('download');
    });
  };

  function insets(message) {
    if (Number.isFinite(message.top)) topInset = clamp(message.top, 0, 4096);
    if (Number.isFinite(message.bottom)) bottomInset = clamp(message.bottom, 0, 4096);
    vwTopDp = topInset;
    vwPlaceTop();
    const vv = window.visualViewport;
    const across = vv && vv.scale > 0 ? vv.width * vv.scale : 0;
    const perDp = across > 0 && innerWidth > 0 ? innerWidth / across : 1;
    root.style.setProperty('--office-bottom', bottomInset * perDp + 'px');
    if (pageRequest) pageRequest.geometry = null;
    scheduleFrame();
  }


  let pageRequest = null,pageFrame = 0;
  const pageMotion = matchMedia('(prefers-reduced-motion: reduce)');
  function pagePoint() {
    const vv = window.visualViewport;
    return { x: vv?.pageLeft ?? scrollX, y: vv?.pageTop ?? scrollY };
  }
  function cancelPageNavigation() {
    const request = pageRequest;
    pageRequest = null;
    cancelAnimationFrame(pageFrame);pageFrame = 0;
    request?.cleanup?.();
  }
  function requestedPage() {
    if (!pageRequest) return null;
    const p = pagePoint();
    if (!pageRequest.moving && (Math.abs(p.x - pageRequest.x) > 1 || Math.abs(p.y - pageRequest.y) > 1)) cancelPageNavigation();
    return pageRequest?.index ?? null;
  }
  function navigatePage(at, smooth, sheet, instant, centerShort = false, rectScale = 1) {
    const velocity = pageRequest?.moving ? pageRequest.velocity || 0 : 0;
    cancelPageNavigation();
    const vv = window.visualViewport,root = document.documentElement;
    const request = pageRequest = { index: at, moving: true, viewportScale: vv?.scale, velocity, geometry: null, ...pagePoint() };
    const current = () => pageRequest === request && active && ready && !paused && !document.hidden && request.viewportScale === vv?.scale;
    const margins = ['scroll-margin-top', 'scroll-margin-bottom', 'scroll-margin-left', 'scroll-margin-right'];
    const saved = margins.map((name) => [name, sheet.style.getPropertyValue(name), sheet.style.getPropertyPriority(name)]);
    let usedMargins = false,observer = null,timer = 0;
    const restore = () => {
      if (!usedMargins) return;
      for (const [name, value, priority] of saved) {
        if (value) sheet.style.setProperty(name, value, priority);else sheet.style.removeProperty(name);
      }
      usedMargins = false;
    };
    request.cleanup = () => {clearTimeout(timer);observer?.disconnect();observer = null;restore();};
    const settled = () => {
      request.cleanup();Object.assign(request, pagePoint(), { moving: false, velocity: 0 });pageFrame = 0;scheduleFrame();
    };
    if (!smooth || !motionEnabled || pageMotion.matches) {instant();settled();return;}
    const px = (style, name) => parseFloat(style.getPropertyValue(name)) || 0;
    const geometry = () => {
      const height = vv?.height || innerHeight,width = vv?.width || innerWidth;
      const cached = request.geometry;
      if (cached && cached.height === height && cached.width === width) return cached;
      restore();
      const r = sheet.getBoundingClientRect(),style = getComputedStyle(sheet),rootStyle = getComputedStyle(root);
      const top = r.top / rectScale + scrollY,pageHeight = r.height / rectScale,extent = root.scrollHeight;
      const zoom = parseFloat(style.getPropertyValue('zoom')) || 1;
      const mt = px(style, 'scroll-margin-top') * zoom,mb = px(style, 'scroll-margin-bottom') * zoom;
      const pt = px(rootStyle, 'scroll-padding-top'),pb = px(rootStyle, 'scroll-padding-bottom');
      const centered = centerShort && pageHeight <= height / 2;
      const target = centered ? top + pageHeight / 2 + (mb - mt) / 2 - (height + pt - pb) / 2 : top - mt - pt;
      return request.geometry = { top, extent, height, width, zoom, paddingTop: pt,
        left: r.left / rectScale + scrollX, right: r.right / rectScale + scrollX,
        paddingLeft: px(rootStyle, 'scroll-padding-left'), paddingRight: px(rootStyle, 'scroll-padding-right'),
        marginLeft: px(style, 'scroll-margin-left') * zoom, marginRight: px(style, 'scroll-margin-right') * zoom,
        y: Math.max(0, Math.min(target, extent - height)) };
    };
    const move = (y, g) => {
      const layoutTop = y - (vv?.offsetTop || 0);
      if (layoutTop >= 0 && layoutTop <= Math.max(0, g.extent - innerHeight)) {
        window.scrollTo({ top: layoutTop, left: scrollX, behavior: 'instant' });
      } else {
        const point = pagePoint(),margin = g.top - y - g.paddingTop;
        const values = [margin, -margin, Math.max(g.marginLeft, g.left - point.x + g.paddingLeft),
        Math.max(g.marginRight, point.x + g.width - g.paddingRight - g.right)];
        margins.forEach((name, i) => sheet.style.setProperty(name, values[i] / g.zoom + 'px', 'important'));
        usedMargins = true;
        sheet.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' });
      }
      Object.assign(request, pagePoint());
    };
    if (typeof ResizeObserver === 'function') {
      observer = new ResizeObserver(() => {request.geometry = null;});
      observer.observe(sheet);if (document.body) observer.observe(document.body);
    }
    let segment = null,lastTime = performance.now(),frames = 0;
    const retarget = (point, g) => {
      const delta = g.y - point.y,distance = Math.abs(delta);
      const speed = request.velocity * delta > 0 ? request.velocity : 0;
      let duration = Math.min(900, 220 + 180 * Math.sqrt(distance / Math.max(1, g.height)));
      if (speed) duration = Math.min(duration, 3 * distance / Math.abs(speed));
      const slope = speed * duration,magnitude = Math.abs(slope),quadratic = 3 * magnitude - 6 * distance,linear = 6 * distance - 4 * magnitude;
      let maximum = magnitude;
      if (quadratic < 0) {const peak = -linear / (2 * quadratic);if (peak > 0 && peak < 1) maximum = Math.max(maximum, magnitude + linear * peak + quadratic * peak * peak);}
      segment = { start: point.y, target: g.y, delta, duration, elapsed: 0, slope, maximum: Math.max(distance, maximum), step: Math.max(.12 * g.height, .04 * distance) };
      request.velocity = speed;
    };
    const frame = (now) => {

      {
        if (!current()) {if (pageRequest === request) {cancelPageNavigation();scheduleFrame();}return;}
        pageFrame = 0;
        try {
          const point = pagePoint();
          if (Math.abs(point.x - request.x) > 1 || Math.abs(point.y - request.y) > 1 || ++frames > 2048) {cancelPageNavigation();scheduleFrame();return;}
          const g = geometry(),clock = Math.max(lastTime, now);
          let elapsed = Math.min(32, clock - lastTime);lastTime = clock;
          if (!segment || Math.abs(segment.target - g.y) > 1) retarget(point, g);
          const distance = Math.abs(segment.delta);
          if (distance) elapsed = Math.min(elapsed, segment.step * segment.duration / segment.maximum);
          segment.elapsed = Math.min(segment.duration, segment.elapsed + elapsed);
          const p = distance ? segment.elapsed / segment.duration : 1,q = 1 - p;
          const y = segment.start + segment.delta * p * p * (3 - 2 * p) + segment.slope * p * q * q;
          request.velocity = (6 * segment.delta * p * q + segment.slope * q * (1 - 3 * p)) / segment.duration;
          if (p > 0 && distance) move(y, g);
          if (p === 1) {
            request.geometry = null;
            const finalGeometry = geometry();
            if (Math.abs(finalGeometry.y - g.y) > 1) {
              retarget(pagePoint(), finalGeometry);pageFrame = requestAnimationFrame(frame);return;
            }
            if (Math.abs(pagePoint().y - g.y) <= 1) settled();else {cancelPageNavigation();scheduleFrame();}
          } else pageFrame = requestAnimationFrame(frame);
        } catch (_) {if (pageRequest === request) {cancelPageNavigation();scheduleFrame();}}
      }

    };
    timer = setTimeout(() => {if (pageRequest === request) {cancelPageNavigation();scheduleFrame();}}, 5000);
    pageFrame = requestAnimationFrame(frame);scheduleFrame();
  }
  function pageViewportResize() {
    if (pageRequest && pageRequest.viewportScale !== window.visualViewport?.scale) cancelPageNavigation();else
    if (pageRequest) pageRequest.geometry = null;
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

  function pages() {return Array.from(vwSheets());}
  function currentPage() {

    {
      const requested = requestedPage();
      if (requested !== null) return requested;
      const sheets = pages(),vv = window.visualViewport;
      return vv && sheets.length ? vwPageOnScreen(sheets, vv) - 1 : 0;
    }

  }
  function reportPosition() {
    if (!ready || !active) return;
    index = currentPage();
    post({ type: 'position', count, index, label: index + 1 + ' / ' + count });
  }

  function scheduleFrame() {
    frameDirty = true;
    if (!active || !ready || paused || document.hidden || awaitingAck || frameTask) return;
    frameTask = requestAnimationFrame(() => {
      frameTask = 0;
      if (!active || paused || document.hidden) return;
      const vv = window.visualViewport;
      const relativeScale = clamp((vv?.scale || baseViewportScale) / baseViewportScale, .1, 10);
      if (Math.abs(relativeScale - scale) > .001) {scale = relativeScale;post({ type: 'scale', scale });}
      reportPosition();
      frameDirty = false;
      awaitingAck = ++frameId;
      post({ type: 'frame', generation, frameId, coordinateRevision: 0,
        x: vv?.pageLeft || scrollX, y: vv?.pageTop || scrollY, width: vv?.width || innerWidth, scale });
    });
  }

  function gotoPage(next, smooth = true) {
    if (!ready || !active || paused || document.hidden) return;
    const sheets = pages();
    const at = Math.floor(clamp(next, 0, count - 1));
    const sheet = sheets[at];
    if (!sheet) return;
    navigatePage(at, smooth, sheet, () => vwGoToPage(at + 1, { behavior: 'instant' }), true, vwPages.rectScale || 1);
    reportPosition();
    scheduleFrame();
  }

  function nativeZoom(operation) {
    cancelPageNavigation();
    if (!ready || !active || paused) return;
    post({ type: 'nativeZoom', operation, factor: operation === 'reset' ? 1 / scale : operation === 'in' ? 1.25 : .8 });
  }
  addEventListener('scroll', scheduleFrame, { passive: true });
  window.visualViewport?.addEventListener('scroll', scheduleFrame, { passive: true });
  window.visualViewport?.addEventListener('resize', pageViewportResize, { passive: true });
  addEventListener('resize', () => {
    if (!active || !finished) return;
    insets({});
    scheduleFrame();
  });

  async function capabilities(buffer) {

    {
      const archive = await JSZip.loadAsync(buffer);
      const unsupported = new Set(),notes = new Set();
      let xmlBytes = 0;
      const entries = Object.values(archive.files);
      if (entries.length > 4096 || !archive.file('word/document.xml')) throw new Error('Invalid OOXML');
      for (const entry of entries) {
        officeDocxGuard();
        const name = entry.name;
        if (entry.dir) continue;
        if (/^word\/(diagrams|charts|embeddings)\//.test(name)) unsupported.add('embeddedDrawingOrObject');
        if (!/^word\/.*\.rels$/.test(name) && !/^word\/(document|header\d*|footer\d*|footnotes|endnotes)\.xml$/.test(name)) continue;
        if (entry._data?.uncompressedSize > 2 * 1024 * 1024) {unsupported.add('candidateXmlLimit');break;}
        const bytes = await entry.async('uint8array');
        xmlBytes += bytes.byteLength;
        if (bytes.byteLength > 2 * 1024 * 1024 || xmlBytes > 8 * 1024 * 1024) {
          unsupported.add('candidateXmlLimit');break;
        }
        const text = new TextDecoder().decode(bytes);
        if (/<!DOCTYPE|<!ENTITY/i.test(text)) throw new Error('Unsupported XML declaration');
        const xml = new DOMParser().parseFromString(text, 'application/xml');
        if (xml.getElementsByTagName('parsererror').length) throw new Error('Invalid OOXML XML');
        const nodes = xml.getElementsByTagName('*');
        if (nodes.length > 30000) {unsupported.add('candidateNodeLimit');break;}
        const mc = 'http://schemas.openxmlformats.org/markup-compatibility/2006';
        const word = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
        const fallbacks = new Map(),unused = new Set(),insideAlternate = new Set();
        for (const node of nodes) {
          const parent = node.parentElement;
          if (insideAlternate.has(parent) ||
          parent?.namespaceURI === mc && parent.localName === 'AlternateContent') insideAlternate.add(node);
          if (node.namespaceURI === mc && node.localName === 'Fallback' &&
          parent?.namespaceURI === mc && parent.localName === 'AlternateContent' &&
          parent.parentElement?.namespaceURI === word && parent.parentElement.localName === 'r' &&
          !insideAlternate.has(parent) && !fallbacks.has(parent)) fallbacks.set(parent, node);
        }
        for (const node of nodes) {
          const local = node.localName,parent = node.parentElement;
          if (local === 'Relationship' && node.getAttribute('TargetMode') === 'External' &&
          !/\/hyperlink$/.test(node.getAttribute('Type') || '')) unsupported.add('externalResource');
          if (unused.has(parent) ||
          node.namespaceURI === mc && local === 'Choice' && fallbacks.get(parent)?.firstElementChild ||
          parent?.namespaceURI === mc && parent.localName === 'Fallback' &&
          fallbacks.get(parent.parentElement) === parent && parent.firstElementChild !== node) {
            unused.add(node);continue;
          }
          if (/^(oMath|oMathPara|altChunk|object|chart|diagram|pict|shape|group|wsp)$/.test(local)) unsupported.add('mathShapeOrEmbeddedContent');
          if (local === 'graphicData' && !/\/picture$/.test(node.getAttribute('uri') || '')) unsupported.add('unsupportedDrawing');
        }
        if (/^word\/(header|footer)/.test(name)) notes.add('headerFooterLayoutApproximate');
      }
      if (!unsupported.size) await imageGate.admit(archive);
      return { unsupported: [...unsupported], notes: [...notes] };
    }

  }

  function fail(code, featureCodes = []) {
    if (!active) return;
    post({ type: 'error', code, renderer: 'gander-docx', fallbackRequired: code === 3, featureCodes });
    const status = document.getElementById('vw-status');
    status.className = 'vw-status vw-error';
    status.style.display = '';
    status.textContent = code === 3 ? 'This document needs the original reader.' : 'Could not open this Word document.';
    cancel(false);
  }
  window.vwError = () => {if (active) fail(1);};
  const oldDone = vwStatusDone;
  window.vwStatusDone = () => {
    if (!active || ready || finished) return;
    const revision = ++readyRevision,owner = generation;
    oldDone();finished = true;
    const current = () => active && !ready && finished && owner === generation && revision === readyRevision;
    const complete = () => {
      readyTask = 0;
      if (!current() || paused || document.hidden || !paper?.isReady()) return;
      count = pages().length;
      if (!count || count > 500) {fail(3, ['candidatePageLimit']);return;}
      baseViewportScale = window.visualViewport?.scale || 1;
      ready = true;armInitialReady = null;
      post({ type: 'ready', count, index: Math.floor(clamp(initialIndex, 0, count - 1)), names: [] });
      post({ type: 'scale', scale });gotoPage(initialIndex, false);sendQuery();
    };
    const arm = () => {
      if (!current() || paused || document.hidden || !paper?.isReady() || readyTask) return;
      readyTask = requestAnimationFrame(() => {
        readyTask = 0;
        if (current() && !paused && !document.hidden && paper?.isReady()) readyTask = requestAnimationFrame(complete);
      });
    };
    armInitialReady = arm;
    const initial = paper ? paper.attach(container) : Promise.resolve();
    initial.then(arm, arm);
  };

  vwFind.setSink((message) => {
    if (!active || !ready || paused || document.hidden || appliedRevision !== queryRevision || appliedQuery !== query) return;
    const fields = /^(\d+) (\d+) ([01]) ([01])$/.exec(String(message));
    if (!fields) return;
    const hits = Number(fields[2]);
    post({ type: 'search', revision: queryRevision, count: hits,
      index: Number(fields[1]) - 1, pending: fields[3] === '0', truncated: fields[4] === '1' });
    scheduleFrame();
  });
  function sendQuery() {
    if (!ready || paused || document.hidden || !active) return;
    appliedRevision = -1;
    vwFind.setPaused(false);
    vwFind.command('q' + query);
    appliedQuery = query;
    appliedRevision = queryRevision;
    if (pendingFindSteps) {vwFind.step(pendingFindSteps);pendingFindSteps = 0;}
    vwFind.report();
  }

  function cancel(clear = true) {
    cancelPageNavigation();
    active = false;ready = false;++readyRevision;armInitialReady = null;
    paper?.close();paper = null;
    abort?.abort();fetchReject(cancelled);
    cancelAnimationFrame(frameTask);cancelAnimationFrame(readyTask);
    frameTask = readyTask = 0;
    urls.forEach((url) => URL.revokeObjectURL(url));urls.clear();imageGate.clear();
    vwFind.setPaused(true);
    window.CSS?.highlights?.delete('vw-find');window.CSS?.highlights?.delete('vw-find-active');
    if (clear) container.replaceChildren();
  }

  async function open(message) {
    if (started) return;
    started = active = true;++generation;
    paper = OfficeDocxPaper.create({ live: () => active, epoch: () => generation, paused: () => paused || document.hidden,
      source: (image) => active && container.contains(image) ? imageGate.paper(image) : null,
      createURL: (blob) => {const url = createURL(blob);urls.add(url);return url;},
      revokeURL: (url) => {urls.delete(url);URL.revokeObjectURL(url);},
      commitColors: (value) => commitColors(value), onSettled: () => {if (ready) scheduleFrame();else armInitialReady?.();} });
    if (message.format !== 'docx' || root.dataset.officeRenderer !== 'gander-docx') {fail(3, ['explicitRouteRequired']);return;}
    if (message.nativeGestures === false) {fail(3, ['nativeGesturesRequired']);return;}
    if (!window.CSS?.highlights || typeof window.Highlight !== 'function') {fail(3, ['protectedSearchHighlightUnavailable']);return;}
    initialIndex = Number.isInteger(message.index) ? message.index : 0;
    root.dataset.officeNativeGestures = 'true';
    protection(message.allowCopy);colors(message);insets(message);
    container.style.visibility = 'hidden';
    abort = new AbortController();

    try {
      const response = await fetch('/document', { signal: abort.signal, credentials: 'omit', redirect: 'error', cache: 'no-store' });
      if (!response.ok) throw new Error('Document unavailable');
      const bytes = await response.arrayBuffer();
      officeDocxGuard();
      if (bytes.byteLength > MAX_BYTES) {fail(2);return;}

      const features = await capabilities(bytes);
      officeDocxGuard();
      if (features.unsupported.length) {fail(3, features.unsupported);return;}
      post({ type: 'capabilities', renderer: 'gander-docx', nativeGestures: true, notes: features.notes });
      fetchResolve(bytes);
    } catch (_) {if (active) fail(1);}

  }

  function command(message) {
    if (!message || typeof message.type !== 'string') return;
    if (message.type === 'open') {void open(message);return;}
    if (message.type === 'cancel' || message.type === 'close') {cancel();return;}
    if (!active) return;
    switch (message.type) {
      case 'colors':colors(message);scheduleFrame();break;
      case 'protection':protection(message.allowCopy);break;
      case 'insets':insets(message);break;
      case 'query':
        if (!Number.isInteger(message.revision) || message.revision < queryRevision) break;
        cancelPageNavigation();
        pendingFindSteps = 0;
        queryRevision = message.revision;
        query = String(message.query || '');
        let end = Math.min(query.length, 4096);
        if (end < query.length && end > 0 && /[\uD800-\uDBFF]/.test(query[end - 1]) && /[\uDC00-\uDFFF]/.test(query[end])) --end;
        query = query.slice(0, end).replace(/\s+/g, ' ').trim();
        if (!ready || paused || document.hidden) post({ type: 'search', revision: queryRevision, count: 0, index: -1, pending: true, truncated: false });
        sendQuery();break;
      case 'find':
        if (message.revision !== queryRevision) break;
        cancelPageNavigation();
        pendingFindSteps = Math.max(-1000, Math.min(1000, pendingFindSteps + (message.forward === false ? -1 : 1)));
        if (ready && !paused && !document.hidden && appliedRevision === queryRevision) {
          vwFind.step(pendingFindSteps);pendingFindSteps = 0;vwFind.report();
        }
        break;
      case 'goto':if (ready && !paused) gotoPage(message.index);break;
      case 'previous':if (ready && !paused) gotoPage(currentPage() - 1);break;
      case 'next':if (ready && !paused) gotoPage(currentPage() + 1);break;
      case 'zoomIn':nativeZoom('in');break;
      case 'zoomOut':nativeZoom('out');break;
      case 'reset':nativeZoom('reset');break;
      case 'nativeScale':
        if (ready && Number.isFinite(message.scale) && message.scale >= .1 && message.scale <= 10) {
          scale = message.scale;baseViewportScale = (window.visualViewport?.scale || 1) / scale;
          post({ type: 'scale', scale });scheduleFrame();
        }
        break;
      case 'frameAck':
        if (message.generation === generation && message.frameId === awaitingAck) {
          awaitingAck = 0;if (frameDirty) scheduleFrame();
        }
        break;
      case 'pause':
        cancelPageNavigation();
        paused = true;
        paper?.pause();cancelAnimationFrame(readyTask);readyTask = 0;
        vwFind.setPaused(true);
        awaitingAck = 0;frameDirty = true;
        cancelAnimationFrame(frameTask);frameTask = 0;break;
      case 'resume':
        pageMotionSettings(message);
        paused = false;
        void paper?.resume().then(() => {if (finished && !ready) armInitialReady?.();else {sendQuery();scheduleFrame();}});
        break;
    }
  }
  addEventListener('pagehide', () => cancel());
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      paper?.pause();cancelAnimationFrame(readyTask);readyTask = 0;
      cancelPageNavigation();
      vwFind.setPaused(true);
      awaitingAck = 0;frameDirty = true;
      cancelAnimationFrame(frameTask);frameTask = 0;
      return;
    }
    void paper?.resume().then(() => {if (!document.hidden && active && finished && !ready && !paused) armInitialReady?.();else
      {sendQuery();scheduleFrame();}});
  });
  protection(false);
  root.classList.add('office-preserve-images');
  window.OfficeViewer = Object.freeze({ command, stopScrolling: () => {cancelPageNavigation();scheduleFrame();}, getNativeScale: () => active && ready && !paused && !document.hidden ? (window.visualViewport?.scale || 0) / baseViewportScale : 0, getNativeZoomState: () => [active && ready && !paused && !document.hidden ? (window.visualViewport?.scale || 0) / baseViewportScale : 0, window.visualViewport?.scale || 0, window.devicePixelRatio || 1] });
  post({ type: 'bridgeReady' });
})();
