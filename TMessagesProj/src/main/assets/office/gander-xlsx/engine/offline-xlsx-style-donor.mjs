/* Modifications Copyright (C) 2026 Ettacent */

/* Adapted from huseyiniriss/offline_document_viewer@8904c7bb93ebdc5265476febdd2344bb46a97326
 * assets/engines/xlsx/xlsx-render.js, MIT, Copyright (c) 2026 Hüseyin İriş.
 * Full grant: OFFLINE_DOCUMENT_VIEWER_LICENSE.txt. Original source SHA256 d23ccc4713f2bb8bc909200dcf487a72ffb1c4417c7e188de58f38c0c34ac6d3.
 * Local modifications Copyright (C) 2026 Ettacent.
 */
import {reject} from './gander-core.mjs';
import {parseTree,validateColor,validateStyleTree,enabled,DEFAULT_INDEXED_COLORS} from './xlsx-style-guards.mjs';
  const EMU_PER_PT = 96 / 72;

  const INDEXED = DEFAULT_INDEXED_COLORS;

  const THEME_ORDER = ['lt1', 'dk1', 'lt2', 'dk2', 'accent1', 'accent2',
    'accent3', 'accent4', 'accent5', 'accent6', 'hlink', 'folHlink'];

  const BORDER_CSS = {
    thin: '1px solid', medium: '2px solid', thick: '3px solid',
    dashed: '1px dashed', dotted: '1px dotted', double: '3px double',
    hair: '1px solid', mediumDashed: '2px dashed', dashDot: '1px dashed',
    mediumDashDot: '2px dashed', dashDotDot: '1px dotted',
    mediumDashDotDot: '2px dotted', slantDashDot: '2px dashed',
  };

  const H_ALIGN = {
    left: 'left', center: 'center', right: 'right', justify: 'justify',
    centerContinuous: 'center', distributed: 'justify', fill: 'left',
  };

  const V_ALIGN = { top: 'top', center: 'middle', bottom: 'bottom', justify: 'middle', distributed: 'middle' };

  const parseXml = parseTree;

  const kids = (node, name) =>
    node ? Array.from(node.children).filter((c) => c.localName === name) : [];

  const kid = (node, name) => kids(node, name)[0];


  function rgbToHsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    const l = (max + min) / 2;
    if (max === min) return [0, 0, l];
    const d = max - min;
    const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    let h;
    if (max === r) h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
    else if (max === g) h = ((b - r) / d + 2) / 6;
    else h = ((r - g) / d + 4) / 6;
    return [h, s, l];
  }

  function hslToRgb(h, s, l) {
    if (s === 0) { const v = Math.round(l * 255); return [v, v, v]; }
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;
    const f = (t) => {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    };
    return [f(h + 1 / 3), f(h), f(h - 1 / 3)].map((v) => Math.round(v * 255));
  }

  function applyTint(hex, tint) {
    if (!tint) return hex;
    const n = parseInt(hex, 16);
    const [h, s, l] = rgbToHsl((n >> 16) & 255, (n >> 8) & 255, n & 255);
    const nl = tint < 0 ? l * (1 + tint) : l * (1 - tint) + tint;
    return hslToRgb(h, s, Math.min(1, Math.max(0, nl)))
      .map((v) => v.toString(16).padStart(2, '0')).join('');
  }

  function resolveColor(node, theme) {
    validateColor(node);
    if (!node) return null;
    const rgb = node.getAttribute('rgb');
    const tint = parseFloat(node.getAttribute('tint') || '0');
    if (rgb) {
      return '#' + applyTint(rgb.length === 8 ? rgb.slice(2) : rgb, tint);
    }
    const themeIdx = node.getAttribute('theme');
    if (themeIdx !== null && theme[+themeIdx]) {
      return '#' + applyTint(theme[+themeIdx], tint);
    }
    const idx = node.getAttribute('indexed');
    if (idx !== null && INDEXED[+idx]) return '#' + applyTint(INDEXED[+idx], tint);
    return null;
  }


  async function parseTheme(zip) {
    const file = zip.file('xl/theme/theme1.xml');
    if (!file) return [];
    const doc = parseXml(await file.async('string'));
    const scheme = doc.getElementsByTagNameNS('*', 'clrScheme')[0];
    if (!scheme) return [];

    const byName = {};
    Array.from(scheme.children).forEach((c) => {
      const srgb = kid(c, 'srgbClr');
      const sys = kid(c, 'sysClr');
      byName[c.localName] = srgb
        ? srgb.getAttribute('val')
        : (sys ? sys.getAttribute('lastClr') : null);
    });
    return THEME_ORDER.map((n) => byName[n] || '000000');
  }


  async function parseStyles(zip, theme) {
    const file = zip.file('xl/styles.xml');
    if (!file) return { xfs: [] };
    const doc = parseXml(await file.async('string'));
    const root = doc.documentElement;
    validateStyleTree(root);

    const fonts = kids(kid(root, 'fonts'), 'font').map((f) => ({
      bold: enabled(kid(f, 'b')),
      italic: enabled(kid(f, 'i')),
      underline: enabled(kid(f, 'u')),
      strike: enabled(kid(f, 'strike')),
      size: kid(f, 'sz') ? parseFloat(kid(f, 'sz').getAttribute('val')) : null,
      color: resolveColor(kid(f, 'color'), theme),
      name: kid(f, 'name') ? kid(f, 'name').getAttribute('val') : null,
      vertAlign: kid(f, 'vertAlign') ? kid(f, 'vertAlign').getAttribute('val') : null,
    }));

    const fills = kids(kid(root, 'fills'), 'fill').map((f) => {
      const pf = kid(f, 'patternFill');
      if (!pf) return null;
      const type = pf.getAttribute('patternType');
      if (!type || type === 'none') return null;
      return resolveColor(kid(pf, 'fgColor'), theme) ||
        resolveColor(kid(pf, 'bgColor'), theme);
    });

    const borders = kids(kid(root, 'borders'), 'border').map((b) => {
      const side = (name) => {
        const el = kid(b, name);
        if (!el) return null;
        const style = el.getAttribute('style');
        if (!style || style === 'none') return null;
        const css = BORDER_CSS[style];
        if (!css) reject(3,'unsupported-border-style');
        const color = resolveColor(kid(el, 'color'), theme) || '#000';
        return `${css} ${color}`;
      };
      return {
        left: side('left'), right: side('right'),
        top: side('top'), bottom: side('bottom'),
      };
    });

    const xfs = kids(kid(root, 'cellXfs'), 'xf').map((xf) => {
      const al = kid(xf, 'alignment');
      const on = (attr) => xf.getAttribute(attr) === '1' || xf.getAttribute(attr) === 'true';
      return {
        font: on('applyFont') || xf.getAttribute('fontId') ? fonts[+xf.getAttribute('fontId') || 0] : null,
        fill: fills[+xf.getAttribute('fillId') || 0] || null,
        border: borders[+xf.getAttribute('borderId') || 0] || null,
        align: al ? {
          h: al.getAttribute('horizontal'),
          v: al.getAttribute('vertical'),
          wrap: al.getAttribute('wrapText') === '1',
          indent: parseInt(al.getAttribute('indent') || '0', 10),
          rotation: parseInt(al.getAttribute('textRotation') || '0', 10),
        } : null,
      };
    });

    return { xfs };
  }


  async function parseSheetLayout(zip, path) {
    const file = zip.file(path);
    if (!file) return { styleAt: {}, rows: {}, freeze: null, gridlines: true };
    const doc = parseXml(await file.async('string'));
    const root = doc.documentElement;

    const styleAt = {};
    const rows = {};
    kids(kid(root, 'sheetData'), 'row').forEach((r) => {
      const idx = r.getAttribute('r');
      const ht = r.getAttribute('ht');
      const hidden = r.getAttribute('hidden') === '1';
      if (ht || hidden) rows[idx] = { height: ht ? parseFloat(ht) * EMU_PER_PT : null, hidden };
      kids(r, 'c').forEach((c) => {
        const s = c.getAttribute('s');
        if (s) styleAt[c.getAttribute('r')] = +s;
      });
    });

    const view = kid(kid(root, 'sheetViews'), 'sheetView');
    const pane = view ? kid(view, 'pane') : null;
    const freeze = pane && pane.getAttribute('state') === 'frozen'
      ? { x: +(pane.getAttribute('xSplit') || 0), y: +(pane.getAttribute('ySplit') || 0) }
      : null;
    const gridlines = !view || view.getAttribute('showGridLines') !== '0';

    return { styleAt, rows, freeze, gridlines };
  }

  async function sheetPaths(zip) {
    const wbFile = zip.file('xl/workbook.xml');
    const relFile = zip.file('xl/_rels/workbook.xml.rels');
    if (!wbFile || !relFile) return {};

    const rels = {};
    Array.from(parseXml(await relFile.async('string'))
      .getElementsByTagNameNS('*', 'Relationship')).forEach((r) => {
        let t = r.getAttribute('Target');
        if (t.startsWith('/')) t = t.slice(1);
        else if (!t.startsWith('xl/')) t = 'xl/' + t;
        rels[r.getAttribute('Id')] = t.replace(/^xl\/\.\.\//, '');
      });

    const out = {};
    Array.from(parseXml(await wbFile.async('string'))
      .getElementsByTagNameNS('*', 'sheet')).forEach((s) => {
        const rid = s.getAttributeNS(
          'http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'id'
        ) || s.getAttribute('r:id');
        if (rels[rid]) out[s.getAttribute('name')] = rels[rid];
      });
    return out;
  }


  function cellCss(xf, isNumber, gridlines) {
    if (!xf) return gridlines ? 'border:1px solid #dcdfe4;' : '';
    const p = [];

    if (xf.fill) p.push(`background:${xf.fill}`);

    const f = xf.font;
    if (f) {
      if (f.bold) p.push('font-weight:700');
      if (f.italic) p.push('font-style:italic');
      if (f.strike && f.underline) p.push('text-decoration:underline line-through');
      else if (f.strike) p.push('text-decoration:line-through');
      else if (f.underline) p.push('text-decoration:underline');
      if (f.size) p.push(`font-size:${f.size}pt`);
      if (f.color) p.push(`color:${f.color}`);
      if (f.name) p.push(`font-family:'${f.name.replace(/['\\]/g, '')}',sans-serif`);
      if (f.vertAlign === 'superscript') p.push('vertical-align:super;font-size:smaller');
      if (f.vertAlign === 'subscript') p.push('vertical-align:sub;font-size:smaller');
    }

    const b = xf.border;
    let hasBorder = false;
    if (b) {
      ['top', 'right', 'bottom', 'left'].forEach((side) => {
        if (b[side]) { p.push(`border-${side}:${b[side]}`); hasBorder = true; }
      });
    }
    if (!hasBorder && gridlines) p.push('border:1px solid #dcdfe4');
    else if (!hasBorder) p.push('border:0');

    const a = xf.align;
    const h = a && a.h ? H_ALIGN[a.h] : null;
    p.push(`text-align:${h || (isNumber ? 'right' : 'left')}`);
    if (a) {
      if (a.v) p.push(`vertical-align:${V_ALIGN[a.v] || 'bottom'}`);
      p.push(a.wrap ? 'white-space:pre-wrap' : 'white-space:nowrap');
      if (a.indent) p.push(`padding-left:${4 + a.indent * 9}px`);
      if (a.rotation && a.rotation !== 255) {
        const deg = a.rotation > 90 ? 90 - a.rotation : -a.rotation;
        p.push(`transform:rotate(${deg}deg)`);
      }
    } else {
      p.push('white-space:nowrap');
    }

    return p.join(';') + ';';
  }


export {parseTheme,parseStyles,cellCss,resolveColor,applyTint};
