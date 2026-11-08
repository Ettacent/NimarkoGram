/* Modifications Copyright (C) 2026 Ettacent */

"use strict";

var vwNightOn = vwParams.get("night") === "1";
var vwNightReady = false;
var vwContrastSurfaces = new Set();







if (vwNightOn) document.documentElement.classList.add("vw-night");

var VW_NIGHT_MATRIX = [
[0.574, -1.430, -0.144, 1],
[-0.426, -0.430, -0.144, 1],
[-0.426, -1.430, 0.856, 1]];


var vwColourProbe = null;

function vwNightColour(value) {
  if (!value || /var\(|currentcolor|inherit|initial|unset|revert/i.test(value)) return null;
  if (!vwColourProbe) vwColourProbe = document.createElement("canvas").getContext("2d");
  vwColourProbe.fillStyle = "#000001";
  vwColourProbe.fillStyle = value;
  var said = vwColourProbe.fillStyle;
  vwColourProbe.fillStyle = "#000002";
  vwColourProbe.fillStyle = value;
  if (said !== vwColourProbe.fillStyle) return null;

  var rgba;
  if (said.charAt(0) === "#") {
    rgba = [parseInt(said.slice(1, 3), 16), parseInt(said.slice(3, 5), 16), parseInt(said.slice(5, 7), 16), 1];
  } else {
    rgba = said.replace(/[^\d.,]/g, "").split(",").map(Number);
  }
  var out = VW_NIGHT_MATRIX.map(function (row) {
    var v = row[0] * rgba[0] / 255 + row[1] * rgba[1] / 255 + row[2] * rgba[2] / 255 + row[3];
    return Math.round(Math.min(1, Math.max(0, v)) * 255);
  });
  return "rgba(" + out.join(", ") + ", " + (rgba.length > 3 ? rgba[3] : 1) + ")";
}

var VW_NIGHT_PROPS = {
  "color": "c",
  "background-color": "b",
  "border-top-color": "bt",
  "border-right-color": "br",
  "border-bottom-color": "bb",
  "border-left-color": "bl",
  "text-decoration-color": "d",
  "column-rule-color": "r",
  "fill": "f",
  "stroke": "s"
};
var VW_NIGHT_STYLED = /color|border|column-rule|fill|stroke|decoration|background/i;

function vwNightArtwork(container) {
  var out = [],index = 0;
  container.querySelectorAll("svg").forEach(function (svg) {
    if (!svg.querySelector("image") || svg.parentElement.closest("svg")) return;
    [svg].concat(Array.from(svg.querySelectorAll("*"))).forEach(function (el) {
      var id = String(index++),style = getComputedStyle(el),decls = [];
      el.setAttribute("data-vw-art", id);
      for (var name in VW_NIGHT_PROPS) {
        var value = style.getPropertyValue(name);
        if (value) decls.push(name + ": " + value + " !important");
      }
      out.push('html.vw-night #container [data-vw-art="' + id + '"] { ' + decls.join("; ") + ' }');
    });
  });
  return out;
}

function vwSplitSelectors(text) {
  var out = [],depth = 0,from = 0;
  for (var i = 0; i < text.length; i++) {
    var c = text.charAt(i);
    if (c === "(" || c === "[") depth++;else
    if (c === ")" || c === "]") depth--;else
    if (c === "," && !depth) {out.push(text.slice(from, i));from = i + 1;}
  }
  out.push(text.slice(from));
  return out.map(function (s) {return s.trim();}).filter(Boolean);
}

function vwNightRules(container) {
  var out = [];
  container.querySelectorAll("style").forEach(function (el) {
    var rules;
    try {rules = el.sheet && el.sheet.cssRules;} catch (e) {return;}
    for (var i = 0; rules && i < rules.length; i++) {
      var rule = rules[i];
      if (!rule.style || !rule.selectorText) continue;
      if (rule.style.getPropertyValue("background-color")) {
        try {container.querySelectorAll(rule.selectorText).forEach(function (el) {vwContrastSurfaces.add(el);});} catch (_) {}
      }
      var decls = [];
      for (var k = 0; k < rule.style.length; k++) {
        var name = rule.style[k];
        var custom = /^--docx-.*-color$/.test(name);
        if (!custom && !VW_NIGHT_PROPS[name]) continue;
        var night = vwNightColour(rule.style.getPropertyValue(name).trim());
        if (night) decls.push(name + ": " + night + (custom ? "" : " !important"));
      }
      if (!decls.length) continue;
      var selectors = vwSplitSelectors(rule.selectorText).map(function (s) {return "html.vw-night " + s;});
      out.push(selectors.join(", ") + " { " + decls.join("; ") + " }");
    }
  });
  return out;
}

function vwNightInline(container) {
  container.querySelectorAll("[style]").forEach(function (el) {
    if (el.hasAttribute("data-vw-art")) return;
    if (!VW_NIGHT_STYLED.test(el.getAttribute("style"))) return;
    var marks = [];
    for (var name in VW_NIGHT_PROPS) {
      var value = el.style.getPropertyValue(name).trim();
      var night = /^var\(--docx-[\w-]+-color\)$/.test(value) ? value : vwNightColour(value);
      if (!night) continue;
      el.style.setProperty("--vw-n-" + VW_NIGHT_PROPS[name], night);
      marks.push(VW_NIGHT_PROPS[name]);
    }
    if (marks.indexOf("b") >= 0) vwContrastSurfaces.add(el);
    if (marks.length) el.setAttribute("data-vw-n", marks.join(" "));
  });
  container.querySelectorAll("svg [fill], svg [stroke]").forEach(function (el) {
    if (el.hasAttribute("data-vw-art")) return;
    var marks = (el.getAttribute("data-vw-n") || "").split(" ").filter(Boolean);
    ["fill", "stroke"].forEach(function (name) {
      var night = vwNightColour(el.getAttribute(name));
      if (!night) return;
      el.style.setProperty("--vw-n-" + VW_NIGHT_PROPS[name], night);
      marks.push(VW_NIGHT_PROPS[name]);
    });
    if (marks.length) el.setAttribute("data-vw-n", marks.join(" "));
  });
}

function vwSetPaperTheme() {}
function vwNightPictures() {}
function vwPaperPicturesReady() {return Promise.resolve();}
function vwDisposePaperPictures() {}
function vwPausePaperPictures() {}
function vwResumePaperPictures() {}

var vwContrastKey = null,vwContrastSheet = null,vwContrastIds = new WeakMap(),vwContrastNext = 0;
function vwRepairNightContrast() {
  var root = document.documentElement,container = document.getElementById("container");
  if (!vwNightReady || !root.classList.contains("vw-night") || !container) return;
  var key = [root.style.getPropertyValue("--office-foreground"), root.style.getPropertyValue("--office-night-paper")].join("|");
  if (key === vwContrastKey) return;
  vwContrastKey = key;
  var surfaces = Array.from(vwContrastSurfaces).filter(function (el) {
    return el.isConnected && container.contains(el) && !el.matches("section.docx,.docx-wrapper,svg,[data-vw-art]");
  });
  if (!surfaces.length) {if (vwContrastSheet) vwContrastSheet.textContent = "";return;}
  if (!vwContrastSheet) {
    vwContrastSheet = document.createElement("style");
    vwContrastSheet.id = "vw-night-contrast";
    document.head.appendChild(vwContrastSheet);
  }
  vwContrastSheet.textContent = "";
  var styles = new WeakMap(),backgrounds = new WeakMap(),contexts = new WeakMap(),seen = new Set(),rules = [];
  function style(el) {var s = styles.get(el);if (!s) {s = getComputedStyle(el);styles.set(el, s);}return s;}
  function rgb(value) {
    var match = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)$/.exec(value);
    return match ? [+match[1], +match[2], +match[3], match[4] === undefined ? 1 : +match[4]] : null;
  }
  function background(el, depth) {
    if (!el || el === container || depth > 64) return null;
    if (backgrounds.has(el)) return backgrounds.get(el);
    var s = style(el),color = rgb(s.backgroundColor),result = null;
    if (+s.opacity === 1 && s.backgroundImage === "none" && s.filter === "none" && s.mixBlendMode === "normal") {
      if (color && color[3] === 1) result = color;else
      if (color && color[3] === 0) result = background(el.parentElement, depth + 1);
    }
    backgrounds.set(el, result);return result;
  }
  function luminance(color) {
    var c = color.slice(0, 3).map(function (v) {v /= 255;return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);});
    return .2126 * c[0] + .7152 * c[1] + .0722 * c[2];
  }
  function opaqueContext(el, depth) {
    if (!el || depth > 64) return false;
    if (contexts.has(el)) return contexts.get(el);
    var s = style(el),safe = +s.opacity === 1 && s.filter === "none" && s.mixBlendMode === "normal";
    if (safe && el !== container) safe = opaqueContext(el.parentElement, depth + 1);
    contexts.set(el, safe);return safe;
  }
  function plainRun(el) {
    for (var child of el.children) {
      if (child.namespaceURI !== "http://www.w3.org/1999/xhtml") return false;
      if (child.tagName === "BR" || child.tagName === "WBR") continue;
      if (child.tagName !== "SPAN" || !child.classList.contains("docx-tab-stop") || child.children.length || /\S/.test(child.textContent)) return false;
      var s = style(child),b = rgb(s.backgroundColor);
      if (!b || b[3] !== 0 || s.backgroundImage !== "none" || +s.opacity !== 1 || s.filter !== "none" || s.mixBlendMode !== "normal") return false;
    }
    return true;
  }
  var node,visited = 0;
  for (var surface of surfaces) {
    var walker = document.createTreeWalker(surface, NodeFilter.SHOW_TEXT);
    while ((node = walker.nextNode()) && ++visited <= 40000 && seen.size < 12000) {
      if (!/\S/.test(node.nodeValue)) continue;
      var el = node.parentElement;
      if (!el || seen.has(el)) continue;
      seen.add(el);
      if (el.namespaceURI !== "http://www.w3.org/1999/xhtml" || !plainRun(el) || el.closest("svg,[data-vw-art],style,script")) continue;
      var s = style(el),ink = rgb(s.color),paper = background(el, 0);
      if (!ink || ink[3] !== 1 || !paper || !opaqueContext(el, 0)) continue;
      var a = luminance(ink),b = luminance(paper),ratio = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
      var large = parseFloat(s.fontSize) >= 24 || parseFloat(s.fontSize) >= 18.667 && parseInt(s.fontWeight, 10) >= 700;
      if (ratio >= (large ? 3 : 4.5)) continue;
      var id = vwContrastIds.get(el);
      if (id === undefined) {id = ++vwContrastNext;vwContrastIds.set(el, id);el.setAttribute("data-vw-contrast", String(id));}
      var color = (b + .05) / .05 >= 1.05 / (b + .05) ? "#000" : "#fff";
      rules.push('html.vw-night #container [data-vw-contrast="' + id + '"] { color: ' + color + ' !important; }');
    }
    if (visited >= 40000 || seen.size >= 12000) break;
  }
  vwContrastSheet.textContent = "@media screen { " + rules.join("\n") + " }";
}

function vwPrepareNight() {
  var container = document.getElementById("container");
  if (vwNightReady || !vwPages.settled || !container) return;

  {
    vwNightReady = true;
    var root = document.documentElement,wasNight = root.classList.contains("vw-night"),artwork;
    root.classList.remove("vw-night");
    try {artwork = vwNightArtwork(container);} finally
    {if (wasNight) root.classList.add("vw-night");}
    var style = document.createElement("style");
    style.id = "vw-night-rules";
    style.textContent = "@media screen { " + vwNightRules(container).concat(artwork).join("\n") + " }";
    document.head.appendChild(style);
    vwNightInline(container);
    vwNightPictures(container);
    document.documentElement.classList.add("vw-night-ready");
    vwRepairNightContrast();

  }
}

function vwSetNight(on) {

  {
    vwNightOn = on;
    if (on) vwPrepareNight();
    document.documentElement.classList.toggle("vw-night", on);
    if (on) vwRepairNightContrast();
    if (on && vwNightReady) vwNightPictures(document.getElementById("container"));

  }
}
