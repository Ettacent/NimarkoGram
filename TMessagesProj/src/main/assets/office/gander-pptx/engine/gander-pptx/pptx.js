/* Modifications Copyright (C) 2026 Ettacent */


var DRAWINGML = "http://schemas.openxmlformats.org/drawingml/2006/main";
var PRESENTATIONML = "http://schemas.openxmlformats.org/presentationml/2006/main";

var vwOpenPackage = JSZip.prototype.load;
JSZip.prototype.load = function () {
  var zip = vwOpenPackage.apply(this, arguments);
  if (!zip.file("docProps/app.xml")) zip.file("docProps/app.xml", "<Properties/>");
  var presentation = zip.file("ppt/presentation.xml");
  var sized = presentation && sizeWhereNoneIsGiven(presentation.asText());
  if (sized && sized !== presentation.asText()) zip.file("ppt/presentation.xml", sized);
  zip.file(/^ppt\/(slides|slideLayouts|slideMasters)\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var drawable = bulletsAPhoneDraws(breaksPptxjsKeeps(xml));
    if (drawable !== xml) zip.file(part.name, drawable);
  });
  zip.file(/^ppt\/diagrams\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var drawable = bulletsAPhoneDraws(xml);
    if (drawable !== xml) zip.file(part.name, drawable);
  });
  var designs = {};
  zip.file(/^ppt\/slides\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var styled = styleTheDesignGives(zip, part.name, xml, designs);
    if (styled !== xml) zip.file(part.name, styled);
  });
  zip.file(/^ppt\/(slides|slideLayouts|slideMasters)\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var marked = placeTheDesignGives(zip, part.name, linesLeftWhole(zip, part.name, xml, designs), designs);
    if (marked !== xml) zip.file(part.name, marked);
  });
  return zip;
};

function sizeWhereNoneIsGiven(xml) {
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var root = doc.documentElement;
  var defaults = childrenNamed(root, "defaultTextStyle")[0];
  if (!defaults) defaults = root.appendChild(doc.createElementNS(PRESENTATIONML, "p:defaultTextStyle"));
  var changed = false;
  for (var lvl = 1; lvl <= 9; lvl++) {
    var level = childrenNamed(defaults, "lvl" + lvl + "pPr")[0];
    if (!level) level = defaults.appendChild(doc.createElementNS(DRAWINGML, "a:lvl" + lvl + "pPr"));
    var props = childrenNamed(level, "defRPr")[0];
    if (!props) props = level.appendChild(doc.createElementNS(DRAWINGML, "a:defRPr"));
    if (!props.hasAttribute("sz")) {
      props.setAttribute("sz", "1800");
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

function pathsPptxjsDraws(xml) {
  if (!/(<\/a:path>|\/>)\s*<a:path\b/.test(xml) && !hasLoneSegment(xml)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var lists = doc.getElementsByTagNameNS(DRAWINGML, "pathLst");
  var changed = false;
  for (var i = 0; i < lists.length; i++) {
    var paths = childrenNamed(lists[i], "path");
    for (var j = 1; j < paths.length; j++) {
      onToGridOf(paths[0], paths[j]);
      while (paths[j].firstChild) paths[0].appendChild(paths[j].firstChild);
      lists[i].removeChild(paths[j]);
      changed = true;
    }
    var segments = paths.length ? childrenNamed(paths[0], "lnTo") : [];
    if (segments.length === 1) {
      paths[0].insertBefore(segments[0].cloneNode(true), segments[0].nextSibling);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

function hasLoneSegment(xml) {
  var paths = xml.match(/<a:path\b[^>]*>[\s\S]*?<\/a:path>/g) || [];
  for (var i = 0; i < paths.length; i++) {
    if ((paths[i].match(/<a:lnTo\b/g) || []).length === 1) return true;
  }
  return false;
}

function childrenNamed(parent, name) {
  var found = [];
  for (var el = parent.firstElementChild; el; el = el.nextElementSibling) {
    if (el.localName === name) found.push(el);
  }
  return found;
}

function onToGridOf(first, path) {
  var sx = gridRatio(first, path, "w");
  var sy = gridRatio(first, path, "h");
  if (sx === 1 && sy === 1) return;
  var points = path.getElementsByTagNameNS(DRAWINGML, "pt");
  for (var i = 0; i < points.length; i++) {
    scaleAttr(points[i], "x", sx);
    scaleAttr(points[i], "y", sy);
  }
  var arcs = path.getElementsByTagNameNS(DRAWINGML, "arcTo");
  for (var k = 0; k < arcs.length; k++) {
    scaleAttr(arcs[k], "wR", sx);
    scaleAttr(arcs[k], "hR", sy);
  }
}

function gridRatio(first, path, side) {
  var to = parseFloat(first.getAttribute(side));
  var from = parseFloat(path.getAttribute(side));
  return to > 0 && from > 0 ? to / from : 1;
}

function scaleAttr(el, name, by) {
  var v = el.getAttribute(name);
  if (v !== null && /^-?\d+$/.test(v)) el.setAttribute(name, String(Math.round(Number(v) * by)));
}

function breaksPptxjsKeeps(xml) {
  if ((xml.match(/<a:br\b/g) || []).length < 2) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var paragraphs = doc.getElementsByTagNameNS(DRAWINGML, "p");
  var changed = false;
  for (var i = 0; i < paragraphs.length; i++) {
    var breaks = childrenNamed(paragraphs[i], "br");
    if (breaks.length > 1 && childrenNamed(paragraphs[i], "r").length) {
      paragraphs[i].insertBefore(breaks[0].cloneNode(true), breaks[0]);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

var SYMBOL_BULLETS = /<a:buFont\b[^>]*\stypeface="(wingdings|webdings|symbol)\b|<a:buChar\b[^>]*\schar="(&#|[\uE000-\uF8FF])/i;

function bulletsAPhoneDraws(xml) {
  if (!SYMBOL_BULLETS.test(xml)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var chars = doc.getElementsByTagNameNS(DRAWINGML, "buChar");
  for (var i = 0; i < chars.length; i++) {
    var font = childrenNamed(chars[i].parentNode, "buFont")[0];
    var was = chars[i].getAttribute("char");
    var now = vwSymbolBullet(font && font.getAttribute("typeface"), was);
    if (now !== was) {
      chars[i].setAttribute("char", now);
      changed = true;
    }
  }
  var fonts = [].slice.call(doc.getElementsByTagNameNS(DRAWINGML, "buFont"));
  for (var j = 0; j < fonts.length; j++) {
    if (vwIsSymbolFont(fonts[j].getAttribute("typeface"))) {
      fonts[j].parentNode.removeChild(fonts[j]);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

var dingbat_unicode = [];
[["Wingdings 2", VW_WINGDINGS_2], ["Wingdings 3", VW_WINGDINGS_3]].forEach(function (font) {
  for (var i = 0; i < font[1].length; i++) {
    dingbat_unicode.push({ f: font[0], code: 0x20 + i, unicode: font[1].charCodeAt(i) });
  }
});

var SETS_BOLD_OR_ITALIC = /<a:defRPr\b[^>]*\s[bi]="(1|true)"/;

function styleTheDesignGives(zip, name, xml, designs) {
  var layout = designPart(zip, relatedPart(zip, name, "slideLayout"), designs);
  var master = layout && designPart(zip, relatedPart(zip, layout.name, "slideMaster"), designs);
  if (!SETS_BOLD_OR_ITALIC.test(xml) && !(layout && layout.sets) && !(master && master.sets)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  for (var i = 0; i < shapes.length; i++) {
    var body = childrenNamed(shapes[i], "txBody")[0];
    if (!body) continue;
    var lists = [childrenNamed(body, "lstStyle")[0]];
    var behind = placeholdersBehind(shapes[i], layout, master);
    if (behind) {
      lists.push(inTextBody(behind.layout, "lstStyle"), inTextBody(behind.master, "lstStyle"),
        master && masterStyle(master.doc, behind.type));
    }
    var paragraphs = childrenNamed(body, "p");
    for (var j = 0; j < paragraphs.length; j++) {
      var pPr = childrenNamed(paragraphs[j], "pPr")[0];
      var level = "lvl" + ((parseInt(pPr && pPr.getAttribute("lvl"), 10) || 0) + 1) + "pPr";
      var runs = childrenNamed(paragraphs[j], "r").concat(childrenNamed(paragraphs[j], "fld"));
      for (var k = 0; k < runs.length; k++) {
        if (inherit(doc, runs[k], lists, level, "b")) changed = true;
        if (inherit(doc, runs[k], lists, level, "i")) changed = true;
      }
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

function inherit(doc, run, lists, level, attr) {
  var rPr = childrenNamed(run, "rPr")[0];
  if (rPr && rPr.hasAttribute(attr)) return false;
  var said = null;
  for (var i = 0; i < lists.length && said === null; i++) {
    var lvl = lists[i] && childrenNamed(lists[i], level)[0];
    var defaults = lvl && childrenNamed(lvl, "defRPr")[0];
    if (defaults && defaults.hasAttribute(attr)) said = defaults.getAttribute(attr);
  }
  if (said !== "1" && said !== "true") return false;
  if (!rPr) {
    rPr = doc.createElementNS(DRAWINGML, (run.prefix ? run.prefix + ":" : "") + "rPr");
    run.insertBefore(rPr, run.firstChild);
  }
  rPr.setAttribute(attr, "1");
  return true;
}

function designPart(zip, name, designs) {
  if (!name) return null;
  if (!(name in designs)) {
    var file = zip.file(name);
    var xml = file && file.asText();
    var doc = xml && new DOMParser().parseFromString(xml, "application/xml");
    designs[name] = doc && !doc.getElementsByTagName("parsererror").length
      ? { name: name, doc: doc, sets: SETS_BOLD_OR_ITALIC.test(xml), unwraps: SETS_NO_WRAP.test(xml) }
      : null;
  }
  return designs[name];
}

function relatedPart(zip, name, type) {
  var slash = name.lastIndexOf("/");
  var rels = zip.file(name.slice(0, slash) + "/_rels/" + name.slice(slash + 1) + ".rels");
  var rel = rels && new RegExp('<Relationship\\b[^>]*Type="[^"]*/' + type + '"[^>]*>').exec(rels.asText());
  var target = rel && /Target="([^"]+)"/.exec(rel[0]);
  if (!target) return null;
  var path = target[1].charAt(0) === "/" ? [] : name.slice(0, slash).split("/");
  target[1].split("/").forEach(function (step) {
    if (step === "..") path.pop();
    else if (step && step !== ".") path.push(step);
  });
  return path.join("/");
}

function placeholderOf(sp) {
  var ph = sp.getElementsByTagNameNS(PRESENTATIONML, "ph")[0];
  return ph ? { type: ph.getAttribute("type") || "obj", idx: ph.getAttribute("idx") } : null;
}

function placeholdersBehind(sp, layout, master) {
  var ph = placeholderOf(sp);
  if (!ph) return null;
  var inLayout = layout && placeholderIn(layout.doc, ph.type, ph.idx);
  var type = masterType(inLayout ? placeholderOf(inLayout).type : ph.type);
  return { layout: inLayout, master: master && placeholderIn(master.doc, type, null), type: type };
}

function placeholderIn(doc, type, idx) {
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  var sameType = null;
  for (var i = 0; i < shapes.length; i++) {
    var ph = placeholderOf(shapes[i]);
    if (!ph) continue;
    if (idx !== null && ph.idx === idx) return shapes[i];
    if (!sameType && ph.type === type) sameType = shapes[i];
  }
  return sameType;
}

function masterType(type) {
  return { title: "title", ctrTitle: "title", dt: "dt", ftr: "ftr", sldNum: "sldNum", hdr: "hdr" }[type] || "body";
}

function masterStyle(doc, type) {
  var styles = doc.getElementsByTagNameNS(PRESENTATIONML, "txStyles")[0];
  var name = type === "title" ? "titleStyle" : type === "body" ? "bodyStyle" : "otherStyle";
  return styles ? childrenNamed(styles, name)[0] : null;
}

function inTextBody(sp, name) {
  var body = sp && childrenNamed(sp, "txBody")[0];
  return body ? childrenNamed(body, name)[0] : null;
}

var SETS_NO_WRAP = /<a:bodyPr\b[^>]*\swrap="none"/;

function linesLeftWhole(zip, name, xml, designs) {
  var layout = /^ppt\/slides\//.test(name) ? designPart(zip, relatedPart(zip, name, "slideLayout"), designs) : null;
  var master = layout && designPart(zip, relatedPart(zip, layout.name, "slideMaster"), designs);
  if (!SETS_NO_WRAP.test(xml) && !(layout && layout.unwraps) && !(master && master.unwraps)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  for (var i = 0; i < shapes.length; i++) {
    var behind = placeholdersBehind(shapes[i], layout, master);
    var chain = [shapes[i]].concat(behind ? [behind.layout, behind.master] : []);
    var wrap = null;
    for (var j = 0; j < chain.length && wrap === null; j++) {
      var bodyPr = inTextBody(chain[j], "bodyPr");
      if (bodyPr && bodyPr.hasAttribute("wrap")) wrap = bodyPr.getAttribute("wrap");
    }
    var nv = childrenNamed(shapes[i], "nvSpPr")[0];
    var props = nv && childrenNamed(nv, "cNvPr")[0];
    if (wrap === "none" && props) {
      props.setAttribute("id", "nowrap-" + props.getAttribute("id"));
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

var PLACED_BY_DESIGN = /<p:spPr\b[^>]*>\s*<a:(prstGeom|custGeom)\b/;

function placeTheDesignGives(zip, name, xml, designs) {
  if (!PLACED_BY_DESIGN.test(xml)) return xml;
  var layout = /^ppt\/slides\//.test(name) ? designPart(zip, relatedPart(zip, name, "slideLayout"), designs) : null;
  var master = designPart(zip, relatedPart(zip, layout ? layout.name : name, "slideMaster"), designs);
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  for (var i = 0; i < shapes.length; i++) {
    var spPr = childrenNamed(shapes[i], "spPr")[0];
    var first = spPr && spPr.firstElementChild;
    if (!first || (first.localName !== "prstGeom" && first.localName !== "custGeom")) continue;
    var behind = placeholdersBehind(shapes[i], layout, master);
    var place = behind && (placeOf(behind.layout) || placeOf(behind.master));
    if (!place) continue;
    spPr.insertBefore(doc.importNode(place, true), first);
    changed = true;
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

function placeOf(sp) {
  var spPr = sp && childrenNamed(sp, "spPr")[0];
  return spPr ? childrenNamed(spPr, "xfrm")[0] : null;
}

function spacesThatBreak(root) {
  var blocks = root.querySelectorAll(".text-block");
  for (var i = 0; i < blocks.length; i++) {
    var walker = document.createTreeWalker(blocks[i], NodeFilter.SHOW_TEXT);
    for (var node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.data.indexOf("\u00a0") !== -1) node.data = node.data.replace(/\u00a0/g, " ");
    }
  }
}

function regularWeight(root) {
  var styles = root.querySelectorAll("style");
  for (var i = 0; i < styles.length; i++) {
    var rules = styles[i].sheet ? styles[i].sheet.cssRules : [];
    for (var j = 0; j < rules.length; j++) {
      if (rules[j].style && rules[j].style.fontWeight === "100") rules[j].style.fontWeight = "normal";
    }
  }
}

function bulletsHang(root) {
  var rows = root.querySelectorAll(".slide-prgrph");
  for (var i = 0; i < rows.length; i++) {
    var bullet = rows[i].firstElementChild;
    var text = rows[i].lastElementChild;
    if (!bullet || bullet === text) continue;
    var side = text.style.paddingLeft ? "paddingLeft" : "paddingRight";
    if (text.style[side]) {
      bullet.style.minWidth = text.style[side];
      text.style[side] = "0px";
    }
    if (bullet.querySelector("img")) continue;
    rows[i].classList.add("vw-bulleted");
    bullet.style.height = "0";
    bullet.style.lineHeight = "0";
    var glyph = bullet.firstElementChild;
    if (glyph) glyph.style.lineHeight = "0";
  }
}

var MISSING = {
  chart: "A chart that cannot be shown here",
  table: "A table that cannot be shown here",
  diagram: "A diagram that cannot be shown here",
  object: "An object that cannot be shown here",
  picture: "A picture that cannot be shown here",
  format: "A picture in a format that cannot be shown here",
  shape: "A shape that cannot be shown here"
};

function whatIsMissing(root) {
  var marked = root.querySelectorAll("[data-vw-missing]");
  for (var i = 0; i < marked.length; i++) markMissing(marked[i], marked[i].getAttribute("data-vw-missing"));
  var charts = root.querySelectorAll("div[id^='chart']");
  for (var j = 0; j < charts.length; j++) {
    if (!charts[j].querySelector("svg, .vw-missing")) markMissing(charts[j], "chart");
  }
}

document.getElementById("result").addEventListener("error", function (e) {
  var img = e.target;
  if (img.tagName !== "IMG" || !img.parentNode || !img.parentNode.classList.contains("block")) return;
  var drawn = /^data:image\/(png|jpeg|gif|bmp|webp|svg\+xml);/.test((img.getAttribute("src") || "").slice(0, 30));
  markMissing(img.parentNode, drawn ? "picture" : "format");
}, true);

function markMissing(block, kind) {
  window.officePptxOmission(kind);
  return;
  while (block.firstChild) block.removeChild(block.firstChild);
  var box = document.createElement("div");
  box.className = "vw-missing";
  box.textContent = MISSING[kind] || MISSING.shape;
  block.appendChild(box);
  var slide = block.closest(".slide");
  var width = slide ? slide.offsetWidth : 960;
  for (var size = width / 40; size > width / 100; size *= 0.8) {
    box.style.fontSize = size + "px";
    if (box.scrollHeight <= box.clientHeight && box.scrollWidth <= box.clientWidth) return;
  }
  box.textContent = "";
}

window.officeStartPptx = function (bytes, order) {
  officePptxGuard();
  $("#result").pptxToHtml({officeBytes: bytes, officeSlideOrder: order,
    pptxFileUrl: "", slideMode: false, keyBoardShortCut: false,
    mediaProcess: false, slidesScale: "", themeProcess: true});
  officePptxGuard();
  var result = document.getElementById("result");
  if ((window.vwLeftOut || []).length) { window.officePptxOmission("renderer"); return; }
  spacesThatBreak(result);
  regularWeight(result);
  bulletsHang(result);
  whatIsMissing(result);
  officePptxGuard();
  vwDisarmLinks(result);
  vwFitHeight();
};
