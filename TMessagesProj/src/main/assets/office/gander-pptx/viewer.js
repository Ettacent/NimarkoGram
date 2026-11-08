/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */
(() => {
  'use strict';











  const root = document.documentElement,result = document.getElementById('result');
  const status = document.getElementById('vw-status');
  const color = (value) => '#' + (Number(value) >>> 0).toString(16).padStart(8, '0').slice(-6);
  const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, Number(n) || 0));
  let started = false,active = false,ready = false,finished = false,paused = false,allowCopy = false;
  let generation = 0,abort = null,readyFrame = 0,frame = 0,frameId = 0,awaiting = 0,dirty = false;
  let slides = [],index = 0,initialIndex = 0,baseScale = 1,scale = 1;
  let motionEnabled = true;
  let top = 0,bottom = 0,layoutTop = -1,layoutBottom = -1;
  let revision = 0,query = '',queryClipped = false,appliedRevision = -1,appliedQuery = '',findSteps = 0;
  const urls = new Set();
  let paper = null;
  const live = () => active && !paused && !document.hidden;
  const cancelled = { officeCancelled: true };
  const guard = () => {if (!active) throw cancelled;};
  window.officePptxGuard = guard;
  function post(value) {if (!active && value.type !== 'bridgeReady') return;try {window.OfficeHost?.postMessage(JSON.stringify(value));} catch (_) {}}
  function accentInk(value) {const c = [16, 8, 0].map((s) => (value >>> s & 255) / 255).map((v) => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4);return c[0] * .2126 + c[1] * .7152 + c[2] * .0722 > .179 ? '#000000' : '#ffffff';}
  function colors(message) {
    pageMotionSettings(message);
    if (paper) void paper.colors(message);else commitColors(message);
  }
  function commitColors(message) {
    if (Number.isInteger(message.background)) root.style.setProperty('--vw-ground', color(message.background));
    if (Number.isInteger(message.foreground)) {root.style.setProperty('--vw-ink', color(message.foreground));root.style.setProperty('--office-ui-ink', color(message.foreground));}
    if (Number.isInteger(message.readingPaper)) root.style.setProperty('--office-paper', color(message.readingPaper));
    if (Number.isInteger(message.accent)) {
      root.style.setProperty('--vw-accent', color(message.accent));root.style.setProperty('--office-find', color(message.accent) + '66');
      root.style.setProperty('--office-find-active', color(message.accent));root.style.setProperty('--office-find-active-ink', accentInk(message.accent));
    }
    OfficePptxNight.colors(message);scheduleFrame();
  }
  function protection(value) {allowCopy = value === true;root.classList.toggle('office-protected', !allowCopy);if (!allowCopy) getSelection()?.removeAllRanges();}
  for (const type of ['copy', 'cut', 'paste', 'contextmenu', 'dragstart', 'selectstart']) document.addEventListener(type, (e) => {if (!allowCopy) e.preventDefault();}, true);
  document.addEventListener('selectionchange', () => {if (!allowCopy && getSelection()?.rangeCount) getSelection().removeAllRanges();});
  document.addEventListener('click', (e) => {if (e.target.closest?.('a,area')) {e.preventDefault();e.stopImmediatePropagation();}}, true);
  window.vwDisarmLinks = (parent) => parent.querySelectorAll('a,area').forEach((a) => {a.removeAttribute('href');a.removeAttribute('target');a.removeAttribute('download');});
  window.open = () => null;
  function insets(message = {}) {
    if (Number.isFinite(message.top)) top = clamp(message.top, 0, 4096);if (Number.isFinite(message.bottom)) bottom = clamp(message.bottom, 0, 4096);
    const vv = window.visualViewport,across = vv && vv.scale > 0 ? vv.width * vv.scale : 0;
    const perDp = across > 0 && innerWidth > 0 ? innerWidth / across : 1;
    const nextTop = Math.round(top * perDp * 64) / 64,nextBottom = Math.round(bottom * perDp * 64) / 64;
    vwTopDp = top;vwTopPx = nextTop;
    if (nextTop !== layoutTop) {layoutTop = nextTop;root.style.setProperty('--vw-top', nextTop + 'px');}
    if (nextBottom !== layoutBottom) {layoutBottom = nextBottom;root.style.setProperty('--office-bottom', nextBottom + 'px');}
    if (pageRequest) pageRequest.geometry = null;
    scheduleFrame();
  }
  window.vwPlaceTop = () => insets();

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
    if (pageRequest?.moving && (!motionEnabled || pageMotion.matches)) go(pageRequest.index, false);
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

    {
      const vv = window.visualViewport,y = vv?.pageTop ?? scrollY;
      const requested = requestedPage(),height = vv?.height || innerHeight;
      if (requested !== null) return requested;
      if (y <= 1) return 0;
      if (document.documentElement.scrollHeight > height + 1 && y + height >= document.documentElement.scrollHeight - 1) return slides.length - 1;
      const middle = y + height / 2;let low = 0,high = slides.length - 1,current = 0;
      while (low <= high) {const m = low + high >> 1;if (slides[m].getBoundingClientRect().top + scrollY <= middle) {current = m;low = m + 1;} else high = m - 1;}
      return current;
    }

  }
  function position() {
    if (!ready || !live()) return;
    index = currentPage();
    post({ type: 'position', count: slides.length, index, label: index + 1 + ' / ' + slides.length });
  }
  function scheduleFrame() {
    dirty = true;if (!ready || !live() || awaiting || frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;if (!live()) return;const vv = window.visualViewport,next = clamp((vv?.scale || baseScale) / baseScale, .1, 10);
      if (Math.abs(next - scale) > .001) {scale = next;post({ type: 'scale', scale });}
      position();dirty = false;awaiting = ++frameId;
      post({ type: 'frame', generation, frameId, coordinateRevision: 0, x: vv?.pageLeft ?? scrollX, y: vv?.pageTop ?? scrollY, width: vv?.width || innerWidth, scale });
    });
  }
  function go(next, smooth = true) {
    if (!ready || !live()) return;const at = Math.floor(clamp(next, 0, slides.length - 1));
    navigatePage(at, smooth, slides[at], () => slides[at]?.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' }));
    position();
  }
  function zoom(operation) {cancelPageNavigation();if (ready && live()) post({ type: 'nativeZoom', operation, factor: clamp(operation === 'reset' ? 1 / scale : operation === 'in' ? 1.25 : .8, .1, 10) });}
  addEventListener('scroll', scheduleFrame, { passive: true });
  window.visualViewport?.addEventListener('scroll', scheduleFrame, { passive: true });
  window.visualViewport?.addEventListener('resize', pageViewportResize, { passive: true });
  addEventListener('resize', () => {if (active) {insets();scheduleFrame();}});

  function resource(value) {
    if (/^#[\w:.-]+$/.test(value)) return true;
    if (/^blob:/.test(value)) return urls.has(value);
    if (!/^data:image\/(png|jpe?g|gif|bmp|webp|svg\+xml);base64,[a-z0-9+/=\s]+$/i.test(value)) return false;
    if (/^data:image\/svg\+xml;/i.test(value)) {
      try {OfficePptxCapabilities.safeSVG(new TextDecoder().decode(Uint8Array.from(atob(value.split(',')[1]), (c) => c.charCodeAt(0))));} catch (_) {return false;}
    }
    return true;
  }
  function safeCss(text) {
    if (/@import|@namespace|expression\s*\(|image-set\s*\(|cross-fade\s*\(|paint\s*\(|javascript\s*:|behavior\s*:|-moz-binding|\\/i.test(text)) throw { officeCode: 3, feature: 'unsafeGeneratedCss' };
    for (const match of text.matchAll(/url\(\s*(?:"([^"]*)"|'([^']*)'|([^)]*))\s*\)/gi)) if (!resource((match[1] ?? match[2] ?? match[3]).trim())) throw { officeCode: 3, feature: 'externalGeneratedResource' };
    return text;
  }
  window.officePptxSafeStyle = (text) => {guard();const el = document.createElement('style');el.textContent = safeCss(text);return el;};
  window.officePptxSafeNodes = (html) => {

    {
      guard();if ((window.vwLeftOut || []).length) throw { officeCode: 3, feature: 'rendererOmission' };
      const template = document.createElement('template');template.innerHTML = html;
      if (template.content.querySelector('script,iframe,object,embed,form,input,button,video,audio,foreignObject,link,meta,base,animate,animateTransform,set')) throw { officeCode: 3, feature: 'unsafeGeneratedMarkup' };
      for (const el of template.content.querySelectorAll('*')) {
        for (const a of [...el.attributes]) {
          if (/^on/i.test(a.name)) throw { officeCode: 3, feature: 'unsafeGeneratedMarkup' };
          if (/^(?:background|poster|data|longdesc|manifest|profile|lowsrc|dynsrc)$/i.test(a.localName)) throw { officeCode: 3, feature: 'unsupportedGeneratedResource' };
          if (a.localName === 'href' && el.localName.toLowerCase() !== 'a' && !resource(a.value)) throw { officeCode: 3, feature: 'externalGeneratedResource' };
          if (a.name === 'src' && !resource(a.value)) throw { officeCode: 3, feature: 'externalGeneratedResource' };
          if (a.name === 'style' || /^(?:fill|stroke|filter|clip-path|mask|marker(?:-(?:start|mid|end))?|cursor|color-profile)$/i.test(a.localName)) safeCss(a.value);
        }
        if (el.localName === 'style') safeCss(el.textContent);
        if (el.localName === 'a' || el.localName === 'area') {el.removeAttribute('href');el.removeAttributeNS('http://www.w3.org/1999/xlink', 'href');el.removeAttribute('target');}
      }
      const sanitized = DOMPurify.sanitize(template.innerHTML, { RETURN_DOM_FRAGMENT: true, USE_PROFILES: { html: true, svg: true, svgFilters: true },
        ADD_TAGS: ['sapn', 'style'], ADD_ATTR: (name) => /^_[a-z0-9-]+$/i.test(name),
        FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'video', 'audio', 'foreignObject', 'link', 'meta', 'base'],
        FORBID_ATTR: ['srcset', 'action', 'formaction', 'ping', 'srcdoc', 'xml:base'],
        ALLOWED_URI_REGEXP: /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|matrix|blob):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i });
      for (const svg of sanitized.querySelectorAll('svg.drawing')) {
        if (!svg.querySelector(':scope > path,:scope > rect,:scope > ellipse,:scope > circle,:scope > line,:scope > polyline,:scope > polygon,:scope > image'))
        throw { officeCode: 3, feature: 'rendererEmptyGeometry' };
        for (const node of svg.querySelectorAll('[d],[points],[x],[y],[width],[height],[rx],[ry]')) {
          for (const attr of node.attributes) {
            if (['d', 'points', 'x', 'y', 'width', 'height', 'rx', 'ry'].includes(attr.name) && /(?:NaN|Infinity|undefined)/.test(attr.value))
            throw { officeCode: 3, feature: 'rendererInvalidGeometry' };
          }
        }
      }
      const imageCount = (fragment) => fragment.querySelectorAll('img,image,[style*="data:image/"]').length;
      if (imageCount(sanitized) !== imageCount(template.content)) throw { officeCode: 3, feature: 'rendererSanitizedImage' };
      const beforeImages = [...template.content.querySelectorAll('img,image')],afterImages = [...sanitized.querySelectorAll('img,image')];
      const imageRef = (image) => image.getAttribute('src') || image.getAttribute('href') || image.getAttributeNS('http://www.w3.org/1999/xlink', 'href') || '';
      if (beforeImages.length !== afterImages.length || beforeImages.some((image, i) => imageRef(image).trim() !== imageRef(afterImages[i]).trim()))
      throw { officeCode: 3, feature: 'rendererSanitizedImage' };
      return sanitized;
    }

  };
  $.ajax = $.getScript = JSZipUtils.getBinaryContent = () => {throw { officeCode: 3, feature: 'dynamicResource' };};
  window.officePptxOmission = () => fail({ officeCode: 3, feature: 'rendererOmission' });
  window.vwError = () => {if (active) fail({ officeCode: 3, feature: 'rendererFailure' });};
  function fail(error) {
    if (!active || error?.officeCancelled) return;
    const code = Number.isInteger(error?.officeCode) ? clamp(error.officeCode, 1, 5) : 3;
    post({ type: 'error', code, renderer: 'gander-pptx', fallbackRequired: code === 3, featureCodes: [error?.feature || 'rendererFailure'] });
    dispose();status.className = 'vw-status vw-error';status.style.display = '';status.textContent = code === 3 ? 'This presentation needs the original reader.' : 'Could not open this presentation.';
  }
  function searchState(pending) {post({ type: 'search', revision, count: 0, index: -1, truncated: queryClipped, pending });}
  vwFind.setSink((message) => {
    if (!ready || !live() || appliedRevision !== revision || appliedQuery !== query) return;
    const a = /^(\d+) (\d+) ([01]) ([01])$/.exec(String(message));if (!a) return;
    post({ type: 'search', revision, count: +a[2], index: +a[1] - 1, pending: a[3] === '0', truncated: queryClipped || a[4] === '1' });scheduleFrame();
  });
  function applyQuery() {
    if (!ready || !live()) return;appliedRevision = -1;vwFind.setPaused(false);vwFind.command('q' + query);appliedQuery = query;appliedRevision = revision;
    if (findSteps) {vwFind.step(findSteps);findSteps = 0;}vwFind.report();
  }
  function setQuery(message) {
    if (!Number.isInteger(message.revision) || message.revision < revision || message.revision > 2147483647) return;
    cancelPageNavigation();
    revision = message.revision;findSteps = 0;const text = typeof message.query === 'string' ? message.query : '';let end = Math.min(text.length, 4096);
    if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1]) && /[\uDC00-\uDFFF]/.test(text[end])) end--;
    query = text.slice(0, end).replace(/\s+/g, ' ').trim();queryClipped = end < text.length;
    if (!ready || !live()) searchState(true);applyQuery();
  }
  function announce() {
    if (!finished || ready || !live() || !paper?.isReady()) return;cancelAnimationFrame(readyFrame);
    readyFrame = requestAnimationFrame(() => {readyFrame = requestAnimationFrame(() => {
        readyFrame = 0;if (!live() || !paper?.isReady()) return;ready = true;baseScale = visualViewport.scale || 1;scale = 1;
        root.classList.add('office-ready');status.style.display = 'none';
        post({ type: 'ready', count: slides.length, index: Math.floor(clamp(initialIndex, 0, slides.length - 1)), names: [] });post({ type: 'scale', scale });go(initialIndex, false);applyQuery();
      });});
  }
  async function decodeImages() {

    {
      const images = [...result.querySelectorAll('img')];
      for (const el of result.querySelectorAll('image')) {
        const src = el.getAttribute('href') || el.getAttribute('xlink:href');if (src) {const img = new Image();img.src = src;images.push(img);}
      }
      if (images.length > 512) throw { officeCode: 3, feature: 'candidateImageLimit' };
      await Promise.all(images.map((image) => image.decode().catch(() => {throw { officeCode: 3, feature: 'imageDecodeFailure' };})));
    }

  }
  async function validateImageParts(parts) {

    {
      let pixels = 0;
      for (const part of parts) {
        guard();const image = new Image(),url = URL.createObjectURL(new Blob([part.data], { type: part.type }));
        try {image.src = url;await image.decode();guard();const size = image.naturalWidth * image.naturalHeight;pixels += size;
          if (!size || size > 16 * 1024 * 1024 || pixels > 64 * 1024 * 1024) throw { officeCode: 3, feature: 'candidateImageLimit' };
          paper?.admit(part, image.naturalWidth, image.naturalHeight);
        } catch (error) {throw error?.officeCode || error?.officeCancelled ? error : { officeCode: 3, feature: 'imageDecodeFailure' };} finally
        {image.removeAttribute('src');URL.revokeObjectURL(url);urls.delete(url);}
      }
    }

  }
  async function open(message) {
    if (started) return;started = active = true;++generation;
    paper = OfficePptxPaper.create({ live: () => active, epoch: () => generation, paused: () => paused || document.hidden,
      createURL: (blob) => {const url = createURL(blob);urls.add(url);return url;},
      revokeURL: (url) => {urls.delete(url);URL.revokeObjectURL(url);},
      commitColors: (value) => commitColors(value),
      onSettled: () => {if (finished && !ready) announce();else scheduleFrame();} });
    let stage = 'host';
    try {
      if (message.format !== 'pptx' || message.nativeGestures !== true || !window.CSS?.highlights || typeof Highlight !== 'function' || !window.visualViewport) throw { officeCode: 3, feature: 'nativeHostRequired' };
      root.dataset.officeNativeGestures = 'true';initialIndex = Number.isInteger(message.index) ? message.index : 0;
      protection(message.allowCopy);colors(message);insets(message);abort = new AbortController();

      stage = 'fetch';const response = await fetch('/document', { signal: abort.signal, credentials: 'omit', redirect: 'error', cache: 'no-store' });guard();
      if (!response.ok) throw { officeCode: 1 };const bytes = await response.arrayBuffer();guard();
      if (bytes.byteLength > 24 * 1024 * 1024) throw { officeCode: 2 };

      stage = 'capability';const info = OfficePptxCapabilities.inspect(bytes, guard);guard();

      stage = 'imageAdmission';await validateImageParts(info.imageParts);delete info.imageParts;guard();
      post({ type: 'capabilities', renderer: 'gander-pptx', nativeGestures: true, notes: info.notes });

      stage = 'conversion';officeStartPptx(bytes, info.order);guard();slides = [...result.querySelectorAll('.slide')];
      if (slides.length !== info.order.length || result.querySelector('[data-vw-missing],.vw-missing')) throw { officeCode: 3, feature: 'rendererOmission' };

      stage = 'images';await decodeImages();guard();
      stage = 'paperVariants';await paper.attach(result);guard();

      stage = 'fonts';await document.fonts.ready;guard();

      stage = 'night';OfficePptxNight.prepare(result);insets();finished = true;announce();

    } catch (error) {if (active) fail(error?.officeCode ? error : { officeCode: 3, feature: stage + 'Failure' });}

  }
  function suspend() {cancelPageNavigation();paused = true;paper?.pause();vwFind.setPaused(true);cancelAnimationFrame(frame);frame = 0;awaiting = 0;dirty = true;}
  function resume() {paused = false;insets();void paper?.resume().then(() => {if (finished && !ready) announce();else {applyQuery();scheduleFrame();}});}
  function dispose() {
    cancelPageNavigation();active = false;ready = false;finished = false;paper?.close();paper = null;abort?.abort();cancelAnimationFrame(frame);cancelAnimationFrame(readyFrame);frame = readyFrame = 0;awaiting = 0;
    vwFind.setPaused(true);window.CSS?.highlights?.delete('vw-find');window.CSS?.highlights?.delete('vw-find-active');
    OfficePptxNight.dispose();urls.forEach((url) => URL.revokeObjectURL(url));urls.clear();
    result.replaceChildren();slides = [];root.classList.remove('office-ready');
  }
  function command(message) {
    if (!message || typeof message.type !== 'string') return;
    if (message.type === 'open') {void open(message);return;}
    if (['close', 'cancel', 'dispose'].includes(message.type)) {dispose();return;}if (!active) return;
    switch (message.type) {
      case 'colors':colors(message);break;
      case 'insets':insets(message);break;
      case 'protection':protection(message.allowCopy);break;
      case 'query':setQuery(message);break;
      case 'find':if (message.revision !== revision) break;cancelPageNavigation();findSteps = clamp(findSteps + (message.forward === false ? -1 : 1), -1000, 1000);if (ready && live() && appliedRevision === revision) {vwFind.step(findSteps);findSteps = 0;vwFind.report();}break;
      case 'goto':go(message.index);break;case 'previous':go(currentPage() - 1);break;case 'next':go(currentPage() + 1);break;
      case 'zoomIn':zoom('in');break;case 'zoomOut':zoom('out');break;case 'reset':zoom('reset');break;
      case 'nativeScale':if (ready && Number.isFinite(message.scale) && message.scale >= .1 && message.scale <= 10) {scale = message.scale;baseScale = (window.visualViewport?.scale || 1) / scale;post({ type: 'scale', scale });scheduleFrame();}break;
      case 'frameAck':if (message.generation === generation && message.frameId === awaiting) {awaiting = 0;if (dirty) scheduleFrame();}break;
      case 'pause':suspend();break;case 'resume':pageMotionSettings(message);resume();break;
    }
  }
  const createURL = URL.createObjectURL.bind(URL);URL.createObjectURL = (blob) => {const url = createURL(blob);if (active) urls.add(url);else URL.revokeObjectURL(url);return url;};
  addEventListener('pagehide', dispose);
  document.addEventListener('visibilitychange', () => {
    if (!active) return;
    if (document.hidden) {paper?.pause();cancelPageNavigation();vwFind.setPaused(true);cancelAnimationFrame(frame);frame = 0;awaiting = 0;dirty = true;} else
    void paper?.resume().then(() => {if (finished && !ready) announce();else {applyQuery();scheduleFrame();}});
  });
  protection(false);vwFind.setPaused(true);window.OfficeViewer = Object.freeze({ command, stopScrolling: () => {cancelPageNavigation();scheduleFrame();}, getNativeScale: () => live() && ready ? (window.visualViewport?.scale || 0) / baseScale : 0, getNativeZoomState: () => [live() && ready ? (window.visualViewport?.scale || 0) / baseScale : 0, window.visualViewport?.scale || 0, window.devicePixelRatio || 1] });post({ type: 'bridgeReady' });
})();
