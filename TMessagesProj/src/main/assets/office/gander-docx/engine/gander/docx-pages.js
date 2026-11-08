/* Modifications Copyright (C) 2026 Ettacent */

"use strict";

var vwPages = {
  split: false,
  measure: null,
  turns: null,
  settled: false,
  wordPages: 0,
  turnAt: null,
  props: [],
  fields: [],
  nextField: 0,
  tabs: null,
  notes: vwNoteBook(),
  pageRoom: null
};

function vwNoteBook() {
  return { count: 0, of: Object.create(null), listed: Object.create(null) };
}

function vwIsPageMark(node) {
  return node.type === "break" && node.break === "lastRenderedPageBreak";
}

function vwHasPageMark(node) {
  var kids = node.children || [];
  for (var i = 0; i < kids.length; i++) {
    if (vwIsPageMark(kids[i]) || vwHasPageMark(kids[i])) return true;
  }
  return false;
}

var VW_BLANK = { "break": 1, bookmarkStart: 1, bookmarkEnd: 1 };
var VW_HOLDERS = { run: 1, hyperlink: 1, smartTag: 1, inserted: 1, paragraph: 1 };

function vwHasInk(node) {
  var kids = node.children || [];
  for (var i = 0; i < kids.length; i++) {
    var c = kids[i];
    if (VW_BLANK[c.type]) continue;
    if (VW_HOLDERS[c.type] ? vwHasInk(c) : true) return true;
  }
  return false;
}

function vwColumns(props) {
  return (props && props.columns && props.columns.numberOfColumns) || 1;
}

function vwColumnMarksOut(elements, all) {
  var inked = false;
  function out(node) {
    if (!node.children) return node;
    var kept = [], changed = false;
    node.children.forEach(function (c) {
      if (vwIsPageMark(c) && (all || !inked)) { changed = true; return; }
      if (!c.children && !VW_BLANK[c.type]) inked = true;
      var d = out(c);
      if (d !== c) changed = true;
      kept.push(d);
    });
    return changed ? Object.assign({}, node, { children: kept }) : node;
  }
  return elements.map(out);
}

function vwInheritRefs(prev, props, key) {
  var own = props[key] || [];
  var inherited = (prev && prev[key] || []).filter(function (ref) {
    return !own.some(function (mine) { return mine.type === ref.type; });
  });
  if (inherited.length) props[key] = own.concat(inherited);
}

function vwSplitAsWordDid(renderer) {
  var createPageElement = renderer.createPageElement;
  var renderHeaderFooter = renderer.renderHeaderFooter;
  var renderRun = renderer.renderRun;
  var renderParagraph = renderer.renderParagraph;
  var renderTableRow = renderer.renderTableRow;
  var renderText = renderer.renderText;

  renderer.splitBySection = function (elements, bodyProps) {
    vwPages.props = [];
    vwPages.fields = [];
    vwPages.notes = vwNoteBook();
    var app = this.document.extendedPropsPart;
    vwPages.wordPages = (app && app.props && app.props.pages) || 0;
    vwPages.split = elements.some(vwHasPageMark);
    if (vwPages.measure === null) vwPages.measure = !vwPages.split;
    return splitPieces(this, elements, bodyProps);
  };

  renderer.groupByPageBreaks = function (pieces) {
    var pages = [], page = [];
    pieces.forEach(function (piece) {
      page.push(piece);
      if (piece.pageBreak) { pages.push(page); page = []; }
    });
    if (page.length) pages.push(page);
    return pages;
  };

  renderer.createPageElement = function (className, props, docStyle) {
    vwPages.props.push(props);
    return createPageElement.call(this, className, props, docStyle);
  };

  renderer.renderHeaderFooter = function () {
    var outer = vwPages.fields;
    vwPages.fields = [];
    try {
      return renderHeaderFooter.apply(this, arguments);
    } finally {
      vwPages.fields = outer;
    }
  };

  renderer.renderRun = function (run) {
    if (run.fieldRun) trackField(run);
    var span = renderRun.call(this, run);
    var field = vwPages.fields[vwPages.fields.length - 1];
    if (span && field && field.showing && field.kind) {
      span.setAttribute("data-vw-field", field.id + " " + field.kind + " " + field.format);
    }
    return span;
  };

  renderer.renderParagraph = function (para) {
    var el = vwMarkPlace(renderParagraph.call(this, para), para);
    if (el && vwPages.measure && para.vwP !== undefined) {
      el.setAttribute("data-vw-p", para.vwP);
      var keep = vwKeeps(this, para);
      if (keep) el.setAttribute("data-vw-keep", keep);
    }
    return el;
  };
  renderer.renderTableRow = function (row) {
    var el = vwMarkPlace(renderTableRow.call(this, row), row);
    if (el && vwPages.measure && row.vwR !== undefined) {
      el.setAttribute("data-vw-r", row.vwR);
      if (vwIsHeaderRow(row)) el.setAttribute("data-vw-head", "");
    }
    return el;
  };
  renderer.renderText = function (text) {
    var node = renderText.call(this, text);
    if (node && vwPages.measure && text.vwT !== undefined) node.vwT = text.vwT;
    return node;
  };

  renderer.refreshTabStops = function () {
    if (this.options.experimental) vwPages.tabs = { tabs: this.currentTabs, size: this.defaultTabSize };
  };

  var renderFootnoteReference = renderer.renderFootnoteReference;
  renderer.renderFootnoteReference = function (ref) {
    var sup = renderFootnoteReference.call(this, ref), notes = vwPages.notes;
    var n = ref.id != null && notes.of[ref.id];
    if (!n) {
      n = ++notes.count;
      if (ref.id != null) notes.of[ref.id] = n;
    }
    if (sup && sup.setAttribute) {
      sup.textContent = String(n);
      sup.setAttribute("data-vw-note", "");
    }
    return sup;
  };
  var renderNotes = renderer.renderNotes;
  renderer.renderNotes = function (ids, map) {
    if (map !== this.footnoteMap) return renderNotes.apply(this, arguments);
    var notes = vwPages.notes;
    var fresh = ids.filter(function (id) {
      if (id == null) return true;
      if (notes.listed[id]) return false;
      notes.listed[id] = true;
      return true;
    });
    var list = renderNotes.call(this, fresh, map);
    if (list && list.setAttribute) {
      var shown = fresh.filter(function (id) { return map[id]; });
      for (var i = 0; i < list.children.length && i < shown.length; i++) {
        if (notes.of[shown[i]]) list.children[i].value = notes.of[shown[i]];
      }
      list.setAttribute("data-vw-notes", "");
    }
    return list;
  };

  var createSectionContent = renderer.createSectionContent;
  renderer.createSectionContent = function (props) {
    var article = createSectionContent.call(this, props);
    if (article) article.vwProps = props;
    return article;
  };

  vwPages.pageRoom = function (props, index) {
    var cache = props.vwRoom || (props.vwRoom = {}), key = index % 2;
    if (cache[key] !== undefined) return cache[key];
    var wrap = document.querySelector(".docx-wrapper");
    if (!wrap) return 0;
    var page = createPageElement.call(renderer, renderer.className, props, {});
    page.style.position = "absolute";
    page.style.visibility = "hidden";
    wrap.appendChild(page);
    try {
      renderer.renderHeaderFooter(props.headerRefs, props, index, false, page);
      renderer.renderHeaderFooter(props.footerRefs, props, index, false, page);
      var style = getComputedStyle(page);
      cache[key] = (parseFloat(style.minHeight) || 0) - (parseFloat(style.paddingTop) || 0) -
        (parseFloat(style.paddingBottom) || 0) - vwOverMargin(page.querySelector(":scope > header")) -
        vwOverMargin(page.querySelector(":scope > footer"));
    } finally {
      wrap.removeChild(page);
    }
    return cache[key];
  };
}

function vwLineUpTabs() {
  var held = vwPages.tabs;
  vwPages.tabs = null;
  if (!held) return;
  try {
    var paras = [], of = new Map();
    held.tabs.forEach(function (tab) {
      var p = tab.span.isConnected && tab.span.closest("p");
      if (!p) return;
      var group = of.get(p);
      if (!group) {
        group = { p: p, tabs: [] };
        of.set(p, group);
        paras.push(group);
      }
      group.tabs.push(tab);
    });
    var ratio = vwPointsPerPixel();
    for (var k = 0; paras.length; k++) {
      var plans = paras.map(function (group) { return vwTabPlan(group.tabs[k], group.p, held.size, ratio); });
      plans.forEach(function (plan, i) { if (plan) vwSetTab(paras[i].tabs[k].span, plan); });
      paras = paras.filter(function (group) { return group.tabs.length > k + 1; });
    }
  } catch (e) {
  }
}

function vwPointsPerPixel() {
  var probe = document.createElement("div");
  probe.style.width = "100pt";
  document.body.appendChild(probe);
  var ratio = 100 / probe.offsetWidth;
  document.body.removeChild(probe);
  return ratio;
}

var VW_TAB_LEFT = { pos: 0, leader: "none", style: "left" };

function vwTabPlan(tab, p, size, ratio) {
  var span = tab.span, box = span.getBoundingClientRect(), para = p.getBoundingClientRect();
  var margin = parseFloat(getComputedStyle(p).marginLeft);
  var stops = tab.stops && tab.stops.length > 0 ? tab.stops.map(function (s) {
    return { pos: parseFloat(s.position), leader: s.leader, style: s.style };
  }).sort(function (a, b) { return a.pos - b.pos; }) : [VW_TAB_LEFT];
  var width = para.width * ratio, step = parseFloat(size);
  for (var pos = stops[stops.length - 1].pos + step; pos < width && stops.length < 50; pos += step) {
    stops.push({ pos: pos, leader: "none", style: "left" });
  }
  var at = (box.left - (para.left + margin)) * ratio, stop = null;
  for (var i = 0; i < stops.length && !stop; i++) {
    if (stops[i].style !== "clear" && stops[i].pos > at) stop = stops[i];
  }
  if (!stop) return null;
  if (stop.style !== "right" && stop.style !== "center") return { stop: stop, spacing: stop.pos - at };
  var tabs = [].slice.call(p.querySelectorAll("." + span.className)), next = tabs[tabs.indexOf(span) + 1];
  var range = document.createRange();
  range.setStart(span, 1);
  if (next) range.setEndBefore(next); else range.setEndAfter(p);
  var text = range.getBoundingClientRect(), share = stop.style === "center" ? 0.5 : 1;
  if (!text.width && !text.height) {
    return { stop: stop, spacing: Math.max(0, stop.pos - (box.right - (para.left - margin)) * ratio) };
  }
  return { stop: stop, spacing: stop.pos - (text.left + share * text.width - (para.left - margin)) * ratio };
}

function vwSetTab(span, plan) {
  span.innerHTML = "&nbsp;";
  span.style.textDecoration = "inherit";
  span.style.wordSpacing = plan.spacing.toFixed(0) + "pt";
  switch (plan.stop.leader) {
    case "dot":
    case "middleDot":
      span.style.textDecoration = "underline";
      span.style.textDecorationStyle = "dotted";
      break;
    case "hyphen":
    case "heavy":
    case "underscore":
      span.style.textDecoration = "underline";
  }
}

function vwKeeps(renderer, para) {
  var next = para.keepNext, lines = para.keepLines;
  var style = renderer.findStyle(para.styleName);
  for (var depth = 0; style && depth < 20; depth++) {
    var props = style.paragraphProps || {};
    if (next === undefined) next = props.keepNext;
    if (lines === undefined) lines = props.keepLines;
    style = style.basedOn ? renderer.findStyle(style.basedOn) : null;
  }
  return (next ? "n" : "") + (lines ? "l" : "");
}

function vwMarkPlace(el, node) {
  var spot = node.vwPlace;
  if (el && spot && spot.canTurn) el.setAttribute("data-vw-place", spot.n + " " + spot.stretch);
  return el;
}

function vwRunless(node) {
  return !(node.children || []).some(function (c) {
    return c.type === "run" || !vwRunless(c);
  });
}

function vwIsHeaderRow(row) {
  return "isHeader" in row && row.isHeader !== false;
}

function vwRowStartsRunless(row) {
  var cell = (row.children || [])[0];
  var first = cell && (cell.children || [])[0];
  return !!first && first.type === "paragraph" && vwRunless(first);
}

var VW_FIELD = /^\s*(PAGE|NUMPAGES|SECTIONPAGES)\b(.*)$/i;
var VW_FIELD_FORMATS = {
  roman: "lowerRoman", ROMAN: "upperRoman", Roman: "upperRoman",
  alphabetic: "lowerLetter", ALPHABETIC: "upperLetter", Alphabetic: "upperLetter",
  arabic: "decimal", Arabic: "decimal", ARABIC: "decimal", ArabicDash: "numberInDash"
};

function trackField(run) {
  var fields = vwPages.fields;
  (run.children || []).forEach(function (part) {
    var top = fields[fields.length - 1];
    if (part.type === "instruction") {
      if (top && !top.showing) top.code += part.text || "";
    } else if (part.type === "complexField") {
      if (part.charType === "begin") {
        fields.push({ id: ++vwPages.nextField, code: "", showing: false });
      } else if (part.charType === "separate" && top) {
        top.showing = true;
        var said = VW_FIELD.exec(top.code);
        if (said) {
          var as = /\\\*\s*(\w+)/.exec(said[2]);
          top.kind = said[1].toUpperCase();
          top.format = (as && VW_FIELD_FORMATS[as[1]]) || "-";
        }
      } else if (part.charType === "end") {
        fields.pop();
      }
    }
  });
}

function splitPieces(renderer, elements, bodyProps) {
  var sections = [], held = [];
  elements.forEach(function (el) {
    held.push(el);
    if (el.type === "paragraph" && el.sectionProps) {
      sections.push({ props: el.sectionProps, elements: held });
      held = [];
    }
  });
  sections.push({ props: bodyProps, elements: held });

  var pieces = [], piece = null, inked = false;
  var places = 0, stretch = 0, open = [];
  function start(props) {
    piece = { sectProps: props, elements: [], pageBreak: false };
    pieces.push(piece);
  }
  function place(node, canTurn) {
    var spot = node.vwPlace = { n: places++, stretch: stretch, canTurn: canTurn };
    if (canTurn) open.push(spot);
    return !!(vwPages.turnAt && vwPages.turnAt[spot.n]);
  }
  function inkSeen() {
    stretch++;
    open = [];
  }
  function pageEnds() {
    open.forEach(function (spot) { spot.canTurn = false; });
    open = [];
  }
  function turn(hard) {
    if (!inked && !hard) return;
    pageEnds();
    piece.pageBreak = true;
    start(piece.sectProps);
    inked = false;
  }
  function add(el) {
    piece.elements.push(el);
    if (el.type === "paragraph" || el.type === "table" ? vwHasInk(el) : !VW_BLANK[el.type]) {
      inked = true;
      if (el.type !== "table") inkSeen();
    }
  }
  function rows(table, list) { return Object.assign({}, table, { children: list }); }

  sections.forEach(function (s, si) {
    var prev = si > 0 ? sections[si - 1].props : null;
    var runsOn = false;
    if (prev) {
      vwInheritRefs(prev, s.props, "headerRefs");
      vwInheritRefs(prev, s.props, "footerRefs");
      runsOn = (s.props.type === "continuous" || s.props.type === "nextColumn") &&
        !renderer.isPageBreakSection(prev, s.props);
      if (!runsOn) {
        pageEnds();
        piece.pageBreak = true;
        inked = false;
      }
    }
    start(s.props);

    var columned = vwColumns(s.props) > 1;
    var elements = columned || (runsOn && vwColumns(prev) > 1)
      ? vwColumnMarksOut(s.elements, columned) : s.elements;
    var placing = !columned;

    elements.forEach(function (el) {
      if (el.type === "table") {
        var all = el.children || [], heads = [], from = 0;
        while (heads.length < all.length && vwIsHeaderRow(all[heads.length])) heads.push(all[heads.length]);
        for (var r = 0; r < all.length; r++) {
          var above = r > Math.max(from, heads.length) || (!from && inked);
          var turns = placing && !vwIsHeaderRow(all[r]) && vwRowStartsRunless(all[r]) &&
            place(all[r], above);
          if (r === from && (turns || vwHasPageMark(all[r]))) {
            turn();
          } else if (turns || vwHasPageMark(all[r])) {
            add(rows(el, (from >= heads.length && from ? heads : []).concat(all.slice(from, r))));
            turn();
            from = r;
          }
          if (vwHasInk(all[r])) inkSeen();
        }
        add(from ? rows(el, (from >= heads.length ? heads : []).concat(all.slice(from))) : el);
        return;
      }
      if (el.type !== "paragraph") { add(el); return; }
      if (placing && vwRunless(el) && place(el, inked)) turn();

      var style = renderer.findStyle(el.styleName);
      if (el.pageBreakBefore || (style && style.paragraphProps && style.paragraphProps.pageBreakBefore)) {
        turn();
      }

      var part = Object.assign({}, el, { children: [] });
      (el.children || []).forEach(function (run) {
        var rest = run;
        var at = run.type === "run" && run.children ? run.children.findIndex(isBreak) : -1;
        while (at >= 0) {
          if (at > 0) part.children.push(Object.assign({}, rest, { children: rest.children.slice(0, at) }));
          var hard = rest.children[at].break === "page";
          var before = vwHasInk(part);
          if (before || hard) {
            add(part);
            turn(hard);
            part = Object.assign({}, el, { children: [] });
            if (before) part.className = [el.className, "vw-cont"].filter(Boolean).join(" ");
          } else {
            turn();
          }
          rest = Object.assign({}, rest, { children: rest.children.slice(at + 1) });
          at = rest.children.findIndex(isBreak);
        }
        if (rest === run || rest.children.length) part.children.push(rest);
      });
      add(part);
    });
  });

  pageEnds();

  var kept = [];
  pieces.forEach(function (p) {
    if (p.elements.length || !kept.length) kept.push(p);
    else if (p.pageBreak) kept[kept.length - 1].pageBreak = true;
  });
  return kept;
}

function isBreak(node) {
  return node.type === "break" && (node.break === "page" || node.break === "lastRenderedPageBreak");
}

function vwFindMissingPages() {
  if (vwPages.measure) return vwMeasurePages();
  var sheets = vwSheets(), missing = vwPages.wordPages - sheets.length;
  if (!vwPages.split || !(missing > 0)) return Promise.resolve(false);
  document.body.getBoundingClientRect();
  return document.fonts.ready.then(function () {
    vwLineUpTabs();
    vwPages.turnAt = vwPlaceTurns([].map.call(sheets, vwMeasureSheet), missing);
    return !!vwPages.turnAt;
  });
}

var VW_OVERFLOW = 1.02;

function vwPlaceTurns(plan, missing) {
  var given = 0, turnAt = null;
  while (given < missing) {
    var best = null, most = VW_OVERFLOW;
    plan.forEach(function (s) {
      var over = s.height / ((s.turns.length + 1) * s.room);
      if (s.room > 0 && !s.full && over > most) { best = s; most = over; }
    });
    if (!best) break;
    var turns = vwTurnsOn(best, best.turns.length + 2);
    if (turns) {
      best.turns = turns;
      given++;
    } else {
      best.full = true;
    }
  }
  plan.forEach(function (s) {
    s.turns.forEach(function (spot) { (turnAt = turnAt || {})[spot.n] = true; });
  });
  return turnAt;
}

function vwTurnsOn(sheet, pages) {
  var turns = [], next = 0, above = 0;
  for (var j = 1; j < pages; j++) {
    var want = j * sheet.height / pages, pick = null, stretch = 0;
    for (var r = next; r < sheet.places.length; r++) {
      for (var q = 0; q < sheet.places[r].length; q++) {
        var spot = sheet.places[r][q];
        if (spot.at - above < sheet.room / 2) continue;
        if (!pick || Math.abs(spot.at - want) < Math.abs(pick.at - want)) {
          pick = spot;
          stretch = r;
        }
      }
    }
    if (!pick) return null;
    turns.push(pick);
    above = pick.at;
    next = stretch + 1;
  }
  return turns;
}

function vwMeasureSheet(sheet) {
  var style = getComputedStyle(sheet);
  var padTop = parseFloat(style.paddingTop) || 0;
  var room = (parseFloat(style.minHeight) || 0) - padTop - (parseFloat(style.paddingBottom) || 0);
  var top = sheet.getBoundingClientRect().top + padTop, bottom = top;
  var blocks = sheet.querySelectorAll(":scope > article > *");
  for (var a = 0; a < blocks.length; a++) {
    bottom = Math.max(bottom, blocks[a].getBoundingClientRect().bottom);
  }
  var places = [], last = null;
  var marked = sheet.querySelectorAll(":scope > article [data-vw-place]");
  for (var m = 0; m < marked.length; m++) {
    var said = marked[m].getAttribute("data-vw-place").split(" ");
    var spot = { n: Number(said[0]), at: marked[m].getBoundingClientRect().top - top };
    if (said[1] === last) places[places.length - 1].push(spot);
    else places.push([spot]);
    last = said[1];
  }
  return { room: room, height: bottom - top, places: places, turns: [], full: false };
}

function vwNumberModel(doc) {
  var body = doc.documentPart && doc.documentPart.body;
  var model = { paras: [], rows: [], texts: {} };
  function walk(node, para, via) {
    (node.children || []).forEach(function (c) {
      if (c.type === "paragraph") {
        c.vwP = model.paras.length;
        var p = { node: c, top: node === body, texts: 0 };
        model.paras.push(p);
        walk(c, p, null);
        return;
      }
      if (c.type === "row") {
        c.vwR = model.rows.length;
        model.rows.push(c);
      }
      var through = para && node === para.node ? c : via;
      if (c.type === "text" && para) {
        c.vwT = para.node.vwP + "." + para.texts++;
        model.texts[c.vwT] = { node: c, holder: node, via: through, para: para };
      }
      walk(c, c.type === "table" ? null : para, through);
    });
  }
  if (body) walk(body, null, null);
  return model;
}

function vwPageMark() {
  return { type: "break", break: "lastRenderedPageBreak" };
}

function vwAddTurns(model, turns) {
  var cuts = [];
  turns.forEach(function (t) {
    if (t.p !== undefined) {
      var p = model.paras[t.p];
      if (p && p.top) (p.node.children = p.node.children || []).unshift({ type: "run", children: [vwPageMark()] });
    } else if (t.r !== undefined) {
      var cell = model.rows[t.r] && (model.rows[t.r].children || [])[0];
      if (cell) (cell.children = cell.children || []).unshift(vwPageMark());
    } else if (t.t !== undefined) {
      var at = model.texts[t.t];
      if (!at || !at.para.top) return;
      if (at.holder.type === "run" && at.holder === at.via) {
        var cut = cuts.filter(function (c) { return c.at === at; })[0];
        if (!cut) cuts.push(cut = { at: at, offsets: [] });
        cut.offsets.push(t.o);
      } else {
        var kids = at.para.node.children, i = kids.indexOf(at.via);
        if (i >= 0) kids.splice(i, 0, { type: "run", children: [vwPageMark()] });
      }
    }
  });
  cuts.forEach(function (cut) {
    var text = cut.at.node, value = text.text || "", parts = [], from = 0;
    cut.offsets.sort(function (a, b) { return a - b; }).forEach(function (o) {
      if (o <= from && parts.length) return;
      if (o > from) parts.push(Object.assign({}, text, { text: value.slice(from, o) }));
      parts.push(vwPageMark());
      from = o;
    });
    if (from < value.length) parts.push(Object.assign({}, text, { text: value.slice(from) }));
    var run = cut.at.holder.children, i = run.indexOf(text);
    if (i >= 0) run.splice.apply(run, [i, 1].concat(parts));
  });
}

var VW_MOST_ELEMENTS = 20000;

function vwMeasurePages() {
  if (document.querySelectorAll("#container *").length > VW_MOST_ELEMENTS) return Promise.resolve(false);
  document.body.getBoundingClientRect();
  return document.fonts.ready.then(function () {
    vwLineUpTabs();
    var turns = [], sheets = vwSheets();
    for (var i = 0; i < sheets.length; i++) vwSheetTurns(sheets[i], turns, i);
    vwPages.turns = turns.length ? turns : null;
    return !!vwPages.turns;
  });
}

var VW_SLACK = 0.5;

function vwOverMargin(el) {
  if (!el) return 0;
  var style = getComputedStyle(el);
  return Math.max(0, el.getBoundingClientRect().height +
    (parseFloat(style.marginTop) || 0) + (parseFloat(style.marginBottom) || 0));
}

function vwBlockTop(el) {
  return el.getBoundingClientRect().top - (parseFloat(getComputedStyle(el).marginTop) || 0);
}

function vwSheetTurns(sheet, turns, index) {
  var articles = sheet.querySelectorAll(":scope > article");
  if (!articles.length) return;
  for (var a = 0; a < articles.length; a++) {
    if ((parseInt(getComputedStyle(articles[a]).columnCount, 10) || 1) > 1) return;
  }
  var style = getComputedStyle(sheet);
  var room = (parseFloat(style.minHeight) || 0) - (parseFloat(style.paddingTop) || 0) -
    (parseFloat(style.paddingBottom) || 0) - vwOverMargin(sheet.querySelector(":scope > header")) -
    vwOverMargin(sheet.querySelector(":scope > footer"));
  if (!(room > 0)) return;
  var notes = vwNotesOn(sheet);

  var blocks = vwBlocks(sheet);
  var last = vwInked(blocks);

  var page = { top: articles[0].getBoundingClientRect().top, room: room, notes: notes };
  for (var b = 0; b < last; b++) {
    var memo = {};
    for (var guard = 0; guard < 1000; guard++) {
      if (vwFits(page, blocks[b].getBoundingClientRect().bottom)) break;
      var turn = blocks[b].tagName === "TABLE" ? vwTableTurn(blocks, b, page, memo)
        : blocks[b].hasAttribute("data-vw-p") ? vwParagraphTurn(blocks, b, page, memo) : null;
      if (!turn || turn.y <= page.top + VW_SLACK) break;
      turns.push(turn.at);
      var props = blocks[b].parentNode.vwProps || vwPages.props[index];
      if (props && vwPages.pageRoom) room = vwPages.pageRoom(props, index + turns.length);
      if (!(room > 0)) return;
      page = { top: turn.y, room: room, notes: notes };
    }
  }
}

function vwFits(page, bottom) {
  return bottom - page.top + vwNotesHeight(page.notes, page.top, bottom) <= page.room + VW_SLACK;
}

function vwNotesOn(sheet) {
  var list = sheet.querySelector(":scope > ol[data-vw-notes]");
  var refs = sheet.querySelectorAll(":scope > article sup[data-vw-note]");
  if (!list || !refs.length || list.children.length !== refs.length) return null;
  var items = list.children, box = list.getBoundingClientRect(), style = getComputedStyle(list);
  var tops = [].map.call(items, function (li) { return li.getBoundingClientRect().top; });
  var end = box.bottom - (parseFloat(style.paddingBottom) || 0) - (parseFloat(style.borderBottomWidth) || 0);
  var at = [], height = [];
  for (var i = 0; i < items.length; i++) {
    at.push(refs[i].getBoundingClientRect().top);
    height.push((i + 1 < items.length ? tops[i + 1] : end) - tops[i]);
  }
  var whole = box.height + (parseFloat(style.marginTop) || 0) + (parseFloat(style.marginBottom) || 0);
  return { at: at, height: height, frame: whole - (end - tops[0]) };
}

function vwNotesHeight(notes, top, bottom) {
  if (!notes) return 0;
  var sum = 0, any = false;
  for (var i = 0; i < notes.at.length; i++) {
    if (notes.at[i] >= top - VW_SLACK && notes.at[i] < bottom) {
      sum += notes.height[i];
      any = true;
    }
  }
  return any ? sum + notes.frame : 0;
}

function vwBlocks(sheet) {
  var articles = sheet.querySelectorAll(":scope > article"), blocks = [];
  for (var a = 0; a < articles.length; a++) blocks.push.apply(blocks, articles[a].children);
  return blocks;
}

function vwInked(blocks) {
  var last = blocks.length;
  while (last > 0 && vwBlank(blocks[last - 1])) last--;
  return last;
}

var VW_EMPTY = { SPAN: 1, A: 1, BR: 1, WBR: 1 };

function vwBlank(el) {
  if (el.tagName !== "P" || el.textContent) return false;
  var inside = el.getElementsByTagName("*");
  for (var i = 0; i < inside.length; i++) {
    if (!VW_EMPTY[inside[i].tagName]) return false;
  }
  var number = getComputedStyle(el, "::before").content;
  if (number && number !== "none" && number !== "normal" && number !== '""') return false;
  var style = getComputedStyle(el);
  if (style.display === "list-item" && (style.listStyleType !== "none" || style.listStyleImage !== "none")) return false;
  return !(parseFloat(style.borderTopWidth) || parseFloat(style.borderBottomWidth) ||
    parseFloat(style.borderLeftWidth) || parseFloat(style.borderRightWidth)) &&
    /^(transparent|rgba\(0, 0, 0, 0\))$/.test(style.backgroundColor);
}

function vwHideTails(sheets) {
  var ends = [];
  for (var i = 0; i < sheets.length; i++) {
    var blocks = vwBlocks(sheets[i]), last = vwInked(blocks);
    if (last === blocks.length) continue;
    var style = getComputedStyle(sheets[i]);
    if (sheets[i].getBoundingClientRect().height > (parseFloat(style.minHeight) || 0) + VW_SLACK) {
      ends.push({ blocks: blocks, last: last });
    }
  }
  ends.forEach(function (end) {
    for (var b = end.last; b < end.blocks.length; b++) end.blocks[b].classList.add("vw-tail");
    if (end.last > 0) end.blocks[end.last - 1].classList.add("vw-page-end");
  });
}

function vwTurnBefore(blocks, b, page) {
  var from = b;
  while (from > 0) {
    var prev = blocks[from - 1];
    if ((prev.getAttribute("data-vw-keep") || "").indexOf("n") < 0) break;
    if (vwBlockTop(prev) <= page.top + VW_SLACK) { from = b; break; }
    from--;
  }
  var el = blocks[from];
  if (vwBlockTop(el) <= page.top + VW_SLACK) return null;
  var row = el.tagName === "TABLE" ? vwRows(el)[0] : null;
  if (el.hasAttribute("data-vw-p")) return { at: { p: Number(el.getAttribute("data-vw-p")) }, y: vwBlockTop(el) };
  if (row && row.hasAttribute("data-vw-r")) return { at: { r: Number(row.getAttribute("data-vw-r")) }, y: vwBlockTop(el) };
  return null;
}

function vwRows(table) {
  return table.querySelectorAll(":scope > tr, :scope > tbody > tr, :scope > thead > tr");
}

function vwTableTurn(blocks, b, page, memo) {
  if (!memo.rows) {
    var rows = vwRows(blocks[b]), heads = 0, headHeight = 0;
    while (heads < rows.length && rows[heads].hasAttribute("data-vw-head")) {
      headHeight += rows[heads].getBoundingClientRect().height;
      heads++;
    }
    memo.rows = rows;
    memo.boxes = [].map.call(rows, function (row) { return row.getBoundingClientRect(); });
    memo.heads = heads;
    memo.lift = heads ? headHeight : 0;
    memo.from = 0;
  }
  for (var r = memo.from; r < memo.rows.length; r++) {
    var box = memo.boxes[r];
    if (box.top < page.top - VW_SLACK || vwFits(page, box.bottom)) continue;
    var at = r;
    if (r <= memo.heads) {
      if (vwBlockTop(blocks[b]) > page.top + VW_SLACK) return vwTurnBefore(blocks, b, page);
      at = memo.heads + 1;
    } else if (box.top - memo.lift <= page.top + VW_SLACK) {
      at = r + 1;
    }
    if (memo.lift >= page.room) return null;
    if (at >= memo.rows.length || !memo.rows[at].hasAttribute("data-vw-r")) return null;
    memo.from = at;
    return { at: { r: Number(memo.rows[at].getAttribute("data-vw-r")) }, y: memo.boxes[at].top - memo.lift };
  }
  return null;
}

function vwParagraphTurn(blocks, b, page, memo) {
  var el = blocks[b], lines = memo.lines || (memo.lines = vwLineBoxes(el));
  if (!lines.length) return vwBlockTop(el) <= page.top + VW_SLACK ? null : vwTurnBefore(blocks, b, page);
  var first = memo.first || 0;
  while (first < lines.length && lines[first].top < page.top - VW_SLACK) first++;
  memo.first = first;
  var cut = first;
  while (cut < lines.length && vwFits(page, lines[cut].bottom)) cut++;
  if (cut >= lines.length) return null;
  var atTop = (first ? lines[first].top : vwBlockTop(el)) <= page.top + VW_SLACK;
  if ((el.getAttribute("data-vw-keep") || "").indexOf("l") >= 0) {
    cut = first;
  } else {
    if (lines.length - cut === 1 && cut - first >= 2) cut--;
    if (cut - first < 2) cut = first;
  }
  if (cut === first) {
    if (!first && !atTop) return vwTurnBefore(blocks, b, page);
    cut = first + 1;
    while (cut < lines.length && vwFits(page, lines[cut].bottom)) cut++;
  }
  for (var k = cut; k < lines.length; k++) {
    if (!lines[k].texts.length) continue;
    var piece = lines[k].texts[0];
    return { at: { t: piece.node.vwT, o: vwLineStart(piece) }, y: lines[k].top };
  }
  return null;
}

function vwLineBoxes(p) {
  var lines = [], line = null, range = document.createRange();
  function add(r, piece) {
    if (!(r.height > 0)) return;
    if (!line || r.top >= line.inkTop + Math.min(line.inkBottom - line.inkTop, r.height) / 2) {
      line = { inkTop: r.top, inkBottom: r.bottom, texts: [] };
      lines.push(line);
    } else {
      line.inkTop = Math.min(line.inkTop, r.top);
      line.inkBottom = Math.max(line.inkBottom, r.bottom);
    }
    if (piece) line.texts.push(piece);
  }
  var walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
  for (var node = walker.nextNode(); node; node = walker.nextNode()) {
    if (node.nodeType === 3) {
      if (!node.nodeValue) continue;
      range.selectNodeContents(node);
      var rects = range.getClientRects();
      for (var i = 0; i < rects.length; i++) {
        add(rects[i], node.vwT !== undefined ? { node: node, rect: rects[i] } : null);
      }
    } else if (/^(img|svg|canvas|br)$/i.test(node.tagName)) {
      add(node.getBoundingClientRect(), null);
    }
  }
  if (lines.length) {
    var box = p.getBoundingClientRect(), style = getComputedStyle(p);
    var above = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.borderTopWidth) || 0);
    var below = (parseFloat(style.paddingBottom) || 0) + (parseFloat(style.borderBottomWidth) || 0);
    for (var k = 0; k < lines.length; k++) {
      lines[k].top = k ? (lines[k - 1].inkBottom + lines[k].inkTop) / 2 - above : box.top;
      lines[k].bottom = k + 1 < lines.length ? (lines[k].inkBottom + lines[k + 1].inkTop) / 2 + below : box.bottom;
    }
  }
  return lines;
}

function vwLineStart(piece) {
  var node = piece.node, text = node.nodeValue, range = document.createRange();
  var mid = piece.rect.top + piece.rect.height / 2;
  function onOrAfter(o) {
    for (var k = o; k < text.length; k++) {
      range.setStart(node, k);
      range.setEnd(node, k + 1);
      var r = range.getBoundingClientRect();
      if (r.height > 0) return r.bottom > mid;
    }
    return true;
  }
  var lo = 0, hi = text.length;
  while (lo < hi) {
    var m = (lo + hi) >> 1;
    if (onOrAfter(m)) hi = m; else lo = m + 1;
  }
  var code = text.charCodeAt(lo);
  return code >= 0xDC00 && code <= 0xDFFF && lo > 0 ? lo - 1 : lo;
}

function vwNumberPages(sheets) {
  if (!(vwPages.split || vwPages.measure) || sheets.length !== vwPages.props.length) return;
  var numbers = [], sectionOf = [], sectionPages = [];
  for (var i = 0; i < sheets.length; i++) {
    var props = vwPages.props[i];
    var first = i === 0 || props !== vwPages.props[i - 1];
    var start = props.pageNumber && props.pageNumber.start;
    numbers.push(first && isFinite(start) && start !== null ? start : (i ? numbers[i - 1] + 1 : 1));
    if (first) sectionPages.push(0);
    sectionOf.push(sectionPages.length - 1);
    sectionPages[sectionPages.length - 1]++;
  }

  for (var p = 0; p < sheets.length; p++) {
    var own = vwPages.props[p].pageNumber;
    var seen = {};
    var marked = sheets[p].querySelectorAll("[data-vw-field]");
    for (var m = 0; m < marked.length; m++) {
      var said = marked[m].getAttribute("data-vw-field").split(" ");
      var text = "";
      if (!seen[said[0]]) {
        seen[said[0]] = true;
        var value = said[1] === "PAGE" ? numbers[p]
          : said[1] === "NUMPAGES" ? sheets.length : sectionPages[sectionOf[p]];
        var format = said[2] !== "-" ? said[2] : said[1] === "PAGE" && own ? own.format : "";
        text = vwFormatPageNumber(value, format);
      }
      writeText(marked[m], text);
    }
  }
}

function writeText(el, text) {
  var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  var node, done = false;
  while ((node = walker.nextNode())) {
    node.nodeValue = done ? "" : text;
    done = true;
  }
}

function vwFormatPageNumber(n, format) {
  switch (format) {
    case "lowerRoman": return roman(n).toLowerCase();
    case "upperRoman": return roman(n);
    case "lowerLetter": return letters(n).toLowerCase();
    case "upperLetter": return letters(n);
    case "numberInDash": return "- " + n + " -";
    default: return String(n);
  }
}

function roman(n) {
  if (n < 1 || n > 3999) return String(n);
  var out = "", marks = [[1000, "M"], [900, "CM"], [500, "D"], [400, "CD"], [100, "C"],
    [90, "XC"], [50, "L"], [40, "XL"], [10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"]];
  marks.forEach(function (m) {
    while (n >= m[0]) { out += m[1]; n -= m[0]; }
  });
  return out;
}

function letters(n) {
  if (n < 1) return String(n);
  var c = String.fromCharCode(65 + (n - 1) % 26);
  return new Array(Math.floor((n - 1) / 26) + 2).join(c);
}

var vwPageSent = 0;
var vwPageAskedFor = null;
var vwPageFrame = 0;

function vwSheets() {
  return document.querySelectorAll(".docx-wrapper > section.docx");
}

function vwSheetTop(sheet) {
  return sheet.getBoundingClientRect().top / (vwPages.rectScale || 1);
}

function vwPageOnScreen(sheets, vv) {
  if (vv.pageTop <= 1) return 1;
  if (vv.pageTop + vv.height >= document.documentElement.scrollHeight - 1) return sheets.length;
  var middle = vv.pageTop + vv.height / 2;
  var lo = 0, hi = sheets.length - 1, at = 0;
  while (lo <= hi) {
    var mid = (lo + hi) >> 1;
    if (vwSheetTop(sheets[mid]) + window.scrollY <= middle) { at = mid; lo = mid + 1; } else { hi = mid - 1; }
  }
  return at + 1;
}

function vwReportPage() {
  var sheets = vwSheets();
  var vv = window.visualViewport;
  if (!vwPages.settled || sheets.length < 2 || !vv || !vv.height) return;
  if (vwPageAskedFor && Math.abs(vv.pageTop - vwPageAskedFor.top) > 1) vwPageAskedFor = null;
  var n = vwPageAskedFor ? vwPageAskedFor.n : vwPageOnScreen(sheets, vv);
  if (n !== vwPageSent && vwFind.post("page " + n + " " + sheets.length)) vwPageSent = n;
}

function vwSchedulePageReport() {
  if (vwPageFrame) return;
  vwPageFrame = requestAnimationFrame(function () { vwPageFrame = 0; vwReportPage(); });
}

function vwGoToPage(n, options) {
  var sheets = vwSheets();
  if (!(n >= 1 && n <= sheets.length)) return;
  var sheet = sheets[n - 1];
  var vv = window.visualViewport;
  var tall = sheet.getBoundingClientRect().height / (vwPages.rectScale || 1);
  sheet.scrollIntoView({ block: vv && tall <= vv.height / 2 ? "center" : "start",
    behavior: options?.behavior || "instant" });
  vwPageAskedFor = vv ? { n: n, top: vv.pageTop } : null;
  vwReportPage();
}

function vwFollowPages() {
  if (vwSheets().length < 2) return;
  window.addEventListener("scroll", vwSchedulePageReport, { passive: true });
  if (window.visualViewport) {
    window.visualViewport.addEventListener("scroll", vwSchedulePageReport, { passive: true });
    window.visualViewport.addEventListener("resize", vwSchedulePageReport, { passive: true });
  }
  vwReportPage();
}
