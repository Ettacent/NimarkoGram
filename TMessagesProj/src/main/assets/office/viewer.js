/* Modifications Copyright (C) 2026 Ettacent */

(() => {
  'use strict';

















  const root = document.getElementById('viewer');
  const MiB = 1024 * 1024;
  const maxBytes = 24 * MiB;
  const modules = {
    docx: ['./engine/docx.mjs', 'DocxScrollViewer'],
    pptx: ['./engine/pptx.mjs', 'PptxScrollViewer'],
    xlsx: ['./engine/xlsx.mjs', 'XlsxViewer']
  };
  const nativeDpr = window.devicePixelRatio || 1;
  const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');
  let nativeMotionEnabled = true;
  let generation = 0;
  let session = null;
  let nativePaused = false;
  let motionSuspended = false;
  const points = new Map();
  let gesture = null;
  let motionFrame = 0;
  let zoomTarget = null;
  let zoomDirection = 0;
  let fitting = false;
  let motionGeneration = 0;
  const palette = { background: 0xffe8e8e8, foreground: 0xff222222, accent: 0xff2481cc };
  let lastTap = null;
  const zoomEvents = new WeakSet();
  const reveals = new Map();
  const qualityReveals = new Map();
  let topInset = 0,bottomInset = 0;
  let pinchFrame = 0;
  let readingPalette = { dark: false, paper: '#fff', matrix: null };
  let detailTheme = 0;
  let detailPaletteKey = '';

  function updateDetailTheme() {
    const key = JSON.stringify([palette.background, palette.foreground, palette.accent, readingPalette]);
    if (key === detailPaletteKey) return;
    detailPaletteKey = key;
    ++detailTheme;
    detailChanged(session, 'theme');
  }

  function detailChanged(s, reason = 'change') {
    if (!s || !live(s)) return;
    try {s.detail?.notify(reason);} catch (_) {}
  }

  function detailInteraction(s, active) {
    try {if (active) s?.detail?.beginInteraction();else s?.detail?.endInteraction();} catch (_) {}
  }

  function resumeDetail() {
    if (nativePaused || motionSuspended || document.hidden) return;
    try {session?.detail?.resume();} catch (_) {}
    resumeReading(session);
    announceReadingReady(session);
  }

  function attachDetail(s) {
    if (!live(s) || s.format !== 'docx' || !s.announced) return;
    try {
      s.detail = window.OfficeDetail?.attach(s.viewer, {
        dpr: Number.isFinite(nativeDpr) && nativeDpr > 0 ? nativeDpr : 1,
        themeToken: () => detailTheme,
        interacting: () => !live(s) || !s.announced || !!s.pinch || points.size > 0 || !!motionFrame ||
        nativePaused || motionSuspended || document.hidden
      });
      if (nativePaused || motionSuspended || document.hidden) s.detail?.pause();
    } catch (_) {}
  }

  function cancelReveals() {
    for (const animation of reveals.values()) animation.cancel();
    reveals.clear();
  }

  function reveal(element, duration = 200, from = 0, complete = () => {}) {
    if (!element || nativePaused || motionSuspended || document.hidden || reducedMotion() || !element.animate) {complete();return;}
    reveals.get(element)?.cancel();
    const animation = element.animate([{ opacity: from }, { opacity: 1 }],
    { duration, easing: 'cubic-bezier(.2,0,0,1)' });
    reveals.set(element, animation);
    animation.onfinish = animation.oncancel = () => {
      if (reveals.get(element) === animation) reveals.delete(element);
      complete();
    };
  }

  function observeCommits(s) {
    const committed = new WeakMap();
    s.commitObserver = new MutationObserver((records) => {
      if (!live(s)) return;
      settleReducedAnimations();
      if (s.readingDeferred) {readingClip(s);resumeReading(s);}
      for (const record of records) {
        if (record.attributeName === 'data-office-sheet-region') {syncSheetReadingRegion();continue;}
        if (record.attributeName === 'data-office-quality-commit') {
          const canvas = record.target;
          if (!canvas.hasAttribute('data-office-quality-commit')) continue;
          canvas.removeAttribute('data-office-quality-commit');
          const preview = canvas.parentElement?.querySelector('[data-office-quality-preview]');
          if (preview) {
            if (s.format !== 'docx' && s.announced && !s.navigation && !nativePaused && !motionSuspended && !document.hidden && !reducedMotion() && preview.animate) {
              const animation = preview.animate([{ opacity: Number(getComputedStyle(preview).opacity) }, { opacity: 0 }],
              { duration: 160, easing: 'cubic-bezier(.2,0,0,1)', fill: 'forwards' });
              qualityReveals.set(preview, animation);
              animation.onfinish = animation.oncancel = () => {
                qualityReveals.delete(preview);
                preview.remove();
              };
            } else preview.remove();
          }
          continue;
        }
        if (record.attributeName === 'data-office-unit') {
          committed.delete(record.target);
          continue;
        }
        const canvas = record.target;
        if (canvas.tagName !== 'CANVAS') continue;
        const unit = canvas.getAttribute('data-office-valid-unit');
        const slot = canvas.closest('[data-office-unit]');
        if (unit === null || unit !== slot?.getAttribute('data-office-unit')) {
          reveals.get(canvas)?.cancel();
          reveals.delete(canvas);
          continue;
        }
        if (committed.get(slot) === unit) continue;
        committed.set(slot, unit);
        const rect = slot.getBoundingClientRect(),viewport = scrollHost(s).getBoundingClientRect();
        if (s.announced && !s.navigation && rect.bottom > viewport.top && rect.top < viewport.bottom) reveal(canvas);
      }
      for (const [element, animation] of reveals) {
        if (!element.isConnected) {animation.cancel();reveals.delete(element);}
      }
      for (const [element, animation] of qualityReveals) {
        if (!element.isConnected) {animation.cancel();qualityReveals.delete(element);}
      }
    });
    s.commitObserver.observe(root, { subtree: true, childList: true, attributes: true,
      attributeFilter: ['data-office-valid-unit', 'data-office-unit', 'data-office-quality-commit', 'data-office-sheet-region'] });
  }

  function hideCover(s) {
    if (s?.cover) {s.cover.style.opacity = '0';s.cover.hidden = true;}
  }

  function cancelNavigation(s, retainCover = false) {
    if (!s) return;
    const navigation = s.navigation;
    const pendingSheet = s.sheetPending || navigation?.sheetTarget !== undefined && !committedSheet(s, navigation.sheetTarget);
    ++s.navigationGeneration;
    s.desiredIndex = null;
    navigation?.abort.abort();
    s.recoveryAbort?.abort();
    s.navigation = null;
    if (!retainCover && !pendingSheet) hideCover(s);
    if (s.format !== 'xlsx' && retainCover) return;
    s.pageNavigationSmooth = false;
    if (navigation) {
      s.selectedIndex = null;
      if (!pendingSheet) position(s, s.format === 'xlsx' ? s.sheetMap[s.visibleIndex] : s.visibleIndex, s.count);
    }
  }

  function currentNavigation(s, navigation) {
    return live(s) && s.navigation === navigation && !navigation.abort.signal.aborted &&
    !nativePaused && !motionSuspended && !document.hidden;
  }

  function coverTo(s, navigation, opacity) {
    if (!s.cover) {
      s.cover = document.createElement('div');
      s.cover.className = 'office-navigation-cover';
      s.cover.setAttribute('aria-hidden', 'true');
      s.cover.style.opacity = '0';
      root.appendChild(s.cover);
    }
    const cover = s.cover;
    cover.hidden = false;
    if (!currentNavigation(s, navigation)) return Promise.resolve(false);
    const from = Number(getComputedStyle(cover).opacity);
    if (reducedMotion() || !cover.animate || from === opacity) {
      cover.style.opacity = String(opacity);
      return Promise.resolve(true);
    }
    return new Promise((resolve) => {
      const animation = cover.animate([{ opacity: from }, { opacity }],
      { duration: opacity ? 90 : 160, easing: 'cubic-bezier(.2,0,0,1)', fill: 'forwards' });
      const finish = (success) => {
        cover.style.opacity = success ? String(opacity) : getComputedStyle(cover).opacity;
        navigation.abort.signal.removeEventListener('abort', cancel);
        clearTimeout(timer);
        animation.onfinish = null;
        animation.cancel();
        resolve(success);
      };
      const cancel = () => finish(false);
      const timer = setTimeout(cancel, 1000);
      animation.onfinish = () => finish(currentNavigation(s, navigation));
      navigation.abort.signal.addEventListener('abort', cancel, { once: true });
    });
  }

  function committedPage(s, slot) {
    const canvas = slot?.querySelector('canvas'),unit = slot?.getAttribute('data-office-unit');
    return !!canvas && canvas.getAttribute('data-office-valid-unit') === unit &&
    canvas.getAttribute('data-office-painted-unit') === unit &&
    Number(canvas.getAttribute('data-office-painted-scale')) === s.viewer.getScale();
  }

  function committedSheet(s, index) {
    const canvas = root.querySelector('canvas[data-office-painted-sheet]');
    return !!canvas && canvas.getAttribute('data-office-painted-sheet') === String(s.sheetMap[index]) &&
    Number(canvas.getAttribute('data-office-painted-scale')) === s.viewer.getScale() &&
    Number(canvas.getAttribute('data-office-painted-width')) === canvas.parentElement.clientWidth &&
    Number(canvas.getAttribute('data-office-painted-height')) === canvas.parentElement.clientHeight;
  }

  function pageDestination(host, slot) {
    if (!slot) return NaN;
    const height = parseFloat(host.firstElementChild.style.height);
    return Math.max(0, Math.min(slot.offsetTop - topInset, Math.max(0, height - host.clientHeight)));
  }

  function waitForNavigationPaint(s, navigation, recovering = false) {

    return new Promise((resolve) => {
      const host = scrollHost(s);
      let frame = 0,quiet = 0,frames = 0;
      const ready = () => {
        if (s.format === 'xlsx') return (recovering || !s.sheetPending) &&
        s.visibleIndex === navigation.target && committedSheet(s, navigation.target);
        const slot = host.querySelector(`[data-office-unit="${navigation.target}"]`);
        if (!committedPage(s, slot)) return false;
        const top = pageDestination(host, slot);
        if (!Number.isFinite(top) || Math.abs(host.scrollTop - top) > 1) return false;
        const viewport = host.getBoundingClientRect();
        for (const visible of host.querySelectorAll('[data-office-unit]')) {
          const rect = visible.getBoundingClientRect();
          if (rect.bottom > viewport.top && rect.top < viewport.bottom &&
          rect.right > viewport.left && rect.left < viewport.right &&
          !committedPage(s, visible)) return false;
        }
        return true;
      };
      const finish = (success) => {
        clearTimeout(deadline);
        clearTimeout(quiet);
        cancelAnimationFrame(frame);
        observer.disconnect();
        host.removeEventListener('scroll', schedule);
        navigation.abort.signal.removeEventListener('abort', cancel);

        resolve(success);
      };
      const cancel = () => finish(false);
      const check = () => {
        frame = 0;
        if (recovering ? !live(s) || navigation.abort.signal.aborted : !currentNavigation(s, navigation)) {finish(false);return;}
        if (!ready()) {frames = 0;return;}
        if (recovering || ++frames === 2) finish(true);else
        frame = requestAnimationFrame(check);
      };
      const schedule = () => {
        frames = 0;
        cancelAnimationFrame(frame);
        clearTimeout(quiet);
        quiet = setTimeout(() => {if (recovering) check();else frame = requestAnimationFrame(check);}, 60);
      };
      const observer = new MutationObserver(schedule);
      observer.observe(root, { subtree: true, childList: true, attributes: true,
        attributeFilter: ['data-office-painted-unit', 'data-office-painted-scale', 'data-office-unit',
        'data-office-painted-sheet', 'data-office-painted-width', 'data-office-painted-height'] });
      const deadline = setTimeout(cancel, 8000);
      navigation.abort.signal.addEventListener('abort', cancel, { once: true });
      host.addEventListener('scroll', schedule, { passive: true });
      schedule();
    });
  }

  function untilNavigationCancelled(operation, abort) {
    return new Promise((resolve, reject) => {
      const finish = (complete, result) => {abort.signal.removeEventListener('abort', cancel);complete(result);};
      const cancel = () => finish(resolve, false);
      operation.then(() => finish(resolve, true), (error) => finish(reject, error));
      if (abort.signal.aborted) cancel();else
      abort.signal.addEventListener('abort', cancel, { once: true });
    });
  }

  async function finishInterruptedSheet(s) {
    if (!live(s) || s.format !== 'xlsx' || s.desiredIndex !== null) return;
    const recovery = { abort: new AbortController() };
    s.recoveryAbort = recovery.abort;
    if (s.sheetWork) await untilNavigationCancelled(s.sheetWork, recovery.abort);
    if (!live(s) || recovery.abort.signal.aborted) return;
    recovery.target = s.sheetMap.indexOf(s.viewer.sheetIndex);
    s.sheetPending = true;
    const painted = await waitForNavigationPaint(s, recovery, true);
    s.sheetPending = false;
    if (!live(s)) return;
    if (s.recoveryAbort === recovery.abort) s.recoveryAbort = null;
    if (!recovery.abort.signal.aborted) {
      if (!painted) throw { officeCode: 5 };
      position(s, s.sheetMap[recovery.target], s.count);
    }
  }

  function stopMotion(retainNavigationCover = false) {
    finishPinch(session);
    cancelNavigation(session, retainNavigationCover);
    stopViewportMotion();
  }

  function stopViewportMotion() {
    if (session?.pinch?.owner === 'animation') finishPinch(session);
    cancelReveals();
    motionGeneration++;
    cancelAnimationFrame(motionFrame);
    motionFrame = 0;
    zoomTarget = null;
    zoomDirection = 0;
    fitting = false;
    const host = session && scrollHost(session);
    if (host) host.scrollTo({ left: host.scrollLeft, top: host.scrollTop, behavior: 'auto' });
    detailInteraction(session, false);
  }

  function pause() {
    try {session?.detail?.pause();} catch (_) {}
    motionSuspended = true;
    stopGlassFrames(session);
    session?.viewer?.cancelOfficeReading?.('paused');
    session?.readingAbort?.abort();
    finishReading(session, false, 'paused');
    stopMotion();
    if (session) {
      session.desiredIndex = null;
      ++session.navigationGeneration;
    }
    for (const id of points.keys()) {
      if (root.hasPointerCapture(id)) root.releasePointerCapture(id);
    }
    points.clear();
    gesture = null;
    lastTap = null;
  }

  function openingDeadline(s) {
    if (!s || !live(s)) return;
    clearTimeout(s.timer);
    s.timer = null;
    if (s.openingStarted !== null) {
      s.openingRemaining = Math.max(0, s.openingRemaining - (performance.now() - s.openingStarted));
      s.openingStarted = null;
    }
    if (s.openingComplete || nativePaused || document.hidden) return;
    if (s.openingRemaining <= 0) {fail(s, { officeCode: 5 });return;}
    s.openingStarted = performance.now();
    s.timer = setTimeout(() => openingDeadline(s), s.openingRemaining);
  }

  function waitForInitialPaint(s) {

    return new Promise((resolve) => {
      let frames = 0;
      const committed = () => {
        if (s.format === 'xlsx') return committedSheet(s, s.index);
        const host = scrollHost(s),viewport = host.getBoundingClientRect();
        let destination = false,visible = false;
        for (const slot of host.querySelectorAll('[data-office-unit]')) {
          const unit = slot.getAttribute('data-office-unit');
          const painted = committedPage(s, slot);
          if (Number(unit) === (s.selectedIndex ?? s.visibleIndex)) destination = painted;
          const rect = slot.getBoundingClientRect();
          if (rect.bottom > viewport.top && rect.top < viewport.bottom && rect.right > viewport.left && rect.left < viewport.right) {
            visible = true;
            if (!painted) return false;
          }
        }
        return destination && visible;
      };
      const finish = () => {
        observer.disconnect();
        window.removeEventListener('resize', check);
        cancelAnimationFrame(s.paintFrame);
        s.paintFrame = 0;
        s.finishPaint = null;

        resolve();
      };
      const frame = () => {
        s.paintFrame = 0;
        if (!live(s)) {finish();return;}
        if (!committed()) {frames = 0;return;}
        if (++frames === 2) finish();else
        s.paintFrame = requestAnimationFrame(frame);
      };
      const check = () => {
        if (!live(s)) {finish();return;}
        if (!committed()) {
          cancelAnimationFrame(s.paintFrame);
          s.paintFrame = 0;
          frames = 0;
        } else if (!s.paintFrame) s.paintFrame = requestAnimationFrame(frame);
      };
      const observer = new MutationObserver(check);
      observer.observe(root, { subtree: true, childList: true, attributes: true,
        attributeFilter: ['data-office-unit', 'data-office-painted-unit', 'data-office-painted-scale',
        'data-office-painted-sheet', 'data-office-painted-width', 'data-office-painted-height', 'style'] });
      window.addEventListener('resize', check);
      s.finishPaint = finish;
      check();
    });
  }

  function send(message) {
    try {window.OfficeHost.postMessage(JSON.stringify(message));} catch (_) {}
  }

  function queueGlassFrame(s) {
    if (!s || !live(s)) return;
    s.glassDirty = true;
    if (!s.announced || nativePaused || motionSuspended || document.hidden || s.glassAwaiting || s.glassFrame) return;
    s.glassFrame = requestAnimationFrame(() => {
      s.glassFrame = 0;
      if (!live(s) || nativePaused || motionSuspended || document.hidden || s.glassAwaiting) return;
      const host = scrollHost(s);
      if (!host || root.clientWidth <= 0) return;
      if (s.format !== 'xlsx') position(s, s.visibleIndex, s.count);
      s.glassDirty = false;
      s.glassAwaiting = true;
      send({ type: 'frame', generation: s.generation, frameId: ++s.glassId,
        x: host.scrollLeft, y: host.scrollTop, width: root.clientWidth,
        scale: s.viewer.getScale(), coordinateRevision: s.coordinateRevision || 0 });
    });
  }

  function observeGlassFrames(s) {
    s.glassId = 0;
    s.glassScroll = () => queueGlassFrame(s);
    scrollHost(s).addEventListener('scroll', s.glassScroll, { passive: true });
    s.glassObserver = new MutationObserver(() => queueGlassFrame(s));
    s.glassObserver.observe(root, { subtree: true, childList: true, attributes: true,
      attributeFilter: ['style', 'width', 'height', 'data-office-painted-unit', 'data-office-painted-sheet', 'data-office-quality-commit'] });
  }

  function stopGlassFrames(s) {
    if (!s) return;
    cancelAnimationFrame(s.glassFrame);
    s.glassFrame = 0;
    s.glassAwaiting = false;
    ++s.glassId;
  }

  function live(s) {return session === s && s.generation === generation;}
  function color(value) {
    const n = value >>> 0;
    return `rgba(${n >>> 16 & 255},${n >>> 8 & 255},${n & 255},${(n >>> 24) / 255})`;
  }
  function colors(command) {
    queueGlassFrame(session);
    for (const [key, variable] of [['background', '--desk'], ['foreground', '--foreground'], ['accent', '--accent']]) {
      if (Number.isInteger(command[key])) palette[key] = command[key];
      document.documentElement.style.setProperty(variable, color(palette[key]));
    }
    const style = document.documentElement.style;
    const rgb = (value) => [value >>> 16 & 255, value >>> 8 & 255, value & 255];
    const background = rgb(palette.background),foreground = rgb(palette.foreground),accent = rgb(palette.accent);
    const mix = (a, b, weight) => `rgb(${a.slice(0, 3).map((v, i) => Math.round(v * (1 - weight) + b[i] * weight)).join(',')})`;
    style.colorScheme = background[0] * 0.2126 + background[1] * 0.7152 + background[2] * 0.0722 < 128 ? 'dark' : 'light';
    style.setProperty('--chrome-muted', mix(background, foreground, 0.05));
    style.setProperty('--chrome-border', mix(background, foreground, 0.3));
    style.setProperty('--chrome-selected', mix(background, accent, 0.25));
    style.setProperty('--find-match', `rgba(${accent.slice(0, 3).join(',')},0.24)`);
    style.setProperty('--find-active', `rgba(${accent.slice(0, 3).join(',')},0.44)`);
    updateDetailTheme();
    if (command.readingDark === false) return readingColors({ dark: false, paper: '#fff', matrix: null });
    if (command.readingDark === true && Number.isInteger(command.readingPaper) &&
    Array.isArray(command.readingMatrix) && command.readingMatrix.length === 20 &&
    command.readingMatrix.every((value) => Number.isFinite(value) && Math.abs(value) <= 4)) {
      return readingColors({ dark: true, paper: color(command.readingPaper), matrix: command.readingMatrix });
    }
    return Promise.resolve();
  }

  function readingIdentity(value) {return JSON.stringify(value.dark ? value.matrix : null);}

  function holdReading(s, value) {
    if (!live(s)) return;
    if (!s.readingCover) {
      s.readingCover = document.createElement('div');
      s.readingCover.className = 'office-reading-cover';
      s.readingCover.setAttribute('aria-hidden', 'true');
      root.appendChild(s.readingCover);
    }
    s.readingCover.hidden = false;
    s.readingCover.style.opacity = '1';
    s.readingCover.style.backgroundColor = value.paper;
    readingClip(s);
  }

  function resumeReading(s) {
    if (!s || !live(s) || !s.readingDeferred || s.readingRetry || nativePaused || motionSuspended || document.hidden) return;
    s.readingRetry = true;
    void applyReading(s.readingDeferred).catch((error) => {if (live(s)) fail(s, error);}).
    finally(() => {s.readingRetry = false;});
  }

  function applyReading(value) {
    globalThis.OfficePaperConsumer?.setPalette(value);

    {
      readingPalette = value;
      updateDetailTheme();
      const style = document.documentElement.style;
      style.setProperty('--office-paper', value.dark ? value.paper : '#fff');
      style.setProperty('--office-page-filter', 'none');
      style.setProperty('--office-sheet-filter', 'none');

    }
    const s = session,key = readingIdentity(value);
    if (!s?.viewer) return Promise.resolve({ status: 'unmounted' });
    if (s.readingDeferred) s.readingDeferred = value;
    if (s.readingWork?.key === key && !s.readingWork.controller.signal.aborted) return s.readingWork.promise;
    s.readingAbort?.abort();
    const controller = s.readingAbort = new AbortController();
    const work = s.readingWork = { key, controller };
    work.promise = (async () => {
      if (typeof s.viewer.setOfficeReadingPalette !== 'function') throw { officeCode: 3 };
      const result = await s.viewer.setOfficeReadingPalette(value, { signal: controller.signal,
        paused: nativePaused || motionSuspended || document.hidden, timeoutMs: 500 });
      if (!live(s) || s.readingWork !== work) return { status: 'cancelled' };
      if (result.status === 'failed') throw result.error;
      if (result.status !== 'committed') {
        s.readingDeferred = value;
        holdReading(s, value);
        return result;
      }
      s.readingCommittedKey = readingIdentity(value);
      s.readingDeferred = null;
      if (!s.readingTransition && s.readingCover) {
        s.readingCover.style.opacity = '0';s.readingCover.hidden = true;
      }
      announceReadingReady(s);
      return result;
    })().finally(() => {if (s.readingWork === work) s.readingWork = null;});
    return work.promise;
  }

  function announceReadingReady(s) {
    if (!s || !live(s) || s.announced || !s.initialPaintComplete || nativePaused || motionSuspended || document.hidden ||
    s.readingDeferred || s.readingCommittedKey !== readingIdentity(readingPalette)) return;
    s.openingComplete = true;
    openingDeadline(s);
    send({ type: 'ready', count: s.count, index: s.index, names: s.names });
    s.announced = true;
    attachDetail(s);
    queueGlassFrame(s);
    position(s, s.format === 'xlsx' ? s.sheetMap[s.index] : s.visibleIndex, s.count);
    send({ type: 'scale', scale: s.viewer.getScale() });
  }

  function readingClip(s) {
    const cover = s.readingCover,viewport = root.getBoundingClientRect();
    const rectangles = [];
    if (s.format === 'xlsx') {
      const canvas = root.querySelector('canvas[data-office-sheet-region]');
      if (canvas) {
        const rect = canvas.getBoundingClientRect();
        const region = canvas.getAttribute('data-office-sheet-region').split(' ').map(Number);
        if (region.length === 4 && region.every(Number.isFinite)) rectangles.push({
          left: rect.left + rect.width * region[0], top: rect.top + rect.height * region[1],
          right: rect.left + rect.width * (region[0] + region[2]), bottom: rect.top + rect.height * (region[1] + region[3]) });
      }
    } else {
      for (const slot of scrollHost(s)?.querySelectorAll('[data-office-unit]') || []) rectangles.push(slot.getBoundingClientRect());
    }
    let path = '';
    for (const rect of rectangles) {
      const left = Math.max(0, rect.left - viewport.left),top = Math.max(0, rect.top - viewport.top);
      const right = Math.min(viewport.width, rect.right - viewport.left),bottom = Math.min(viewport.height, rect.bottom - viewport.top);
      if (right > left && bottom > top) path += `M${left} ${top}H${right}V${bottom}H${left}Z`;
    }
    cover.style.clipPath = `path('${path || 'M0 0Z'}')`;
  }

  function finishReading(s, commit = true, status = 'cancelled') {
    const transaction = s?.readingTransition;
    if (!transaction) return;
    s.readingTransition = null;
    transaction.interrupt?.();
    clearTimeout(transaction.deadline);
    cancelAnimationFrame(transaction.frame);
    transaction.observer.disconnect();
    transaction.resize.disconnect();
    scrollHost(s)?.removeEventListener('scroll', transaction.schedule);
    const committed = commit && s.readingCommittedKey === readingIdentity(transaction.pending);
    if (committed) {
      s.readingCover.style.opacity = '0';s.readingCover.hidden = true;
    } else {
      s.viewer?.cancelOfficeReading?.(status);
      s.readingAbort?.abort();
      s.readingDeferred = transaction.pending;
      holdReading(s, transaction.pending);
    }
    transaction.resolve({ status: committed ? 'committed' : status });
  }

  function animateReading(s, transaction, property, destination, duration) {
    const cover = s.readingCover;
    if (!live(s) || s.readingTransition !== transaction) return Promise.resolve(false);
    const from = getComputedStyle(cover)[property];
    return new Promise((resolve) => {
      const animation = cover.animate([{ [property]: from }, { [property]: destination }],
      { duration, easing: 'cubic-bezier(.2,0,0,1)', fill: 'forwards' });
      const finish = (success) => {
        cover.style[property] = success ? String(destination) : getComputedStyle(cover)[property];
        animation.onfinish = animation.oncancel = null;
        animation.cancel();
        if (transaction.interrupt === cancel) transaction.interrupt = null;
        resolve(success);
      };
      const cancel = () => finish(false);
      transaction.interrupt = cancel;
      animation.onfinish = () => finish(true);
      animation.oncancel = cancel;
    });
  }

  async function runReading(s, transaction) {
    while (live(s) && s.readingTransition === transaction) {
      const revision = transaction.revision;
      if (readingIdentity(transaction.pending) !== readingIdentity(readingPalette)) {
        if (!(await animateReading(s, transaction, 'opacity', 1, 80))) continue;
        if (revision !== transaction.revision || s.readingTransition !== transaction) continue;
        if (!(await animateReading(s, transaction, 'backgroundColor', transaction.pending.paper, 80))) continue;
        if (revision !== transaction.revision || s.readingTransition !== transaction) continue;
      } else if (Number(getComputedStyle(s.readingCover).opacity) >= .999) {
        if (!(await animateReading(s, transaction, 'backgroundColor', transaction.pending.paper, 80))) continue;
        if (revision !== transaction.revision || s.readingTransition !== transaction) continue;
      }
      const result = await applyReading(transaction.pending);
      if (revision !== transaction.revision || s.readingTransition !== transaction) continue;
      if (result.status !== 'committed') {finishReading(s, false, result.status);return;}
      s.readingCover.style.backgroundColor = transaction.pending.paper;
      if (!(await animateReading(s, transaction, 'opacity', 0, 140))) continue;
      if (revision !== transaction.revision || s.readingTransition !== transaction) continue;
      finishReading(s);
    }
  }

  function readingColors(value) {
    const s = session;
    const initialPalette = s?.announced && !s.readingPresented;
    if (s?.announced) s.readingPresented = true;
    if (!s?.announced || initialPalette || nativePaused || motionSuspended || document.hidden || reducedMotion()) {
      finishReading(s, false);
      return applyReading(value);
    }
    const transaction = s.readingTransition;
    if (transaction) {
      const reversed = readingIdentity(transaction.pending) !== readingIdentity(value);
      transaction.pending = value;
      if (readingIdentity(value) === readingIdentity(readingPalette)) {
        s.readingCover.style.backgroundColor = value.paper;
      }
      if (reversed) {++transaction.revision;transaction.interrupt?.();}
      return transaction.promise;
    }
    if (readingIdentity(value) === readingIdentity(readingPalette) || s.cover && !s.cover.hidden && Number(getComputedStyle(s.cover).opacity) >= .999) {
      return applyReading(value);
    }
    if (!s.readingCover) {
      s.readingCover = document.createElement('div');
      s.readingCover.className = 'office-reading-cover';
      s.readingCover.setAttribute('aria-hidden', 'true');
      root.appendChild(s.readingCover);
    }
    const cover = s.readingCover;
    if (!cover.animate) {holdReading(s, value);return applyReading(value);}
    cover.style.opacity = '0';
    cover.style.backgroundColor = readingPalette.paper;
    cover.hidden = false;
    const work = { pending: value, revision: 0, frame: 0 };
    work.promise = new Promise((resolve) => {work.resolve = resolve;});
    s.readingTransition = work;
    work.schedule = () => {
      if (!work.frame) work.frame = requestAnimationFrame(() => {
        work.frame = 0;
        if (live(s) && s.readingTransition === work) readingClip(s);
      });
    };
    work.observer = new MutationObserver((records) => {
      if (records.some((record) => record.target !== cover)) work.schedule();
    });
    work.observer.observe(root, { subtree: true, childList: true, attributes: true,
      attributeFilter: ['data-office-unit', 'data-office-sheet-region', 'style'] });
    work.resize = new ResizeObserver(work.schedule);
    work.resize.observe(root);
    scrollHost(s)?.addEventListener('scroll', work.schedule, { passive: true });
    readingClip(s);
    work.deadline = setTimeout(() => {if (s.readingTransition === work) finishReading(s, false, 'timeout');}, 700);
    void runReading(s, work).catch((error) => {if (s.readingTransition === work) finishReading(s, false, 'failed');if (live(s)) fail(s, error);});
    return work.promise;
  }

  function syncSheetReadingRegion() {
    const canvas = root.querySelector('canvas[data-office-sheet-region]');
    if (!canvas) return;
    const values = canvas.getAttribute('data-office-sheet-region').split(' ').map(Number);
    if (values.length !== 4 || !values.every(Number.isFinite)) return;
    const region = document.getElementById('office-sheet-reading-region');
    for (let i = 0; i < 4; i++) region.setAttribute(['x', 'y', 'width', 'height'][i], String(values[i]));
  }

  function insets(command) {
    const previous = topInset,previousBottom = bottomInset;
    if (Number.isFinite(command.top)) topInset = Math.max(0, command.top);
    if (Number.isFinite(command.bottom)) bottomInset = Math.max(0, command.bottom);
    document.documentElement.style.setProperty('--office-bottom-inset', `${bottomInset}px`);
    const s = session;
    if (!s?.viewer) return;
    if (previous === topInset && previousBottom === bottomInset &&
    scrollHost(s).hasAttribute('data-office-top-inset')) return;
    stopMotion();
    const host = scrollHost(s),before = host.scrollTop;
    host.setAttribute('data-office-top-inset', String(topInset));
    host.setAttribute('data-office-bottom-inset', String(bottomInset));
    if (s.format === 'xlsx') s.viewer.setTopInset(topInset);else
    if (previous !== topInset && s.ready) {
      s.viewer.relayout();
      host.scrollTop = before <= 16 ? before : before + topInset - previous;
    }
    detailChanged(s);
  }

  function dispose() {
    session?.viewer?.cancelOfficeReading?.('destroyed');
    session?.readingAbort?.abort();
    finishReading(session, false);
    pause();
    for (const animation of qualityReveals.values()) animation.cancel();
    qualityReveals.clear();
    generation++;
    const old = session;
    session = null;
    points.clear();
    gesture = null;
    if (old) {
      try {old.detail?.dispose();} catch (_) {}
      old.detail = null;
      stopGlassFrames(old);
      old.glassObserver?.disconnect();
      scrollHost(old)?.removeEventListener('scroll', old.glassScroll);
      old.abort.abort();
      clearTimeout(old.timer);
      clearTimeout(old.searchTimer);
      clearTimeout(old.searchDeadline);
      cancelAnimationFrame(old.paintFrame);
      old.finishPaint?.();
      old.resizeObserver?.disconnect();
      old.commitObserver?.disconnect();
      old.pendingSearch = null;
      old.pendingFind = null;
      try {old.viewer?.destroy();} catch (_) {}
    }
    root.replaceChildren();
  }

  function errorCode(error) {
    if (Number.isInteger(error?.officeCode)) return error.officeCode;
    if (['encrypted', 'invalid-password', 'unsupported-encryption'].includes(error?.code)) return 4;
    if (['ooxml-resource-limit', 'ooxml-decoded-image-limit'].includes(error?.code)) return 2;
    if (/timeout|timed out/i.test(String(error?.name) + ' ' + String(error?.message))) return 5;
    if (error instanceof SyntaxError || typeof WebAssembly === 'object' && error instanceof WebAssembly.CompileError) return 3;
    return 1;
  }

  function fail(s, error) {
    if (!live(s)) return;
    const code = errorCode(error);
    dispose();
    send({ type: 'error', code });
  }

  function position(s, index, count) {
    if (!live(s)) return;
    if (s.format === 'xlsx') {
      if (!s.sheetMap) return;
      index = s.sheetMap.indexOf(index);
      count = s.sheetMap.length;
      if (index < 0) return;
    }
    s.visibleIndex = index;
    s.count = count;
    if (s.sheetPending || s.navigation && !s.navigation.arrived) return;
    if (s.format !== 'xlsx') {
      const host = scrollHost(s),end = host.scrollHeight - host.clientHeight;
      if (host.scrollTop <= 1) index = 0;else
      if (end > 1 && host.scrollTop >= end - 1) index = count - 1;
    }
    s.index = Math.max(0, s.selectedIndex ?? index);
    const label = s.names[s.index] || String(s.index + 1);
    if (s.announced && (s.lastPosition?.index !== s.index || s.lastPosition?.count !== count || s.lastPosition?.label !== label)) {
      s.lastPosition = { index: s.index, count, label };
      send({ type: 'position', ...s.lastPosition });
    }
  }

  function releasePageSelection(s) {
    if (s.format === 'xlsx' || s.selectedIndex === null) return;
    s.selectedIndex = null;
    position(s, s.visibleIndex, s.count);
  }

  function selectPage(s, index) {
    if (s.format === 'xlsx') return;
    s.selectedIndex = index;
    position(s, s.visibleIndex, s.count);
  }

  function compatible() {
    return typeof WebAssembly === 'object' && typeof fetch === 'function' &&
    typeof AbortController === 'function' && typeof ResizeObserver === 'function' &&
    typeof PointerEvent === 'function' && typeof TextDecoder === 'function' &&
    !!document.createElement('canvas').getContext('2d');
  }

  function workerMode() {
    try {
      return typeof Worker === 'function' && typeof OffscreenCanvas === 'function' &&
      typeof createImageBitmap === 'function' && !!new OffscreenCanvas(1, 1).getContext('2d');
    } catch (_) {return false;}
  }

  async function documentBytes(s) {

    {
      const response = await fetch(new URL('/document', location.origin), {
        signal: s.abort.signal, credentials: 'omit', cache: 'no-store', redirect: 'error'
      });
      if (response.status === 413) throw { officeCode: 2 };
      if (!response.ok) throw { officeCode: 1 };
      const length = Number(response.headers.get('content-length'));
      if (length > maxBytes) throw { officeCode: 2 };
      if (!response.body?.getReader) throw { officeCode: 3 };
      const reader = response.body.getReader();
      const chunks = [];
      let size = 0;
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > maxBytes) throw { officeCode: 2 };
          chunks.push(value);
        }
      } catch (error) {
        await reader.cancel().catch(() => {});
        throw error;
      } finally {reader.releaseLock();}
      if (!size) throw { officeCode: 1 };
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) {bytes.set(chunk, offset);offset += chunk.byteLength;}
      return bytes.buffer;
    }

  }

  async function navigate(s, index, smooth = true) {
    if (!live(s) || !s.ready || !Number.isFinite(index) || !s.count) return;
    if (s.announced && (nativePaused || motionSuspended || document.hidden)) return;
    const target = Math.max(0, Math.min(s.count - 1, Math.trunc(index)));
    const pageRetarget = s.format !== 'xlsx' && s.pageNavigationSmooth &&
    Math.abs(target - (s.desiredIndex ?? s.navigation?.target ?? s.index)) <= 1;
    stopMotion(true);
    s.desiredIndex = target;
    s.navigationSmooth = smooth;
    s.pageNavigationSmooth = !!pageRetarget;
    if (s.navigationRunning) return s.navigationRunning;
    s.navigationRunning = (async () => {
      await Promise.resolve();
      if (s.findNavigation) await s.findNavigation.catch(() => {});
      while (live(s) && s.desiredIndex !== null) {
        const target = s.desiredIndex,request = s.navigationGeneration;
        const navigation = { target, abort: new AbortController(), arrived: false };
        s.navigation = navigation;
        const host = scrollHost(s),slot = host.querySelector(`[data-office-unit="${target}"]`);
        const nearby = s.format !== 'xlsx' &&
        Math.abs(pageDestination(host, slot) - host.scrollTop) <= host.clientHeight * 1.5;
        const sameSheet = s.format === 'xlsx' && s.viewer.sheetIndex === s.sheetMap[target] && (
        !s.sheetWork || s.sheetWorkTarget === target);
        const retarget = s.format !== 'xlsx' && s.pageNavigationSmooth && s.navigationSmooth && !reducedMotion();
        const covered = s.announced && (Number(s.cover?.style.opacity) > 0 || !sameSheet && !nearby && !retarget);
        if (covered && !(await coverTo(s, navigation, 1))) {
          if (request !== s.navigationGeneration) continue;
          cancelNavigation(s);
          break;
        }
        if (!live(s) || request !== s.navigationGeneration) continue;
        if (s.format === 'xlsx') {
          if (!sameSheet) {
            let timer;
            navigation.sheetTarget = target;
            s.sheetPending = true;
            const pending = Promise.race([s.viewer.goToSheet(s.sheetMap[target]), new Promise((_, reject) => {
              timer = setTimeout(() => reject({ officeCode: 5 }), s.announced ? 8000 : 30000);
            })]).finally(() => {
              clearTimeout(timer);
              if (s.sheetWork === pending) {s.sheetWork = null;s.sheetWorkTarget = null;s.sheetPending = false;}
            });
            s.sheetWork = pending;
            s.sheetWorkTarget = target;
            await untilNavigationCancelled(pending, navigation.abort);
          }
        } else {
          const options = { behavior: s.announced && s.navigationSmooth && !covered && !reducedMotion() ? 'smooth' : 'auto' };
          s.pageNavigationSmooth = options.behavior === 'smooth';
          if (s.format === 'docx') s.viewer.scrollToPage(target, options);else
          s.viewer.scrollToSlide(target, options);
        }
        if (!live(s)) return;
        if (request !== s.navigationGeneration) {
          await finishInterruptedSheet(s);
          continue;
        }
        const painted = !s.announced || (await waitForNavigationPaint(s, navigation));
        if (!live(s)) return;
        if (request !== s.navigationGeneration) {await finishInterruptedSheet(s);continue;}
        if (!painted) {
          if (covered) throw { officeCode: 5 };
          cancelNavigation(s);
          break;
        }
        navigation.arrived = true;
        if (s.format === 'xlsx') position(s, s.sheetMap[target], s.count);else
        selectPage(s, target);
        if (covered) await coverTo(s, navigation, 0);
        if (request === s.navigationGeneration) {
          hideCover(s);
          s.navigation = null;
          s.desiredIndex = null;
          s.pageNavigationSmooth = false;
          break;
        }
      }
      if (live(s) && !s.navigation) hideCover(s);
    })();
    try {await s.navigationRunning;} finally {s.navigationRunning = null;}
  }

  async function open(command) {
    dispose();
    nativeMotionEnabled = command.motionEnabled !== false;
    motionSuspended = nativePaused || document.hidden;
    colors(command);
    insets({ top: command.top ?? 0, bottom: command.bottom ?? 0 });
    const s = { generation, format: command.format, abort: new AbortController(), viewer: null,
      ready: false, count: 0, index: 0, names: [], navigationGeneration: 0, searchGeneration: 0,
      selectedIndex: null, visibleIndex: 0,
      openingRemaining: 60000, openingStarted: null, openingComplete: false,
      desiredIndex: null, navigationRunning: null,
      searchRunning: false, searchPending: false, pendingSearch: null, pendingFind: null,
      matches: 0, matchIndex: -1, truncated: false, locations: null };
    session = s;
    openingDeadline(s);
    try {
      if (!Object.hasOwn(modules, s.format)) throw { officeCode: 1 };
      if (location.protocol !== 'https:' || !compatible()) throw { officeCode: 3 };
      Object.defineProperty(window, 'devicePixelRatio', { configurable: true, get: () =>
        Math.min(2, nativeDpr, Math.sqrt(4 * 1024 * 1024 / Math.max(1, root.clientWidth * root.clientHeight))) });
      let engine;
      let math;
      try {
        [engine, { math }] = await Promise.all([import(modules[s.format][0]), import('./engine/math.mjs')]);
      } catch (_) {throw { officeCode: 3 };}
      if (!live(s)) return;
      const bytes = await documentBytes(s);
      if (!live(s)) return;
      root.className = s.format;
      const options = {
        mode: workerMode() ? 'worker' : 'main', useGoogleFonts: false, debug: false, math,
        officeReadingPalette: readingPalette,
        workerTimeoutMs: 30000, wasmUrl: new URL(`./engine/${s.format}_parser_bg.wasm`, location.href).href,
        resourceLimits: { maxArchiveEntryBytes: maxBytes, maxTotalInflatedBytes: 96 * MiB, maxArchiveEntries: 4096 },
        xlsxWorksheetLimits: { maxRows: 100000, maxCells: 250000, maxOwnedUtf8Bytes: 16 * MiB, maxJsonBytes: 32 * MiB },
        imageResources: { decodedByteBudget: 16 * MiB, strategy: 'adaptive', resolution: 'display' },
        dpr: s.format === 'docx' ? Number.isFinite(nativeDpr) && nativeDpr > 0 ? nativeDpr : 1 : window.devicePixelRatio, overscan: 1, enableTextSelection: false,
        enableElementSelection: false, enableHyperlinks: false, onHyperlinkClick: () => {},
        comments: false, enableMediaPlayback: false, resizable: false, showZoomSlider: false,
        showScrollbars: true, hiddenSheetMode: 'skip', enableZoom: true,
        zoomMin: 0.1, zoomMax: 3, background: 'var(--desk)', refitOnResize: s.format === 'xlsx',
        paddingLeft: s.format === 'xlsx' ? 16 : 12, paddingRight: s.format === 'xlsx' ? 16 : 12,
        selectionColor: 'var(--accent)',
        findHighlightColors: { match: 'var(--find-match)', active: 'var(--find-active)' },
        onVisiblePageChange: (index, count) => position(s, index, count),
        onVisibleSlideChange: (index, count) => position(s, index, count),
        onSheetChange: (index, count) => position(s, index, count),
        onScaleChange: (scale) => {
          detailChanged(s);
          if (live(s) && s.announced) send({ type: 'scale', scale });
        },
        onError: (error) => fail(s, error)
      };
      const viewer = new engine[modules[s.format][1]](root, options);
      if (!live(s)) {viewer.destroy();return;}
      s.viewer = viewer;
      if (s.format !== 'xlsx') scrollHost(s)?.setAttribute('data-office-scroll-host', '');
      insets({ top: topInset, bottom: bottomInset });
      observeCommits(s);
      observeGlassFrames(s);

      {
        await viewer.load(bytes);
      }
      if (!live(s)) {viewer.destroy();return;}
      if (s.format !== 'xlsx') {

        {await viewer.waitUntilLayoutComplete();}
      }
      if (!live(s)) {viewer.destroy();return;}
      if (s.format === 'xlsx') {
        const tabs = [...root.querySelectorAll('.xlsx-tab-strip > div > button')];
        if (tabs.length !== viewer.sheetCount) throw { officeCode: 3 };
        s.sheetMap = tabs.flatMap((tab, index) => tab.style.display === 'none' ? [] : [index]);
        if (s.sheetMap.length !== viewer.visibleSheetCount) throw { officeCode: 3 };
        s.visibleSheets = new Set(s.sheetMap);
        s.names = s.sheetMap.map((index) => viewer.sheetNames[index]);
      }
      s.count = s.format === 'xlsx' ? s.sheetMap.length : s.format === 'docx' ? viewer.pageCount : viewer.slideCount;
      if (!s.count) throw { officeCode: 1 };
      initializeWidthFit(s);
      s.ready = true;
      s.resizeObserver = new ResizeObserver(() => {
        if (!live(s)) return;
        stopMotion();
        resizeWidthFit(s);
        detailChanged(s);
      });
      s.resizeObserver.observe(root);
      await navigate(s, Number.isFinite(command.index) ? command.index : 0, false);
      if (!live(s)) return;
      await waitForInitialPaint(s);
      if (!live(s)) return;
      s.initialPaintComplete = true;
      await applyReading(readingPalette);
      if (live(s)) announceReadingReady(s);
    } catch (error) {fail(s, error);}
  }

  function searchState(s, pending = false) {
    s.searchPending = pending;
    if (live(s)) send({ type: 'search', revision: s.queryRevision ?? 0,
      index: s.matchIndex, count: s.matches, truncated: s.truncated, pending });
  }

  function cancelSearch(s, truncated) {
    if (s.findMotion === motionGeneration) stopViewportMotion();
    const request = ++s.searchGeneration;
    clearTimeout(s.searchTimer);
    clearTimeout(s.searchDeadline);
    s.searchDeadline = null;
    s.pendingSearch = null;
    s.pendingFind = null;
    s.matches = 0;
    s.matchIndex = -1;
    s.locations = null;
    s.truncated = truncated;
    try {s.viewer.clearFind();} catch (_) {s.truncated = true;}
    return request;
  }

  async function drainSearch(s) {
    if (s.searchRunning) return;
    s.searchRunning = true;
    let yielded = performance.now();
    try {
      while (live(s)) {
        const work = s.pendingSearch || s.pendingFind;
        if (!work) break;
        if (s.pendingSearch) s.pendingSearch = null;else
        {
          const forward = work.steps > 0;
          work.steps += forward ? -1 : 1;
          if (!work.steps) s.pendingFind = null;
          work.forward = forward;
        }
        if (work.request !== s.searchGeneration) continue;
        try {
          if (work.operation) await work.operation();else
          await find(s, work.forward, work.request);
        } catch (_) {
          if (!live(s) || work.request !== s.searchGeneration) continue;
          cancelSearch(s, true);
          searchState(s);
        }
        if (performance.now() - yielded > 8) {
          await new Promise((resolve) => setTimeout(resolve, 0));
          yielded = performance.now();
        }
      }
    } finally {s.searchRunning = false;}
  }

  async function find(s, forward, request, origin) {
    if (s.navigationRunning) await s.navigationRunning;
    if (!live(s) || request !== s.searchGeneration) return;
    const host = scrollHost(s);
    if (nativePaused || motionSuspended || document.hidden || points.size || origin && (
    origin.motion !== motionGeneration || origin.navigation !== s.navigationGeneration ||
    origin.left !== host.scrollLeft || origin.top !== host.scrollTop ||
    origin.scale !== s.viewer.getScale())) {
      searchState(s);
      return;
    }
    const work = moveToMatch(s, forward, request);
    s.findNavigation = work;
    try {await work;} finally {if (s.findNavigation === work) s.findNavigation = null;}
  }

  async function revealPageMatch(s, unit, request, motion, navigation) {
    const host = scrollHost(s),left = host.scrollLeft,top = host.scrollTop,scale = s.viewer.getScale();
    let timer, target;
    try {
      target = await Promise.race([s.viewer.getActiveFindTarget(), new Promise((resolve) => {
        timer = setTimeout(() => resolve(null), 1000);
      })]);
    } finally {clearTimeout(timer);}
    if (!live(s) || request !== s.searchGeneration || motion !== motionGeneration ||
    navigation !== s.navigationGeneration || nativePaused || motionSuspended || document.hidden ||
    points.size || host.scrollLeft !== left || host.scrollTop !== top || s.viewer.getScale() !== scale) return;
    const geometry = s.viewer.getZoomGeometry();
    if (!Number.isInteger(unit) || unit < 0 || unit >= geometry.heights.length) return;
    const pageWidth = geometry.widths[unit],pageHeight = geometry.heights[unit];
    const lower = Math.min(topInset, host.clientHeight),upper = Math.max(lower, host.clientHeight - bottomInset);
    if (![pageWidth, pageHeight].every(Number.isFinite) || pageWidth <= 0 || pageHeight <= 0 ||
    host.clientWidth <= 0 || upper <= lower) return;
    if (!target || target.unit !== unit || ![target.left, target.top, target.right, target.bottom].every(Number.isFinite) ||
    target.right <= target.left || target.bottom <= target.top ||
    target.right <= 0 || target.bottom <= 0 || target.left >= pageWidth || target.top >= pageHeight) {
      const options = { behavior: reducedMotion() ? 'auto' : 'smooth' };
      if (s.format === 'docx') s.viewer.scrollToPage(unit, options);else
      s.viewer.scrollToSlide(unit, options);
      return;
    }
    target = { left: Math.max(0, target.left), top: Math.max(0, target.top),
      right: Math.min(pageWidth, target.right), bottom: Math.min(pageHeight, target.bottom) };
    const x = Math.max(geometry.padding, (host.clientWidth - geometry.widths[unit]) / 2);
    let y = geometry.leading + unit * geometry.gap;
    for (let index = 0; index < unit; index++) y += geometry.heights[index];
    const shift = (start, end, minimum, maximum) => end - start > maximum - minimum || start < minimum ?
    start - minimum : end > maximum ? end - maximum : 0;
    const dx = shift(x + target.left, x + target.right, left, left + host.clientWidth);
    const dy = shift(y + target.top, y + target.bottom, top + lower, top + upper);
    if (dx || dy) host.scrollTo({ left: Math.max(0, left + dx), top: Math.max(0, top + dy),
      behavior: reducedMotion() ? 'auto' : 'smooth' });
  }

  async function moveToMatch(s, forward, request) {

    {
      if (!s.matches) return;
      stopMotion();
      s.desiredIndex = null;
      ++s.navigationGeneration;
      const motion = motionGeneration,navigation = s.navigationGeneration;
      s.findMotion = motion;
      const host = scrollHost(s),before = { left: host.scrollLeft, top: host.scrollTop },sheet = s.index;
      const scale = s.viewer.getScale();
      let sheetGeneration;
      const current = () => live(s) && request === s.searchGeneration && motion === motionGeneration &&
      navigation === s.navigationGeneration && !nativePaused && !motionSuspended && !document.hidden &&
      !points.size && s.viewer.getScale() === scale && (
      sheetGeneration === undefined || s.viewer.getSheetGeneration() === sheetGeneration);
      let index = (s.matchIndex + (forward ? 1 : s.matchIndex < 0 ? 0 : -1) + s.matches) % s.matches;
      const pageReveal = !s.locations && s.format !== 'xlsx' && typeof s.viewer.getActiveFindTarget === 'function';
      let location;
      if (s.locations) {
        location = s.locations[index];
        if (s.format === 'xlsx') {
          const pending = location.sheet !== s.viewer.sheetIndex ? s.viewer.goToSheet(location.sheet) : null;
          sheetGeneration = s.viewer.getSheetGeneration?.();
          if (pending) await pending;
          if (!current() || s.viewer.sheetIndex !== location.sheet) {
            if (live(s) && request === s.searchGeneration) searchState(s);
            return;
          }
          await s.viewer.scrollToCell(location.ref, { align: 'center', isCurrent: current });
        } else if (s.format === 'docx') s.viewer.scrollToPage(location.page);else
        s.viewer.scrollToSlide(location.slide);
      } else {
        const options = pageReveal ? { reveal: false } : s.format === 'xlsx' ? { isCurrent: current } : undefined;
        const match = await (forward ? s.viewer.findNext(options) : s.viewer.findPrev(options));
        location = match?.location;
        if (Number.isInteger(match?.matchIndex) && match.matchIndex >= 0 && match.matchIndex < s.matches) index = match.matchIndex;
        if (pageReveal && location) await revealPageMatch(s, s.format === 'docx' ? location.page : location.slide,
        request, motion, navigation);
      }
      if (!live(s) || request !== s.searchGeneration) {
        if (live(s)) s.viewer.clearFind();
        return;
      }
      if (s.format === 'xlsx' && (!location || !current())) {
        searchState(s);
        return;
      }
      if (navigation === s.navigationGeneration && location && s.format !== 'xlsx') {
        selectPage(s, s.format === 'docx' ? location.page : location.slide);
      }
      if (!pageReveal && motion === motionGeneration && navigation === s.navigationGeneration && (s.format !== 'xlsx' || sheet === s.index) && !reducedMotion()) {
        const destination = { left: host.scrollLeft, top: host.scrollTop, behavior: 'smooth' };
        host.scrollTo({ ...before, behavior: 'auto' });
        host.scrollTo(destination);
      }
      s.matchIndex = index;
      searchState(s);
    }

  }

  function query(s, value) {
    const request = cancelSearch(s, typeof value === 'string' && value.length > 512);
    value = typeof value === 'string' ? value.slice(0, 512) : '';
    searchState(s, !!value);
    if (!value) return;
    const host = scrollHost(s);
    const origin = { motion: motionGeneration, navigation: s.navigationGeneration,
      left: host.scrollLeft, top: host.scrollTop, scale: s.viewer.getScale() };
    const queryTruncated = s.truncated;
    const deadline = setTimeout(() => {
      if (!live(s) || request !== s.searchGeneration) return;
      cancelSearch(s, true);
      searchState(s);
    }, 30000);
    s.searchDeadline = deadline;
    const operation = async () => {
      try {
        let matches;

        {matches = await s.viewer.findText(value, { caseSensitive: false });}

        if (!live(s) || request !== s.searchGeneration) {
          if (live(s)) s.viewer.clearFind();
          return;
        }
        if (matches.length > 1001 || s.sheetMap && matches.some((match) =>
        !s.visibleSheets.has(match.location.sheet))) throw Error('Office search result contract');
        s.truncated = queryTruncated || matches.length > 1000;
        s.matches = Math.min(1000, matches.length);
        if (s.matches) await find(s, true, request, origin);else
        searchState(s);
      } finally {
        clearTimeout(deadline);
        if (s.searchDeadline === deadline) s.searchDeadline = null;
      }
    };
    s.searchTimer = setTimeout(() => {
      if (!live(s) || request !== s.searchGeneration) return;
      s.pendingSearch = { request, operation };
      if (!s.searchRunning) void drainSearch(s);
    }, 0);
  }

  async function command(command) {
    if (!command || typeof command !== 'object') return;
    const kind = command.type || command.command;
    if (kind === 'frameAck') {
      const s = session;
      if (!s || command.generation !== s.generation || command.frameId !== s.glassId) return;
      s.glassAwaiting = false;
      const active = (root.getAnimations?.({ subtree: true }) || []).some((animation) =>
      animation.playState === 'running' && animation.effect?.target?.getClientRects().length > 0);
      if (s.glassDirty || active || motionFrame) queueGlassFrame(s);
      return;
    }
    if (kind === 'open') {await open(command);return;}
    if (kind === 'dispose') {dispose();return;}
    if (kind === 'pause') {nativePaused = true;openingDeadline(session);pause();return;}
    if (kind === 'resume') {
      nativeMotionEnabled = command.motionEnabled !== false;
      nativePaused = false;
      motionSuspended = document.hidden;
      openingDeadline(session);
      motionPreferenceChanged();
      resumeDetail();
      queueGlassFrame(session);
      return;
    }
    if (kind === 'colors') return colors(command);
    if (kind === 'insets') {
      insets(command);
      return;
    }
    if (kind === 'protection') return;
    const s = session;
    if (!s?.ready) return;
    try {
      switch (kind) {
        case 'goto':await navigate(s, command.index);break;
        case 'previous':await navigate(s, (s.desiredIndex ?? s.index) - 1);break;
        case 'next':await navigate(s, (s.desiredIndex ?? s.index) + 1);break;
        case 'zoomIn':stepZoom(s, 1);break;
        case 'zoomOut':stepZoom(s, -1);break;
        case 'reset':animateZoom(s, fitScale(s), undefined, true);break;
        case 'query':{
            const revision = command.revision ?? 0;
            if (!Number.isInteger(revision) || revision < 0 || revision > 2147483647) break;
            stopMotion();
            releasePageSelection(s);
            s.queryRevision = revision;
            query(s, command.query);
            break;
          }
        case 'find':{
            if ((command.revision ?? 0) !== (s.queryRevision ?? 0)) break;
            if (s.searchPending || !s.matches) break;
            stopMotion();
            releasePageSelection(s);
            const steps = (s.pendingFind?.steps || 0) + (command.forward !== false ? 1 : -1);
            s.pendingFind = steps ? { request: s.searchGeneration, steps: Math.max(-1000, Math.min(1000, steps)) } : null;
            if (!s.searchRunning) void drainSearch(s);
            break;
          }
      }
    } catch (error) {fail(s, error);}
  }

  function scrollHost(s) {
    return s.format === 'xlsx' ? root.querySelector('[data-xlsx-viewport-input]') : root.firstElementChild?.firstElementChild;
  }

  function reducedMotion() {return !nativeMotionEnabled || motionPreference.matches;}

  function settleReducedAnimations() {
    if (reducedMotion()) {
      finishReading(session);
      for (const animation of root.getAnimations?.({ subtree: true }) || []) animation.cancel();
    }
  }

  function motionPreferenceChanged() {
    if (!reducedMotion()) return;
    const s = session,target = s?.desiredIndex ?? s?.navigation?.target;
    if (s?.ready && target !== undefined && target !== null && !nativePaused && !motionSuspended && !document.hidden) {
      void navigate(s, target, false).catch((error) => fail(s, error));
    } else stopViewportMotion();
    settleReducedAnimations();
  }

  motionPreference.addEventListener?.('change', motionPreferenceChanged);

  function fitScale(s) {
    return s.format === 'xlsx' ? 1 : s.fitScale * Math.max(1, scrollHost(s).clientWidth - 24) / s.fitWidth;
  }

  function initializeWidthFit(s) {
    if (s.format === 'xlsx') return;
    s.viewer.fitWidth();
    const host = scrollHost(s);
    host.scrollLeft = 0;
    s.fitScale = s.viewer.getScale();
    s.fitWidth = Math.max(1, host.clientWidth - 24);
    s.layoutWidth = host.clientWidth;
    s.widthFit = true;
  }

  function resizeWidthFit(s) {
    if (s.format === 'xlsx') return;
    const host = scrollHost(s);
    if (!host || host.clientWidth <= 24 || host.clientWidth === s.layoutWidth) return;
    s.layoutWidth = host.clientWidth;
    if (s.widthFit) {
      s.viewer.setScale(fitScale(s));
      host.scrollLeft = 0;
    }
  }

  function stepZoom(s, direction) {
    if (s.pinch) {
      cancelAnimationFrame(pinchFrame);
      pinchFrame = 0;
      previewPinch(s);
    }
    const queued = s.pinch?.owner === 'animation' && zoomDirection === direction && zoomTarget !== null;
    const base = queued ? zoomTarget : s.pinch?.target ?? s.viewer.getScale();
    animateZoom(s, direction > 0 ? base * 1.25 : base / 1.25);
    zoomDirection = direction;
  }

  function zoomAt(s, scale, center) {
    const host = scrollHost(s);
    if (!host || !live(s)) return;
    scale = Math.max(Math.min(0.1, fitScale(s)), Math.min(3, scale));
    const rect = host.getBoundingClientRect();
    s.viewer.setScaleAt(scale, center.x - rect.left, center.y - rect.top);
  }

  function beginPinch(s, state, owner = 'touch') {
    if (!(state.distance > 0)) return;
    detailInteraction(s, true);
    if (s.format !== 'xlsx') s.viewer.beginInteraction();
    const host = scrollHost(s),rect = host.getBoundingClientRect();
    const geometry = s.format === 'xlsx' ? null : s.viewer.getZoomGeometry();
    const slots = geometry ? [...host.querySelectorAll('[data-office-unit]')].map((element) => {
      const unit = Number(element.getAttribute('data-office-unit'));
      return { element, unit, left: parseFloat(element.style.left), top: parseFloat(element.style.top),
        willChange: element.style.getPropertyValue('will-change'), willChangePriority: element.style.getPropertyPriority('will-change') };
    }) : [];
    const prefix = [0];
    if (geometry) for (const height of geometry.heights) prefix.push(prefix.at(-1) + height);
    if (geometry) geometry.maxWidth = geometry.widths.reduce((maximum, width) => Math.max(maximum, width), 0);
    const x = state.center.x - rect.left,y = state.center.y - rect.top;
    let unit = 0;
    if (geometry) {
      while (unit + 1 < geometry.heights.length && geometry.leading + prefix[unit + 1] + (unit + 1) * geometry.gap <= host.scrollTop + y) unit++;
    }
    const pageTop = geometry ? geometry.leading + prefix[unit] + unit * geometry.gap : 0;
    const pageLeft = geometry ? Math.max(geometry.padding, (host.clientWidth - geometry.widths[unit]) / 2) : 0;
    const anchorY = host.scrollTop + y - pageTop;
    const fraction = geometry ? Math.max(0, Math.min(1, anchorY / geometry.heights[unit])) : 0;
    s.pinch = { owner, rect, scale: s.viewer.getScale(), distance: state.distance, center: state.center, latest: state,
      target: s.viewer.getScale(), ratio: 1, geometry, slots, prefix, unit,
      fraction, anchorOffset: geometry ? anchorY - fraction * geometry.heights[unit] : 0,
      xFraction: geometry ? (host.scrollLeft + x - pageLeft) / geometry.widths[unit] : 0,
      scrollX: host.scrollLeft, scrollY: host.scrollTop,
      sheet: geometry ? null : s.viewer.getZoomPreview() };
    if (s.format === 'docx') for (const slot of slots) slot.element.style.setProperty('will-change', 'transform');else
    if (s.format === 'pptx') for (const slot of slots) {
      const hints = slot.willChange && slot.willChange !== 'auto' ? slot.willChange.split(',').map((value) => value.trim()) : [];
      if (!hints.includes('transform')) hints.push('transform');
      slot.element.style.setProperty('will-change', hints.join(', '), slot.willChangePriority);
    }
  }

  function previewPinch(s) {

    {
      const pinch = s?.pinch;
      if (!pinch || !live(s)) return;
      const latest = pinch.latest;
      const host = scrollHost(s),viewportWidth = host.clientWidth,viewportHeight = host.clientHeight;
      if (pinch.lastPreview === latest && pinch.lastWidth === viewportWidth && pinch.lastHeight === viewportHeight &&
      pinch.lastTopInset === topInset && pinch.lastBottomInset === bottomInset) return;
      pinch.lastPreview = latest;
      pinch.lastWidth = viewportWidth;
      pinch.lastHeight = viewportHeight;
      pinch.lastTopInset = topInset;
      pinch.lastBottomInset = bottomInset;
      pinch.target = Math.max(Math.min(.1, fitScale(s)), Math.min(3, pinch.scale * latest.distance / pinch.distance));
      pinch.ratio = pinch.target / pinch.scale;
      const ratio = pinch.ratio,g = pinch.geometry;
      const x = latest.center.x - pinch.rect.left,y = latest.center.y - pinch.rect.top;
      if (g) {
        const height = g.leading + pinch.prefix.at(-1) * ratio + (g.heights.length - 1) * g.gap + g.trailing;
        const width = g.maxWidth * ratio + g.padding * 2;
        pinch.finalY = Math.max(0, Math.min(height + bottomInset - viewportHeight,
        g.leading + pinch.prefix[pinch.unit] * ratio + pinch.unit * g.gap + pinch.fraction * g.heights[pinch.unit] * ratio + pinch.anchorOffset - y));
        const focalLeft = Math.max(g.padding, (viewportWidth - g.widths[pinch.unit] * ratio) / 2);
        pinch.finalX = Math.max(0, Math.min(width - viewportWidth, focalLeft + pinch.xFraction * g.widths[pinch.unit] * ratio - x));
        for (const slot of pinch.slots) {
          const left = Math.max(g.padding, (viewportWidth - g.widths[slot.unit] * ratio) / 2);
          const top = g.leading + pinch.prefix[slot.unit] * ratio + slot.unit * g.gap;
          slot.element.style.transformOrigin = '0 0';
          slot.element.style.transform = `matrix(${ratio},0,0,${ratio},${left - pinch.finalX + pinch.scrollX - slot.left},${top - pinch.finalY + pinch.scrollY - slot.top})`;
        }
      } else {
        const initialX = pinch.sheet.rtl ? viewportWidth - (pinch.center.x - pinch.rect.left) : pinch.center.x - pinch.rect.left;
        const currentX = pinch.sheet.rtl ? viewportWidth - x : x;
        pinch.finalX = Math.max(0, Math.min(pinch.sheet.maxX(ratio), (pinch.sheet.x + initialX) * ratio - currentX));
        const desired = (pinch.sheet.y + pinch.center.y - pinch.rect.top) * ratio - (latest.center.y - pinch.sheet.rootTop);
        pinch.finalY = Math.max(-topInset, Math.min(pinch.sheet.maxY(ratio), desired));
        s.viewer.previewZoom(pinch.sheet, ratio, pinch.finalX, pinch.finalY, true);
      }
    }

  }

  function finishPinch(s) {
    const pinch = s?.pinch;
    if (!pinch) return;
    cancelAnimationFrame(pinchFrame);
    pinchFrame = 0;
    previewPinch(s);
    s.pinch = null;
    if (pinch.owner === 'animation') {
      ++motionGeneration;
      cancelAnimationFrame(motionFrame);
      motionFrame = 0;
      zoomTarget = null;
      zoomDirection = 0;
      fitting = false;
    }
    for (const slot of pinch.slots) {
      slot.element.style.transform = '';
      slot.element.style.transformOrigin = '';
      if (s.format === 'docx' || s.format === 'pptx') {
        if (slot.willChange) slot.element.style.setProperty('will-change', slot.willChange, slot.willChangePriority);else
        slot.element.style.removeProperty('will-change');
      }
    }
    if (!live(s)) return;
    zoomAt(s, pinch.target, pinch.center);
    const host = scrollHost(s);
    if (pinch.geometry) {
      host.scrollLeft = pinch.finalX;
      host.scrollTop = pinch.finalY;
      s.viewer.endInteraction();
    } else
    s.viewer.finishZoomPreview(pinch.finalX, pinch.finalY);
    s.coordinateRevision = (s.coordinateRevision || 0) + 1;
    detailInteraction(s, false);
    queueGlassFrame(s);
  }

  function animateZoom(s, target, center, fit = false) {
    const previous = center === undefined && s.pinch?.owner === 'animation' ? s.pinch : null;
    if (previous) {
      cancelReveals();
      ++motionGeneration;
      cancelAnimationFrame(motionFrame);
      motionFrame = 0;
      zoomTarget = null;
      zoomDirection = 0;
      fitting = false;
    } else stopMotion();
    s.widthFit = fit;
    fitting = fit;
    const host = scrollHost(s);
    if (!host) return;
    const rect = root.getBoundingClientRect();
    center ||= previous?.center || { x: rect.left + rect.width / 2,
      y: rect.top + Math.min(rect.height, topInset) + Math.max(0, rect.height - topInset - bottomInset) / 2 };
    const from = previous?.target ?? s.viewer.getScale();
    zoomTarget = Math.max(Math.min(0.1, fitScale(s)), Math.min(3, target));
    const to = zoomTarget;
    if (reducedMotion()) {
      if (previous) {
        previous.latest = { distance: to / previous.scale, center };
        previewPinch(s);
      } else zoomAt(s, to, center);
      stopMotion();
      return;
    }
    if (!previous) beginPinch(s, { distance: 1, center }, 'animation');
    const preview = s.pinch,motion = motionGeneration;
    const start = performance.now();
    let frameId = 0;
    const frame = (now) => {
      if (motion !== motionGeneration || motionFrame !== frameId) return;
      motionFrame = 0;
      if (!live(s) || document.hidden) {stopMotion();return;}
      try {
        const t = Math.max(0, Math.min(1, (now - start) / 220));
        if (s.pinch !== preview || preview.owner !== 'animation') {
          zoomTarget = null;
          zoomDirection = 0;
          fitting = false;
          return;
        }
        preview.latest = { distance: (from + (to - from) * (1 - Math.pow(1 - t, 3))) / preview.scale, center };
        previewPinch(s);
        if (t < 1) motionFrame = frameId = requestAnimationFrame(frame);else
        stopMotion();
      } catch (error) {fail(s, error);}
    };
    motionFrame = frameId = requestAnimationFrame(frame);
  }

  function fling(s, host, vx, vy) {
    stopMotion();
    detailInteraction(s, true);
    vx = Math.max(-3, Math.min(3, vx));
    vy = Math.max(-3, Math.min(3, vy));
    const start = performance.now();
    const motion = motionGeneration;
    let previous = start;
    let frameId = 0;
    const frame = (now) => {
      if (motion !== motionGeneration || motionFrame !== frameId) return;
      motionFrame = 0;
      if (!live(s) || document.hidden || now - start > 1200) {stopMotion();return;}
      const dt = Math.min(32, now - previous);
      previous = now;
      const x = host.scrollLeft,y = host.scrollTop;
      host.scrollTo({ left: x + vx * dt, top: y + vy * dt, behavior: 'instant' });
      const decay = Math.exp(-dt / 220);
      vx = host.scrollLeft === x ? 0 : vx * decay;
      vy = host.scrollTop === y ? 0 : vy * decay;
      if (Math.hypot(vx, vy) < 0.025) {stopMotion();return;}
      motionFrame = frameId = requestAnimationFrame(frame);
    };
    motionFrame = frameId = requestAnimationFrame(frame);
  }

  function snapshot(s) {
    const values = [...points.values()];
    const center = values.reduce((p, q) => ({ x: p.x + q.x / values.length, y: p.y + q.y / values.length }), { x: 0, y: 0 });
    const distance = values.length > 1 ? Math.hypot(values[0].x - values[1].x, values[0].y - values[1].y) : 0;
    return { center, distance, scale: s.viewer.getScale(), host: scrollHost(s), time: performance.now() };
  }
  root.addEventListener('pointerdown', (event) => {
    const s = session;
    if (!s?.ready || event.pointerType === 'mouse' && event.button !== 0) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (points.size >= 2) return;
    stopMotion();
    s.desiredIndex = null;
    ++s.navigationGeneration;
    points.set(event.pointerId, { x: event.clientX, y: event.clientY });
    detailInteraction(s, true);
    root.setPointerCapture(event.pointerId);
    gesture = { ...snapshot(s), start: { x: event.clientX, y: event.clientY }, vx: 0, vy: 0,
      moved: false, multi: points.size > 1, pointerType: event.pointerType };
    gesture.pinchScale = gesture.scale;
    gesture.pinchDistance = gesture.distance;
    gesture.started = gesture.time;
    if (gesture.multi) {lastTap = null;beginPinch(s, gesture);}
  }, true);
  root.addEventListener('pointermove', (event) => {
    const s = session;
    if (!s?.ready || !points.has(event.pointerId) || !gesture) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    points.set(event.pointerId, { x: event.clientX, y: event.clientY });
    const next = snapshot(s);
    try {
      if (s.pinch?.owner === 'animation') {
        stopMotion();
        s.widthFit = false;
        Object.assign(gesture, snapshot(s), { vx: 0, vy: 0, moved: true });
        releasePageSelection(s);
        if (next.distance) beginPinch(s, next);
        return;
      }
      if (points.size > 1) {
        if (!(next.distance > 0)) return;
        if (!s.pinch) beginPinch(s, next);
        s.widthFit = false;
        s.pinch.latest = next;
        if (!pinchFrame) {
          const preview = s.pinch;
          const frameId = requestAnimationFrame(() => {
            if (pinchFrame !== frameId) return;
            pinchFrame = 0;
            if (s.pinch === preview) previewPinch(s);
          });
          pinchFrame = frameId;
        }
        gesture.moved = true;
        releasePageSelection(s);
        return;
      }
      const dx = gesture.center.x - next.center.x,dy = gesture.center.y - next.center.y;
      if (Math.hypot(next.center.x - gesture.start.x, next.center.y - gesture.start.y) > 8) releasePageSelection(s);
      if (gesture.host) {
        const x = gesture.host.scrollLeft,y = gesture.host.scrollTop;
        gesture.host.scrollTo({ left: x + dx, top: y + dy, behavior: 'instant' });
      }
      const dt = Math.max(1, next.time - gesture.time);
      gesture.vx = dt > 100 ? dx / dt : gesture.vx * 0.3 + dx / dt * 0.7;
      gesture.vy = dt > 100 ? dy / dt : gesture.vy * 0.3 + dy / dt * 0.7;
      gesture.moved ||= Math.hypot(next.center.x - gesture.start.x, next.center.y - gesture.start.y) > 8;
      Object.assign(gesture, snapshot(s));
    } catch (error) {fail(s, error);}
  }, true);
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) root.addEventListener(type, (event) => {
    if (!points.delete(event.pointerId)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const s = session,end = gesture;
    if (type !== 'pointerup') stopMotion();else
    if (s?.pinch?.owner === 'touch') finishPinch(s);
    if (points.size && s?.ready) {
      gesture = { ...end, ...snapshot(s), multi: true, vx: 0, vy: 0 };
      gesture.pinchScale = gesture.scale;
      gesture.pinchDistance = gesture.distance;
      if (gesture.distance && !s.pinch) beginPinch(s, gesture);
      return;
    }
    gesture = null;
    detailInteraction(s, false);
    if (type !== 'pointerup' || !s?.ready || !end || end.multi) {lastTap = null;return;}
    const now = performance.now();
    if (!end.moved && now - end.started < 300) {
      if (lastTap && now - lastTap.time < 300 && Math.hypot(event.clientX - lastTap.x, event.clientY - lastTap.y) < 24) {
        const fit = fitScale(s);
        const reset = s.viewer.getScale() > fit * 1.1;
        animateZoom(s, reset ? fit : Math.min(3, fit * 2),
        { x: event.clientX, y: event.clientY }, reset);
        lastTap = null;
      } else lastTap = { time: now, x: event.clientX, y: event.clientY };
    } else {
      lastTap = null;
      if (end.pointerType === 'touch' && now - end.time < 100 && end.host) fling(s, end.host, end.vx, end.vy);
    }
  }, true);
  root.addEventListener('wheel', (event) => {
    if (!zoomEvents.has(event)) {
      stopMotion();
      if (session) {
        if (event.ctrlKey || event.metaKey) session.widthFit = false;
        session.desiredIndex = null;
        ++session.navigationGeneration;
        if (!event.ctrlKey && !event.metaKey && (event.deltaX || event.deltaY)) releasePageSelection(session);
      }
    }
  }, { capture: true, passive: false });
  document.addEventListener('keydown', () => {
    stopMotion();
    if (session) releasePageSelection(session);
  }, true);
  for (const type of ['contextmenu', 'copy', 'cut', 'paste', 'selectstart', 'dragstart', 'click', 'dblclick', 'keydown']) {
    document.addEventListener(type, (event) => {event.preventDefault();event.stopImmediatePropagation();}, true);
  }
  window.addEventListener('pagehide', dispose);
  window.addEventListener('blur', () => {
    if (nativePaused || document.hidden) pause();
  });
  window.addEventListener('focus', () => {
    motionSuspended = nativePaused || document.hidden;
    resumeDetail();
    queueGlassFrame(session);
  });
  document.addEventListener('visibilitychange', () => {
    openingDeadline(session);
    if (document.hidden) pause();else
    {
      motionSuspended = nativePaused;
      resumeDetail();
      queueGlassFrame(session);
    }
  });
  window.OfficeViewer = Object.freeze({ command, stopScrolling: () => stopMotion() });
  globalThis.OfficePaperHost?.attach(window.OfficeViewer);
  send({ type: 'bridgeReady' });
})();
