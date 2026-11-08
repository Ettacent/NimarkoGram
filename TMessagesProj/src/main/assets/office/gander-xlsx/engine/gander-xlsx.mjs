/* Modifications Copyright (C) 2026 Ettacent */

/* Gander XLSX reader adaptation, MIT, Copyright (c) 2026 Arjun Maniyani.
 * mokshablr/gander@4e60c1ea460d871012576e7e330e585efd7514ec, viewer/xlsx.js.
 * Local modifications Copyright (C) 2026 Ettacent. See GANDER_LICENSE.txt.
 */
import { decodeCell, reject } from './gander-core.mjs';
import { applyCellTokens } from './xlsx-style-tokens.mjs';
import { CACHE, pieceCells, withinVisible, maxViewportHeight, admitViewport } from './sheet-cache-budget.mjs';
const css = `
.gander-reader{position:fixed;inset:0;overflow:auto;overflow-y:scroll;touch-action:pan-x pan-y;background:var(--office-desk)}
.gander-spacer{position:relative;min-width:100%;overflow:hidden}
.gander-sheet{position:absolute;transform-origin:0 0;color:var(--office-ink,#222);background:var(--office-paper,#fff)}
/* Fixed column widths plus collapsed outer borders can exceed the piece width.
   Let those authored edges paint; cell content remains clipped by each td. */
.gander-piece{position:relative;box-sizing:border-box;min-height:1px}
.gander-piece table{border-collapse:collapse;table-layout:fixed;font:13px system-ui,sans-serif;width:100%;background:var(--office-paper,#fff);color:var(--office-ink,#222)}
.gander-piece td{border:1px solid var(--office-border,#cfd8dc);padding:4px 8px;white-space:pre;min-width:0;overflow:hidden;box-sizing:border-box}
::highlight(office-sheet-find){background-color:var(--office-find,#ffe466)}
::highlight(office-sheet-active){background-color:var(--office-accent,#2481cc);color:var(--office-accent-ink,#fff)}
.gander-reader .xlsx-tab-strip{display:none!important}
`;
const nextTask = () => new Promise((resolve) => setTimeout(resolve, 0));
function element(doc, tag, cls) {const el = doc.createElement(tag);if (cls) el.className = cls;return el;}
function cellText(node) {if (node.nodeType === 3) return node.nodeValue.replace(/\n/g, '\r');if (node.nodeName === 'BR') return '\n';return [...node.childNodes].map(cellText).join('');}

export function columnWidths(table, cols) {
  const widths = Array(cols).fill(null),covered = [];let found = 0;
  for (const row of table.rows) {
    if (found === cols) break;
    let col = 0;
    for (const cell of row.cells) {
      while (covered[col] > 0) col++;
      if (cell.colSpan === 1 && col < cols && widths[col] === null) {widths[col] = cell.getBoundingClientRect().width;found++;}
      if (cell.rowSpan > 1) for (let j = 0; j < cell.colSpan; j++) covered[col + j] = cell.rowSpan;
      col += cell.colSpan;
    }
    for (let k = 0; k < covered.length; k++) if (covered[k] > 0) covered[k]--;
  }
  return widths;
}

export function safeTable(doc, html, sheet, piece, widths, styles = {}) {
  const template = element(doc, 'template');template.innerHTML = html;
  const table = element(doc, 'table'),group = element(doc, 'colgroup'),body = element(doc, 'tbody');
  for (const width of widths) {const col = element(doc, 'col');col.style.width = width + 'px';if (width === 0) col.style.display = 'none';group.appendChild(col);}
  table.append(group, body);
  let row = piece.s;
  for (const source of template.content.querySelectorAll('tr')) {
    const tr = element(doc, 'tr');tr.dataset.row = String(row);
    const meta = sheet.rows[row];
    if (meta?.hidden) tr.style.display = 'none';else
    if (Number.isFinite(meta?.hpx) || Number.isFinite(meta?.hpt)) tr.style.height = Math.max(1, meta.hpx ?? meta.hpt * 4 / 3) + 'px';
    for (const raw of source.children) {
      if (!['TD', 'TH'].includes(raw.tagName)) continue;
      const match = /-([A-Z]{1,3}[1-9][0-9]{0,6})$/.exec(raw.id);
      if (!match) reject(3, 'sheetjs-cell-id-contract');
      const point = decodeCell(match[1]);
      if (point.r !== row || point.c < sheet.range.s.c || point.c > sheet.range.e.c) reject(3, 'sheetjs-cell-range');
      const td = element(doc, 'td');td.dataset.ref = match[1];td.textContent = cellText(raw);
      const rs = Number(raw.getAttribute('rowspan') || 1),cs = Number(raw.getAttribute('colspan') || 1);
      if (!Number.isInteger(rs) || rs < 1 || row + rs - 1 > piece.e || !Number.isInteger(cs) || cs < 1 || point.c + cs - 1 > sheet.range.e.c) reject(3, 'sheetjs-cell-span');
      td.rowSpan = rs;td.colSpan = cs;
      applyCellTokens(td, styles[match[1]]);
      if (sheet.cols[point.c]?.hidden) td.style.display = 'none';
      tr.appendChild(td);
    }
    body.appendChild(tr);row++;
  }
  if (row !== piece.e + 1 && piece.e >= piece.s) reject(3, 'sheetjs-row-contract');
  table.officeCells = new Map([...table.querySelectorAll('td[data-ref]')].map((td) => [td.dataset.ref, td]));
  return table;
}

export class XlsxViewer {
  constructor(root, options = {}) {
    this.root = root;this.options = options;this.doc = root.ownerDocument;this.destroyed = false;
    this.scale = 1;this.logicalZoom = true;this.bottomInset = 0;this.sheetIndex = -1;this.sheetGeneration = 0;this.showGeneration = 0;
    this.searchGeneration = 0;this.matchIndex = -1;this.matches = [];this.requests = new Map();this.serial = 0;this.topInset = 0;
    this.host = element(this.doc, 'div', 'gander-reader');
    const style = element(this.doc, 'style');style.textContent = css;
    this.viewport = this.host;
    this.spacer = element(this.doc, 'div', 'gander-spacer');this.sheetDiv = element(this.doc, 'div', 'gander-sheet');
    this.spacer.appendChild(this.sheetDiv);
    this.tabs = element(this.doc, 'div', 'xlsx-tab-strip');this.buttons = element(this.doc, 'div');this.tabs.appendChild(this.buttons);
    this.host.append(style, this.spacer, this.tabs);root.appendChild(this.host);
    this.onScroll = () => this.scheduleVisible();this.doc.defaultView.addEventListener('scroll', this.onScroll, { passive: true });
    this.doc.defaultView.visualViewport?.addEventListener('scroll', this.onScroll, { passive: true });
    this.onHostScroll = () => {this.scheduleVisible();this.options.onViewportChange?.();};
    this.host.addEventListener('scroll', this.onHostScroll, { passive: true });
    this.resize = new ResizeObserver(() => {this.updateExtent();this.scheduleVisible();this.options.onGeometryChange?.();});this.resize.observe(this.viewport);this.resize.observe(this.sheetDiv);
    this.onVisualResize = () => {try {if (this.model && !this.destroyed && !this.paused && !this.hasVisibleCoverage()) this.scheduleVisible();} catch (error) {this.options.onError?.(error);}};
    this.doc.defaultView.visualViewport?.addEventListener('resize', this.onVisualResize, { passive: true });
  }


  async load(bytes) {
    this.worker = new Worker(new URL('./gander-worker.js', import.meta.url));
    this.worker.onmessage = ({ data }) => {
      const item = this.requests.get(data.id);if (!item) return;this.requests.delete(data.id);clearTimeout(item.timer);
      if (data.type === 'error') item.reject(Object.assign(new Error(data.reason), { officeCode: data.officeCode }));else item.resolve(data);
    };
    this.worker.onerror = () => this.workerFailed();
    this.worker.onmessageerror = () => this.workerFailed();
    const parsed = await this.request('parse', { bytes }, [bytes]);
    if (this.destroyed) return;
    this.model = parsed.model;this.parserVersion = parsed.version;
    this.sheetCount = this.model.sheets.length;this.sheetNames = this.model.sheets.map((s) => s.name);
    this.visibleSheetCount = this.model.sheets.filter((s) => !s.hidden).length;
    for (const s of this.model.sheets) {
      const b = element(this.doc, 'button');b.textContent = s.name;if (s.hidden) b.style.display = 'none';this.buttons.appendChild(b);
      s.widths = s.range ? Array.from({ length: s.range.e.c - s.range.s.c + 1 }, (_, i) => {
        const col = s.cols[s.range.s.c + i];return col?.hidden ? 0 : Math.max(24, Math.min(2048, col?.wpx ?? (col?.wch ?? 10) * 7 + 16));
      }) : [];
      s.width = Math.max(1, s.widths.reduce((a, b) => a + b, 0));
      for (const p of s.pieces) {
        p.height = 0;
        for (let r = p.s; r <= p.e; r++) {const row = s.rows[r];p.height += row?.hidden ? 0 : Math.max(1, row?.hpx ?? (row?.hpt ? row.hpt * 4 / 3 : 27));}
        p.height = Math.max(1, p.height);p.table = null;
      }
      if (s.width > 8000000 || s.pieces.reduce((height, p) => height + p.height, 0) > 8000000) reject(3, 'native-layout-extent');
      if (!admitViewport(s, maxViewportHeight(this.doc.defaultView))) reject(3, 'native-visible-dom-budget');
    }
    await this.goToSheet(this.model.sheets.find((s) => !s.hidden).index);
  }
  workerFailed(error = Object.assign(new Error('gander-worker-failed'), { officeCode: 3 })) {
    if (this.destroyed || this.workerError) return;
    this.workerError = error;this.committed = false;
    this.waitingForPiece = false;this.fillAgain = false;
    cancelAnimationFrame(this.frame);this.frame = 0;
    const worker = this.worker;this.worker = null;
    if (worker) {worker.onmessage = null;worker.onerror = null;worker.onmessageerror = null;worker.terminate();}
    for (const item of this.requests.values()) {clearTimeout(item.timer);item.reject(error);}
    this.requests.clear();
    if (this.queuedPiece) {this.queuedPiece.reject(error);this.queuedPiece = null;}
    this.options.onError?.(error);
  }
  request(type, data = {}, transfer = []) {
    if (this.workerError) return Promise.reject(this.workerError);
    if (this.destroyed) return Promise.reject(Object.assign(new Error('destroyed'), { officeCode: 1 }));
    const id = ++this.serial;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {if (this.requests.has(id)) this.workerFailed(Object.assign(new Error('gander-worker-timeout'), { officeCode: 5 }));}, 30000);
      this.requests.set(id, { resolve, reject, timer });
      try {this.worker.postMessage({ id, type, ...data }, transfer);} catch (error) {this.requests.delete(id);clearTimeout(timer);reject(error);}
    });
  }
  getSheetGeneration() {return this.sheetGeneration;}
  getScale() {return this.scale;}
  getMaximumScale(index = this.sheetIndex) {
    const sheet = this.model.sheets[index];
    return Math.max(.1, Math.min(10, 7990000 / Math.max(sheet.width, sheet.pieces.reduce((sum, p) => sum + p.height, 0))));
  }
  getMinimumScale(index = this.sheetIndex, width = this.viewport.clientWidth) {
    const sheet = this.model?.sheets[index];if (!sheet) return 1;
    return Math.min(this.getMaximumScale(index), Math.min(1, Math.max(.5, (Math.max(1, width) - 32) / sheet.width)));
  }
  ceilExtent(value) {
    const nearest = Math.round(value);
    return Math.abs(value - nearest) <= 8 * Number.EPSILON * Math.max(1, Math.abs(value)) ? nearest : Math.ceil(value);
  }
  getScrollState() {return { x: this.host.scrollLeft, y: this.host.scrollTop };}
  setInsets(top, bottom) {if (top === this.topInset && bottom === this.bottomInset) return;this.topInset = top;this.bottomInset = bottom;this.updateExtent();}
  setTopInset(value) {this.topInset = value;this.updateExtent();}
  updateExtent() {

    {
      const sheet = this.model?.sheets[this.sheetIndex];if (!sheet || this.zoomPreview) return;
      const deviceWidth = Math.max(1, this.viewport.clientWidth),gutter = 16;
      const next = Math.max(this.getMinimumScale(this.sheetIndex, deviceWidth), Math.min(this.getMaximumScale(), this.scale));
      let anchor = null;
      if (next !== this.scale) {
        const x = deviceWidth / 2,y = this.topInset + Math.max(0, this.viewport.clientHeight - this.topInset - this.bottomInset) / 2;
        const oldX = (this.extentWidth ?? deviceWidth) / 2,oldY = this.topInset + Math.max(0, (this.extentHeight ?? this.viewport.clientHeight) - this.topInset - this.bottomInset) / 2;
        anchor = { x, y, sx: (this.host.scrollLeft + oldX - (this.sheetLeft || 0)) / this.scale, sy: (this.host.scrollTop + oldY - this.topInset) / this.scale };
        this.scale = next;
      }
      const width = sheet.width + 'px',extent = this.ceilExtent(Math.max(deviceWidth, sheet.width * this.scale + 2 * gutter)) + 'px';
      const height = sheet.pieces.reduce((sum, p) => sum + p.height, 0);
      if (sheet.width * this.scale + 32 > 8000000 || this.topInset + height * this.scale + this.bottomInset > 8000000) reject(3, 'native-layout-extent');
      if (this.sheetDiv.style.width !== width) {this.sheetDiv.style.width = width;}
      if (this.spacer.style.width !== extent) {this.spacer.style.width = extent;}
      this.spacer.style.height = this.ceilExtent(Math.max(this.viewport.clientHeight, this.topInset + height * this.scale + this.bottomInset)) + 'px';
      this.sheetLeft = (parseFloat(extent) - sheet.width * this.scale) / 2;
      this.sheetDiv.style.left = this.sheetLeft + 'px';this.sheetDiv.style.top = this.topInset + 'px';
      this.sheetDiv.style.transform = 'scale(' + this.scale + ')';
      this.extentWidth = deviceWidth;this.extentHeight = this.viewport.clientHeight;
      if (anchor) {
        this.host.scrollTo({ left: Math.max(0, Math.min(parseFloat(extent) - deviceWidth, this.sheetLeft + anchor.sx * this.scale - anchor.x)),
          top: Math.max(0, Math.min(parseFloat(this.spacer.style.height) - this.viewport.clientHeight, this.topInset + anchor.sy * this.scale - anchor.y)), behavior: 'instant' });
        this.options.onViewportWrite?.();
      }
    }
  }
  async goToSheet(index) {
    if (this.destroyed) return;
    const sheet = this.model?.sheets[index];if (!sheet || sheet.hidden) reject(1, 'hidden-or-missing-sheet');
    if (index === this.sheetIndex) return this.showPending;
    const scale = Math.max(this.getMinimumScale(index), Math.min(this.scale, this.getMaximumScale(index)));
    const generation = ++this.showGeneration;++this.sheetGeneration;this.sheetIndex = index;this.committed = false;
    this.scale = scale;
    this.cacheTableLimit = CACHE.baseTables;
    cancelAnimationFrame(this.frame);this.frame = 0;
    this.viewport.scrollTop = 0;this.viewport.scrollLeft = 0;
    this.sheetDiv.replaceChildren();
    for (const s of this.model.sheets) for (const p of s.pieces) {p.table = null;p.block = null;p.pending = null;}
    for (const p of sheet.pieces) {p.block = element(this.doc, 'div', 'gander-piece');p.block.style.height = p.height + 'px';this.sheetDiv.appendChild(p.block);}
    this.updateExtent();this.options.onViewportWrite?.();this.options.onSheetChange?.(index, this.sheetCount);
    const work = (async () => {
      await this.place(0, generation);
      if (this.destroyed || generation !== this.showGeneration) return;
      await this.fillVisible();
      if (this.destroyed || generation !== this.showGeneration) return;
      if (this.paused || !this.hasVisibleCoverage()) return;
      this.committed = true;this.sheetDiv.setAttribute('data-gander-commit', String(generation));this.scheduleVisible();
    })();
    this.showPending = work;return work;
  }
  isPaintCommitted(index) {return !this.destroyed && this.committed && this.sheetIndex === index;}
  getReadingRects() {return [this.sheetDiv.getBoundingClientRect()];}
  scrollViewportTo(x, y) {
    if (this.destroyed || this.paused || !Number.isFinite(x) || !Number.isFinite(y)) return false;
    const win = this.doc.defaultView,root = this.doc.documentElement.style,style = this.sheetDiv.style;
    const r = this.sheetDiv.getBoundingClientRect(),dx = x - (r.left + win.scrollX),dy = y - (r.top + win.scrollY);
    const names = ['top', 'right', 'bottom', 'left'];
    const saved = names.flatMap((edge) => [[root, 'scroll-padding-' + edge], [style, 'scroll-margin-' + edge]]).
    map(([target, name]) => [target, name, target.getPropertyValue(name), target.getPropertyPriority(name)]);
    try {
      for (const edge of names) root.setProperty('scroll-padding-' + edge, '0px');
      for (const [edge, value] of [['top', -dy], ['bottom', dy], ['left', -dx], ['right', dx]]) style.setProperty('scroll-margin-' + edge, value + 'px');
      this.sheetDiv.scrollIntoView({ behavior: 'instant', block: 'start', inline: 'start' });
    } catch (_) {return false;
    } finally {
      for (const [target, name, value, priority] of saved) {if (value) target.setProperty(name, value, priority);else target.removeProperty(name);}
    }
    this.options.onViewportWrite?.();this.scheduleVisible();return true;
  }
  viewportPlan() {

    {
      const sheet = this.model?.sheets[this.sheetIndex];if (!sheet) return null;
      const win = this.doc.defaultView,vv = win.visualViewport;
      if (!this.zoomPreview) for (const p of sheet.pieces) if (p.table) p.height = Math.max(1, p.block.getBoundingClientRect().height / this.scale);
      const top = (this.viewport.scrollTop - this.topInset) / this.scale,bottom = top + this.viewport.clientHeight / this.scale,center = (top + bottom) / 2;
      let y = 0;const visible = [],ranked = [];
      sheet.pieces.forEach((p, k) => {const end = y + p.height;if (end > top && y < bottom) visible.push(k);ranked.push({ k, distance: Math.abs((y + end) / 2 - center) });y = end;});
      ranked.sort((a, b) => a.distance - b.distance);
      if (!withinVisible(sheet, visible)) reject(3, 'native-visible-dom-budget');
      const wanted = new Set(visible);
      for (const item of ranked) {if (wanted.size >= Math.max(CACHE.baseTables, visible.length)) break;
        const next = [...wanted, item.k];if (withinVisible(sheet, next)) wanted.add(item.k);}
      this.cacheTableLimit = Math.min(CACHE.visibleTables, Math.max(this.cacheTableLimit || CACHE.baseTables, visible.length));
      return { sheet, visible: new Set(visible), wanted, ranked, center, distances: new Map(ranked.map((item) => [item.k, item.distance])) };
    }
  }
  hasVisibleCoverage() {if (this.zoomPreview) return false;const plan = this.viewportPlan();return !!plan && [...plan.visible].every((k) => !!plan.sheet.pieces[k].table);}
  calibrateRows(sheet, table, actual) {

    {
      if (sheet.rowFloor !== undefined) return;
      let floor = Infinity,rowsHeight = 0;
      for (const row of table.rows) {const height = row.getBoundingClientRect().height / this.scale;rowsHeight += height;
        const meta = sheet.rows[Number(row.dataset.row)];
        if (height > 0 && meta?.hpt === undefined && row.cells.length === sheet.widths.length && [...row.cells].every((c) => c.rowSpan === 1 && c.colSpan === 1)) floor = Math.min(floor, height);
      }
      sheet.rowFloor = Number.isFinite(floor) ? floor : sheet.defaultHeight || 1;
      sheet.tableBorder = Math.max(0, actual - rowsHeight);
      for (const p of sheet.pieces) if (!p.table) {let height = 0,shown = 0;
        for (let r = p.s; r <= p.e; r++) {const row = sheet.rows[r];if (row?.hidden) continue;shown++;height += Math.max(sheet.rowFloor, row?.hpx ?? (row?.hpt ? row.hpt * 4 / 3 : sheet.defaultHeight || 1));}
        p.height = Math.max(1, height + (shown ? sheet.tableBorder : 0));if (p.block) p.block.style.height = p.height + 'px';
      }
    }
  }
  evict(piece) {piece.block.style.height = piece.height + 'px';piece.block.replaceChildren();piece.table = null;}
  admission(plan, incoming, pinned) {
    const sheet = plan.sheet,attached = sheet.pieces.filter((p) => p.table && p !== incoming),retire = [];
    let cells = pieceCells(sheet, incoming),text = incoming.textUnits || 0,count = 1;
    for (const p of attached) {cells += pieceCells(sheet, p);text += p.textUnits || 0;count++;}
    const limit = Math.min(CACHE.totalTables, this.cacheTableLimit + (pinned ? 1 : 0));
    const candidates = attached.filter((p) => {const k = sheet.pieces.indexOf(p);return !plan.visible.has(k) && !plan.wanted.has(k);}).
    sort((a, b) => plan.distances.get(sheet.pieces.indexOf(b)) - plan.distances.get(sheet.pieces.indexOf(a)));
    for (const p of candidates) {if (count <= limit && cells <= CACHE.totalCells && text <= CACHE.totalText) break;retire.push(p);cells -= pieceCells(sheet, p);text -= p.textUnits || 0;count--;}
    return count <= limit && cells <= CACHE.totalCells && text <= CACHE.totalText ? retire : null;
  }
  pieceRequest(sheet, k, generation, isCurrent, pinned) {
    if (this.workerError) return Promise.reject(this.workerError);
    if (this.destroyed) return Promise.resolve(null);
    return new Promise((resolve, reject) => {

      const job = { sheet: sheet.index, k, generation, isCurrent, pinned,
        resolve: (value) => {resolve(value);},
        reject: (error) => {reject(error);} };
      if (this.queuedPiece) {
        if (this.queuedPiece.pinned && !pinned && this.queuedPiece.isCurrent()) {job.resolve(null);return;}
        this.queuedPiece.resolve(null);
      }
      this.queuedPiece = job;this.drainPiece();
    });
  }
  drainPiece() {
    if (this.activePiece || this.workerError || this.destroyed || !this.queuedPiece) return;
    const job = this.queuedPiece;this.queuedPiece = null;
    if (!job.isCurrent()) {job.resolve(null);this.drainPiece();return;}
    this.activePiece = job;
    void this.request('piece', { sheet: job.sheet, piece: job.k }).then(
      (result) => job.resolve(job.isCurrent() ? result : null), (error) => job.reject(error)
    ).finally(() => {if (this.activePiece === job) this.activePiece = null;this.drainPiece();
      if (this.waitingForPiece) {this.waitingForPiece = false;if (!this.destroyed && !this.paused) this.scheduleVisible();}
    });
  }
  async place(k, generation = this.showGeneration, isWanted = () => true, pinned = true) {
    const sheet = this.model.sheets[this.sheetIndex],piece = sheet.pieces[k];if (!piece || !piece.block) return;
    const current = () => !this.destroyed && !this.workerError && !this.paused && !this.zoomPreview && generation === this.showGeneration && isWanted();
    if (!current()) return;if (piece.table) return piece.table;
    if (piece.pending) {await piece.pending;if (!current()) return;return this.place(k, generation, isWanted, pinned);}
    const work = (async () => {
      const result = await this.pieceRequest(sheet, k, generation, current, pinned);
      if (!result || !current()) return;
      if (result.html.length > CACHE.pieceHTML) reject(3, 'native-piece-dom-budget');
      const plan = this.viewportPlan();if (!pinned && !plan.wanted.has(k)) return;
      const victims = this.admission(plan, piece, pinned);
      if (!victims) {if (pinned) reject(3, 'native-visible-dom-budget');return;}
      let t;
      {t = safeTable(this.doc, result.html, sheet, piece, sheet.widths, result.styles);}

      let actual;
      {
        for (const victim of victims) this.evict(victim);
        piece.block.replaceChildren(t);piece.block.style.height = 'auto';piece.table = t;
        actual = t.getBoundingClientRect().height / (this.previewScale ?? this.scale);
        if (actual > 0) piece.height = actual;
      }
      this.calibrateRows(sheet, t, Math.max(1, actual));
      this.applyHighlights(t);this.updateExtent();return t;
    })();piece.pending = work;
    try {return await work;} finally {if (piece.pending === work) piece.pending = null;}
  }
  setPaused(value) {if (value && !this.paused) {++this.searchGeneration;this.worker?.postMessage({ type: 'cancelFind' });}this.paused = !!value;if (value) {this.fillRevision = (this.fillRevision || 0) + 1;this.fillAgain = false;cancelAnimationFrame(this.frame);this.frame = 0;} else this.scheduleVisible();}
  scheduleVisible() {if (this.destroyed || this.workerError || this.paused || this.zoomPreview || !this.model || this.frame) return;if (this.waitingForPiece && (this.activePiece || this.queuedPiece)) {this.fillAgain = true;return;}this.frame = requestAnimationFrame(() => {this.frame = 0;void this.fillVisible().catch((e) => {if (!this.workerError && !this.destroyed) this.options.onError?.(e);});});}
  async fillVisible() {

    {
      this.fillRevision = (this.fillRevision || 0) + 1;this.fillAgain = true;
      if (this.waitingForPiece && (this.activePiece || this.queuedPiece)) {return this.fillWork;}
      if (this.fillWork) {return this.fillWork;}
      const work = (async () => {
        while (this.fillAgain && !this.destroyed && !this.workerError && !this.paused && !this.zoomPreview && this.model) {
          this.fillAgain = false;const generation = this.showGeneration;
          const current = () => !this.destroyed && !this.workerError && generation === this.showGeneration && !this.paused && !this.zoomPreview;
          const plan = this.viewportPlan();
          const next = plan.ranked.find((item) => plan.visible.has(item.k) && !plan.sheet.pieces[item.k].table) ||
          plan.ranked.find((item) => plan.wanted.has(item.k) && !plan.sheet.pieces[item.k].table);
          if (next) {
            await this.place(next.k, generation, current, false);if (this.destroyed) return;
            if (!plan.sheet.pieces[next.k].table && current()) {
              const latest = this.viewportPlan();
              if (!latest.wanted.has(next.k)) {this.fillAgain = true;await nextTask();continue;}
              if (this.activePiece || this.queuedPiece) {this.waitingForPiece = true;break;}
              if (latest.visible.has(next.k)) reject(3, 'native-visible-dom-budget');
              this.refreshHighlights();break;
            }
            this.fillAgain = true;await nextTask();continue;}
          const anchor = plan.sheet.pieces.find((p, k) => p.table && plan.visible.has(k));
          if (anchor) {const victims = this.admission(plan, anchor, false);if (victims) for (const victim of victims) this.evict(victim);}
          if ([...plan.visible].every((k) => !!plan.sheet.pieces[k].table)) {
            this.committed = true;const token = String(this.showGeneration);
            if (this.sheetDiv.getAttribute('data-gander-commit') !== token) this.sheetDiv.setAttribute('data-gander-commit', token);
          }
          this.refreshHighlights();
        }
      })();this.fillWork = work;
      try {const result = await work;return result;} finally {if (this.fillWork === work) this.fillWork = null;}
    }
  }
  clearFind() {++this.searchGeneration;this.matches = [];this.matchIndex = -1;this.worker?.postMessage({ type: 'cancelFind' });this.refreshHighlights();}
  async findText(query, options = {}) {
    this.clearFind();if (!query) return [];
    const generation = this.searchGeneration,result = await this.request('find', { query, caseSensitive: !!options.caseSensitive });
    if (this.destroyed || generation !== this.searchGeneration || result.type === 'cancelled') return [];
    this.matches = result.matches.slice(0, 1000);this.matchIndex = -1;this.refreshHighlights();return result.matches;
  }
  applyHighlights() {this.refreshHighlights();}
  refreshHighlights() {

    {
      const win = this.doc.defaultView;if (!win.CSS?.highlights || !win.Highlight) return;
      const all = [],active = [];const pieces = this.model?.sheets[this.sheetIndex]?.pieces || [];
      const state = [this.matches, this.matchIndex, this.sheetIndex, ...pieces.filter((p) => p.table).map((p) => p.table)];
      if (this.highlightState?.length === state.length && state.every((value, i) => value === this.highlightState[i])) {return;}
      this.highlightState = state;
      for (let i = 0; i < this.matches.length; i++) {
        const m = this.matches[i];if (m.location.sheet !== this.sheetIndex) continue;
        const td = pieces.find((p) => p.table?.officeCells.has(m.location.ref))?.table.officeCells.get(m.location.ref),text = td?.firstChild;
        if (!text || text.nodeType !== 3 || m.start < 0 || m.end > text.length) continue;
        const range = this.doc.createRange();range.setStart(text, m.start);range.setEnd(text, m.end);all.push(range);if (i === this.matchIndex) active.push(range);
      }
      win.CSS.highlights.set('office-sheet-find', new win.Highlight(...all));win.CSS.highlights.set('office-sheet-active', new win.Highlight(...active));
    }
  }
  findNext(options) {return this.moveFind(1, options);}
  findPrev(options) {return this.moveFind(-1, options);}
  async moveFind(delta, { isCurrent = () => true } = {}) {
    if (!this.matches.length || !isCurrent()) return null;
    const generation = this.searchGeneration,index = (this.matchIndex + (delta > 0 ? 1 : this.matchIndex < 0 ? 0 : -1) + this.matches.length) % this.matches.length;
    const match = this.matches[index];
    const work = match.location.sheet !== this.sheetIndex ? this.goToSheet(match.location.sheet) : null;
    if (work) await work;
    if (this.destroyed || generation !== this.searchGeneration || !isCurrent()) return null;
    const shown = await this.scrollToCell(match.location.ref, { isCurrent });
    if (!shown || this.destroyed || generation !== this.searchGeneration || !isCurrent()) return null;
    this.matchIndex = index;this.refreshHighlights();return { ...match, matchIndex: index };
  }
  async scrollToCell(ref, { isCurrent = () => true } = {}) {
    if (this.destroyed || this.paused || !this.model || !isCurrent()) return false;
    const sheet = this.model.sheets[this.sheetIndex],point = decodeCell(ref),generation = this.showGeneration;
    const k = sheet.pieces.findIndex((p) => p.s <= point.r && p.e >= point.r);if (k < 0 || !isCurrent()) return false;
    const table = await this.place(k, generation, isCurrent);
    if (!table || this.destroyed || this.paused || generation !== this.showGeneration || !isCurrent()) return false;
    const cell = table.querySelector(`td[data-ref="${ref}"]`);if (!cell) return false;
    if (this.options.prepareReveal?.() === false) return false;
    if (this.destroyed || this.paused || generation !== this.showGeneration || !isCurrent()) return false;
    const win = this.doc.defaultView,style = this.viewport.style,insets = this.options.getVisibleInsets?.() || {};
    const top = Number.isFinite(insets.top) ? Math.max(0, insets.top) : this.topInset;
    const bottom = Number.isFinite(insets.bottom) ? Math.max(0, insets.bottom) : Number.parseFloat(style.getPropertyValue('--office-bottom')) || 0;
    const saved = ['scroll-padding-top', 'scroll-padding-bottom'].map((name) => [name, style.getPropertyValue(name), style.getPropertyPriority(name)]);
    try {
      style.setProperty('scroll-padding-top', top + 1 + 'px');style.setProperty('scroll-padding-bottom', bottom + 1 + 'px');
      cell.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'center' });
    } finally {for (const [name, value, priority] of saved) {if (value) style.setProperty(name, value, priority);else style.removeProperty(name);}}
    this.options.onViewportWrite?.();
    this.scheduleVisible();
    const r = cell.getBoundingClientRect(),v = win.visualViewport,left = v?.offsetLeft || 0,right = left + (v?.width || win.innerWidth);
    const safeTop = (v?.offsetTop || 0) + top,safeBottom = (v?.offsetTop || 0) + (v?.height || win.innerHeight) - bottom,tolerance = 1 / (v?.scale || 1);
    if (r.width <= 0 || r.height <= 0 || safeBottom <= safeTop || right <= left) return false;
    const vertical = r.height <= safeBottom - safeTop ? r.top >= safeTop - tolerance && r.bottom <= safeBottom + tolerance : r.bottom > safeTop && r.top < safeBottom;
    const horizontal = r.width <= right - left ? r.left >= left - tolerance && r.right <= right + tolerance : r.right > left && r.left < right;
    return vertical && horizontal;
  }
  destroy() {
    if (this.destroyed) return;this.destroyed = true;++this.showGeneration;++this.searchGeneration;
    this.waitingForPiece = false;this.fillAgain = false;
    cancelAnimationFrame(this.frame);this.resize.disconnect();this.doc.defaultView.removeEventListener('scroll', this.onScroll);
    this.doc.defaultView.visualViewport?.removeEventListener('scroll', this.onScroll);
    this.doc.defaultView.visualViewport?.removeEventListener('resize', this.onVisualResize);
    this.host.removeEventListener('scroll', this.onHostScroll);
    this.doc.defaultView.CSS?.highlights?.delete('office-sheet-find');this.doc.defaultView.CSS?.highlights?.delete('office-sheet-active');
    this.worker?.terminate();for (const item of this.requests.values()) {clearTimeout(item.timer);item.reject(Object.assign(new Error('destroyed'), { officeCode: 1 }));}
    if (this.queuedPiece) {this.queuedPiece.resolve(null);this.queuedPiece = null;}
    this.requests.clear();this.matches = [];this.model = null;this.host.remove();
  }
}
