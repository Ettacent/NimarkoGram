/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent. Gander (MIT); see the accompanying Gander license. */
(() => {
  'use strict';






  const props = ['color', 'background-color', 'border-top-color', 'border-right-color', 'border-bottom-color',
  'border-left-color', 'text-decoration-color', 'column-rule-color', 'fill', 'stroke', 'stop-color', 'flood-color'];
  const root = document.documentElement;
  let records = null,sheet = null,dark = false,preserveImages = true;
  let paper = '#242424',ink = '#eeeeee',imageRules = [],key = '';
  const rgba = (value) => {
    if (!value || !/^rgba?\(/.test(value)) return null;
    const a = value.match(/[\d.]+/g)?.map(Number);
    return a?.length >= 3 ? [a[0], a[1], a[2], a.length > 3 ? a[3] : 1] : null;
  };
  const rgb = (value) => [1, 3, 5].map((i) => parseInt(value.slice(i, i + 2), 16));
  const luminance = (c) => (.2126 * c[0] + .7152 * c[1] + .0722 * c[2]) / 255;
  const neutral = (c) => Math.max(...c.slice(0, 3)) - Math.min(...c.slice(0, 3)) < 12;
  const lightPaper = (c) => neutral(c) && Math.min(...c.slice(0, 3)) > 230;
  const css = (c) => 'rgba(' + c.join(',') + ')';
  function contrast(color, background) {
    const light = (c) => c.slice(0, 3).map((v) => v / 255).map((v) => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4).
    reduce((n, v, i) => n + v * [.2126, .7152, .0722][i], 0);
    const composed = color.slice(0, 3).map((v, i) => v * color[3] + background[i] * (1 - color[3]));
    const a = light(composed),b = light(background);
    return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
  }
  function transform(name, value, record) {
    if (record.preserved) return value;
    const color = rgba(value);
    if (!color || color[3] === 0) return null;
    if (name === 'background-color' || record.paperShape && name === 'fill') {
      return lightPaper(color) ? css([...rgb(paper), color[3]]) : value;
    }
    if (record.svg || /^(fill|stroke|stop-color|flood-color)$/.test(name)) return value;
    const target = rgb(ink),ground = rgb(paper);
    if (contrast(color, ground) >= 4.5) return value;
    if (Math.max(...color.slice(0, 3)) - Math.min(...color.slice(0, 3)) < 40) return css([...target, color[3]]);
    for (let step = 1; step <= 20; step++) {
      const mixed = color.slice(0, 3).map((v, i) => Math.round(v + (target[i] - v) * step / 20));
      if (contrast([...mixed, color[3]], ground) >= 4.5) return css([...mixed, color[3]]);
    }
    return css([...target, color[3]]);
  }
  function localDesign(slide) {
    const surfaces = [],paperShapes = new Set(),protectedCss = new Set();
    const contains = (outer, inner) => {
      const area = inner.width * inner.height;
      const overlap = Math.max(0, Math.min(outer.right, inner.right) - Math.max(outer.left, inner.left)) *
      Math.max(0, Math.min(outer.bottom, inner.bottom) - Math.max(outer.top, inner.top));
      return area > 0 && overlap >= area * .95;
    };
    const behind = (surface, element) => !!(surface.element.compareDocumentPosition(element) & 4);
    const insideEllipse = (outer, inner) => {
      const rx = outer.width / 2,ry = outer.height / 2,cx = outer.left + rx,cy = outer.top + ry;
      if (!(rx > 0 && ry > 0 && inner.width > 0 && inner.height > 0)) return false;
      return [inner.left, inner.right].every((x) => [inner.top, inner.bottom].every((y) =>
      ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1));
    };
    const backdrop = (element, box) => surfaces.some((s) => s.preserved && s.backdrop && behind(s, element) && (
    s.ellipse ? insideEllipse(s.ellipse, box) : contains(s.box, box)));
    for (const element of slide.querySelectorAll('svg.drawing,img,image')) {
      const box = element.getBoundingClientRect();
      if (/^(IMG|IMAGE)$/i.test(element.tagName)) {
        const frame = element.closest('.block') || element;
        surfaces.push({ element, box: frame.getBoundingClientRect(), preserved: true, backdrop: true });continue;
      }
      const visible = (e) => {for (let at = e; at && at !== slide; at = at.parentElement) {const s = getComputedStyle(at);if (s.display === 'none' || s.visibility === 'hidden' || Number(s.opacity || 1) === 0) return false;}return true;};
      const fills = [...element.querySelectorAll('path,rect,circle,ellipse,polygon,polyline,use')].
      filter((e) => !e.closest('defs') && visible(e)).map((e) => {const s = getComputedStyle(e);return { element: e, value: s.fill, opacity: Number(s.fillOpacity || 1) * Number(s.opacity || 1) * Number(getComputedStyle(element).opacity || 1) };}).
      filter((f) => f.opacity > 0 && (/url\(/i.test(f.value) || (rgba(f.value)?.[3] || 0) > 0));
      if (!fills.length) continue;
      const rectangle = fills.length === 1 && fills[0].element.tagName.toLowerCase() === 'rect';
      const paperFill = rectangle && fills.every((f) => {const c = rgba(f.value);return c && c[3] > .95 && f.opacity > .95 && lightPaper(c);});
      const preserved = !paperFill || backdrop(element, box);
      if (!preserved) paperShapes.add(element);
      const opaque = fills.every((f) => {const c = rgba(f.value);return c && c[3] > .95 && f.opacity > .95;});
      const authoredFill = fills.some((f) => /url\(/i.test(f.value));
      let ellipse = null;
      if (opaque && fills.length === 1 && /^(ellipse|circle)$/i.test(fills[0].element.tagName)) {
        const fill = fills[0].element,matrix = fill.getScreenCTM?.();
        let solid = rgba(fills[0].value)?.[3] === 1 && fills[0].opacity === 1 &&
        !!matrix && Math.abs(matrix.b) < 1e-6 && Math.abs(matrix.c) < 1e-6;
        for (let at = fill; solid && at && at !== slide; at = at.parentElement) {
          const s = getComputedStyle(at);
          if (Number(s.opacity || 1) < 1 || s.clipPath && s.clipPath !== 'none' ||
          s.maskImage && s.maskImage !== 'none' || s.filter && s.filter !== 'none') solid = false;
        }
        if (solid) ellipse = fill.getBoundingClientRect();
      }
      surfaces.push({ element, box, preserved, ellipse, backdrop: opaque && (rectangle || !!ellipse), ownLabel: opaque || authoredFill });
    }
    for (const element of [slide, ...slide.querySelectorAll('*')]) {
      const s = getComputedStyle(element),c = rgba(s.backgroundColor);
      if (/gradient\(|url\(/i.test(s.backgroundImage) || c && c[3] > 0 && !lightPaper(c)) protectedCss.add(element);
    }
    const cache = new Map();
    function preserved(element) {
      for (let at = element; at; at = at.parentElement) {if (protectedCss.has(at)) return true;if (at === slide) break;}
      const block = element.closest('.block:not(.group):not(.office-group-coordinates)');
      if (!block) return false;
      if (!cache.has(block)) {
        const paired = surfaces.find((s) => s.element === block.previousElementSibling);
        cache.set(block, !!(paired?.preserved && paired?.ownLabel) || backdrop(block, block.getBoundingClientRect()));
      }
      return cache.get(block);
    }
    return { paperShapes, preserved };
  }
  function hasImageBackground(slide, background) {
    if (background && /url\(/i.test(getComputedStyle(background).backgroundImage)) return true;
    const box = slide.getBoundingClientRect(),area = box.width * box.height;
    if (!area) return false;
    for (const image of slide.querySelectorAll('img,image')) {
      const raw = image.getBoundingClientRect();
      const at = { left: raw.left, right: raw.right, top: raw.top, bottom: raw.bottom };
      for (let parent = image.parentElement; parent && parent !== slide; parent = parent.parentElement) {
        const style = getComputedStyle(parent),clip = parent.getBoundingClientRect();
        if (/^(hidden|clip|scroll|auto)$/.test(style.overflowX)) {at.left = Math.max(at.left, clip.left);at.right = Math.min(at.right, clip.right);}
        if (/^(hidden|clip|scroll|auto)$/.test(style.overflowY)) {at.top = Math.max(at.top, clip.top);at.bottom = Math.min(at.bottom, clip.bottom);}
      }
      const width = Math.max(0, Math.min(at.right, box.right) - Math.max(at.left, box.left));
      const height = Math.max(0, Math.min(at.bottom, box.bottom) - Math.max(at.top, box.top));
      if (width * height >= area * .8) return true;
    }
    return false;
  }
  function prepare(container) {
    if (records) return;

    {
      root.classList.add('office-night-measuring');
      try {prepareVisible(container);} finally
      {root.classList.remove('office-night-measuring');}

    }
  }
  function prepareVisible(container) {
    if (records) return;
    records = [];
    let id = 0;
    for (const slide of container.querySelectorAll('.slide')) {
      const background = slide.querySelector('[class^="slide-background-"]');
      const base = rgba(getComputedStyle(background || slide).backgroundColor);
      const photoBackground = hasImageBackground(slide, background);
      const authoredDark = base && base[3] > .5 && luminance(base) < .35;
      const design = localDesign(slide);
      if (photoBackground || authoredDark) {
        slide.dataset.officeNightPreserved = photoBackground ? 'imageBackground' : 'authoredDark';
      }
      slide.classList.add('office-night-ink');
      for (const element of [slide, ...slide.querySelectorAll('*')]) {
        if (/^(STYLE|DEFS)$/i.test(element.tagName)) continue;
        const style = getComputedStyle(element),values = [],identity = String(++id);
        element.dataset.officeNightId = identity;
        if (/^(IMG|IMAGE)$/i.test(element.tagName)) {
          imageRules.push('html.office-night.office-invert-images #result [data-office-night-id="' + identity + '"]{filter:' + (style.filter === 'none' ? '' : style.filter + ' ') + 'invert(1) hue-rotate(180deg) !important}');
          continue;
        }
        if (/url\(/i.test(style.backgroundImage)) {
          const selector = 'html.office-night.office-invert-images #result [data-office-night-id="' + identity + '"]';
          imageRules.push(selector + '{background-image:none !important;isolation:isolate;' + (style.position === 'static' ? 'position:relative;' : '') + '}');
          imageRules.push(selector + '::before{content:"";position:absolute;inset:0;z-index:-1;pointer-events:none;background-image:' + style.backgroundImage + ';background-size:' + style.backgroundSize + ';background-position:' + style.backgroundPosition + ';background-repeat:' + style.backgroundRepeat + ';background-origin:' + style.backgroundOrigin + ';background-clip:' + style.backgroundClip + ';filter:invert(1) hue-rotate(180deg)}');
        }
        for (const name of props) {
          const value = style.getPropertyValue(name);
          if (value && !/url\(|none|currentcolor|inherit/i.test(value) && rgba(value)?.[3] !== 0) values.push([name, value]);
        }
        const gradient = style.backgroundImage;
        if (/gradient\(/.test(gradient) && !/url\(/.test(gradient)) values.push(['background-image', gradient]);
        if (!values.length) continue;
        const svg = element.closest('svg.drawing');
        const paperShape = !!svg && design.paperShapes.has(svg);
        const preserved = !!svg && !paperShape || design.preserved(element);
        records.push({ id: identity, values, photoBackground, authoredDark, svg: !!svg, paperShape, preserved });
      }
    }
    sheet = document.createElement('style');sheet.id = 'office-pptx-night';document.head.append(sheet);
    apply();
  }
  function apply() {

    {
      if (sheet && records) {
        sheet.textContent = records.map((record) => {
          if (record.authoredDark) return '';
          const declarations = record.values.map(([name, value]) => {
            const night = name === 'background-image' ? value : transform(name, value, record);
            return night ? name + ':' + night + ' !important' : '';
          }).filter(Boolean).join(';');
          const scope = record.photoBackground ? 'html.office-night.office-invert-images' : 'html.office-night';
          return declarations ? scope + ' #result [data-office-night-id="' + record.id + '"]{' + declarations + '}' : '';
        }).join('\n') + '\n' + imageRules.join('\n');
      }
      root.classList.toggle('office-night', dark && !!records);
      root.classList.toggle('office-invert-images', dark && !preserveImages && !!records);

    }
  }
  function colors(value) {
    if (typeof value.readingDark === 'boolean') dark = value.readingDark;
    preserveImages = value.preserveImages !== false;
    if (Number.isInteger(value.readingPaper)) paper = '#' + (value.readingPaper >>> 0).toString(16).padStart(8, '0').slice(-6);
    if (Number.isInteger(value.foreground)) ink = '#' + (value.foreground >>> 0).toString(16).padStart(8, '0').slice(-6);
    const next = JSON.stringify([dark, preserveImages, paper, ink]);
    if (next !== key) {key = next;apply();}
  }
  function dispose() {sheet?.remove();sheet = null;records = null;imageRules = [];key = '';root.classList.remove('office-night', 'office-invert-images');}
  window.OfficePptxNight = Object.freeze({ prepare, colors, dispose });
})();
