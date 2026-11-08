/* Modifications Copyright (C) 2026 Ettacent */

"use strict";

var vwParams = new URLSearchParams(location.search);
var vwName = vwParams.get("name") || "file";
var vwExt = (vwParams.get("ext") || "").toLowerCase();

function vwStatus(msg) {
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    el.className = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status";
  el.textContent = msg;
  el.style.display = "";
}

function vwStatusDone() {
  var el = document.getElementById("vw-status");
  if (el) el.style.display = "none";
}

function vwError(title, detail) {
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status vw-error";
  el.style.display = "";
  el.innerHTML = "";
  var t = document.createElement("div");
  t.className = "vw-error-title";
  t.textContent = title;
  var d = document.createElement("div");
  d.className = "vw-error-detail";
  d.textContent = detail || "";
  el.appendChild(t);
  el.appendChild(d);
}

function vwWebViewTooOld(what) {
  var have = vwParams.get("webview");
  var locked = vwParams.get("locked");
  if (!have && !locked) return false;
  vwNeedsNewerWebView(what, have, vwParams.get("needs"), locked);
  window.onerror = null;
  return true;
}

function vwNeedsNewerWebView(what, have, needs, locked) {
  if (locked) {
    vwError(
      what + " cannot be shown on this phone",
      (needs
        ? what + " need the browser engine built into your phone to be version " +
          needs + " or newer, and this one is " + (have || "older") + ". "
        : what + " need a newer browser engine than the one built into your phone. ") +
      "On this phone that engine comes from the manufacturer and cannot be " +
      "updated or replaced, so installing Android System WebView will not help."
    );
    return;
  }
  vwError(
    "Android System WebView is too old to show " + what,
    "Gander opens " + what + " using Android System WebView, the browser engine built " +
    "into your phone. " +
    (have && needs
      ? what + " need version " + needs + " or newer, and this one is " + have + ". "
      : "") +
    "Updating Android System WebView and reopening the file will fix it."
  );
}

function vwPhysicalWidth() {
  var vv = window.visualViewport;
  if (!vv || !(vv.width > 0)) return 0;
  var dpr = window.devicePixelRatio || 1;
  var seen = vv.width * dpr * (vv.scale > 0 ? vv.scale : 1);
  var screenWidth = window.screen && window.screen.width > 0
    ? window.screen.width * dpr
    : 0;
  if (!(seen > 0) || !(screenWidth > 0)) return 0;
  if (seen > screenWidth * 1.05 || seen < screenWidth * 0.25) return 0;
  return seen;
}

var vwFitBound = false;

function vwFitHeight() {
  if (!vwFitBound) {
    vwFitBound = true;
    addEventListener("resize", vwFitHeight);
  }
  document.documentElement.style.setProperty("--vw-fit", window.innerHeight + "px");
  vwPlaceTop();
}

var vwTopDp = Number(vwParams.get("top")) || 0;
var vwTopPx = 0;
var vwTopBound = false;
var vwBarGoneDp = 0;
var vwBarGonePx = 0;

function vwPlaceTop() {
  if (!vwTopDp && !vwTopPx) return;
  if (!vwTopBound) {
    vwTopBound = true;
    addEventListener("resize", vwPlaceTop);
  }
  var vv = window.visualViewport;
  var across = vv && vv.scale > 0 ? vv.width * vv.scale : 0;
  var perDp = across > 0 && window.innerWidth > 0 ? window.innerWidth / across : 1;
  var px = vwTopDp * perDp;
  var gone = vwBarGoneDp * perDp;
  var root = document.documentElement.style;
  if (px !== vwTopPx) {
    vwTopPx = px;
    root.setProperty("--vw-top", px + "px");
  }
  if (gone !== vwBarGonePx) {
    vwBarGonePx = gone;
    root.setProperty("--vw-bar-gone", gone + "px");
  }
}

vwPlaceTop();

function vwBarSaid(msg) {
  var verb = msg.charAt(0);
  if (verb === "t") {
    vwTopDp = Number(msg.slice(1)) || 0;
    vwPlaceTop();
    return true;
  }
  if (verb === "b") {
    vwBarGoneDp = Number(msg.slice(1)) || 0;
    vwPlaceTop();
    return true;
  }
  return false;
}

function vwPrintSizes(sheets) {
  var rules = "";
  var names = {};
  for (var i = 0; i < sheets.length; i++) {
    var style = sheets[i].style;
    if (!style.width || !style.minHeight) continue;
    var size = style.width + " " + style.minHeight;
    if (!names[size]) {
      names[size] = "vw-sheet-" + i;
      rules += "@page " + names[size] + " { size: " + size + "; } ";
    }
    style.page = names[size];
  }
  if (!rules) return;
  var css = document.createElement("style");
  css.textContent = "@media print { " + rules + "}";
  document.head.appendChild(css);
}

function vwDisarmLinks(root) {
  var links = root.querySelectorAll("a[href], area[href]");
  for (var i = 0; i < links.length; i++) {
    var href = links[i].getAttribute("href").trim();
    if (href.charAt(0) === "#") continue;
    var scheme = /^([a-z][a-z0-9+.-]*):/i.exec(href);
    if (scheme && /^(https?|mailto|tel)$/i.test(scheme[1])) continue;
    links[i].removeAttribute("href");
  }
}

function vwDocUrl() {
  return "/doc/file" + (vwExt ? "." + vwExt : "");
}

var VW_BUSY_BYTES = 1024 * 1024;

function vwShowBusy(doc, size) {
  var el = document.getElementById("vw-status");
  if (size < VW_BUSY_BYTES || !el || el.style.display === "none") return doc;
  el.classList.remove("vw-wait");
  return new Promise(function (resolve) {
    requestAnimationFrame(function () { setTimeout(function () { resolve(doc); }, 0); });
  });
}

function vwFetchDoc(kind) {
  return fetch(vwDocUrl()).then(function (r) {
    if (!r.ok) throw new Error("Could not read the file (HTTP " + r.status + ")");
    return kind === "text" ? r.text() : r.arrayBuffer();
  }).then(function (doc) {
    return vwShowBusy(doc, kind === "text" ? doc.length : doc.byteLength);
  });
}

var VW_TEXT_PAGE = 5 * 1024 * 1024;

function vwEncodingOf(bytes) {
  if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE) return "utf-16le";
  if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF) return "utf-16be";
  return "utf-8";
}

function vwOpenText() {
  return vwOpenTextPages().then(function (doc) {
    var next = doc.next;
    doc.next = function () {
      return next().then(function (page) { return vwShowBusy(page, page.bytes); });
    };
    return doc;
  });
}

function vwOpenTextPages() {
  return fetch(vwDocUrl()).then(function (r) {
    if (!r.ok) throw new Error("Could not read the file (HTTP " + r.status + ")");

    var decoder = null;
    function decode(bytes, last) {
      if (!decoder) decoder = new TextDecoder(vwEncodingOf(bytes));
      return { text: decoder.decode(bytes, { stream: !last }), bytes: bytes.length, done: last };
    }

    if (!r.body || !r.body.getReader) {
      return r.arrayBuffer().then(function (buf) {
        var all = new Uint8Array(buf);
        var off = 0;
        return {
          next: function () {
            var end = Math.min(off + VW_TEXT_PAGE, all.length);
            var page = all.subarray(off, end);
            off = end;
            return Promise.resolve(decode(page, off >= all.length));
          }
        };
      });
    }

    var reader = r.body.getReader();
    var pending = [];
    var held = 0;
    var ended = false;

    return {
      next: function () {
        function pump() {
          if (ended || held > VW_TEXT_PAGE) return Promise.resolve();
          return reader.read().then(function (res) {
            if (res.done) { ended = true; return; }
            pending.push(res.value);
            held += res.value.length;
            return pump();
          });
        }
        return pump().then(function () {
          var take = Math.min(held, VW_TEXT_PAGE);
          var page = new Uint8Array(take);
          var off = 0;
          while (off < take) {
            var chunk = pending[0];
            var n = Math.min(chunk.length, take - off);
            page.set(chunk.subarray(0, n), off);
            off += n;
            if (n === chunk.length) pending.shift();
            else pending[0] = chunk.subarray(n);
          }
          held -= take;
          return decode(page, ended && held === 0);
        });
      }
    };
  });
}

function vwMostlyRightToLeft(text) {
  var letter, rtlLetter;
  try {
    letter = new RegExp("\\p{L}", "gu");
    rtlLetter = new RegExp("(?=\\p{L})[\\p{scx=Arabic}\\p{scx=Hebrew}\\p{scx=Syriac}" +
      "\\p{scx=Thaana}\\p{scx=Nko}\\p{scx=Adlam}]", "gu");
  } catch (e) {
    return null;
  }
  var all = (text.match(letter) || []).length;
  return all ? (text.match(rtlLetter) || []).length * 2 > all : null;
}

var VW_DIRECTION_SAMPLE = 64 * 1024;

var VW_JSON_INDENT = "  ";

var VW_JSON_MAX = 1024 * 1024;

var VW_JSON_MAX_OUT = 4 * VW_JSON_MAX;
var VW_JSON_MAX_LINES = 150 * 1000;

function vwFormatJson(text) {
  try {
    JSON.parse(text);
  } catch (e) {
    return null;
  }

  var pads = [""];
  function pad(depth) {
    while (pads.length <= depth) pads.push(pads[pads.length - 1] + VW_JSON_INDENT);
    return pads[depth];
  }

  var out = [];
  var n = text.length;
  var depth = 0;
  var i = 0;
  var size = n;
  var lines = 1;

  function newline() {
    var indent = pad(depth);
    out.push("\n", indent);
    size += 1 + indent.length;
    lines++;
  }

  var fresh = false;
  function begin() {
    if (fresh) {
      newline();
      fresh = false;
    }
  }

  while (i < n) {
    if (size > VW_JSON_MAX_OUT || lines > VW_JSON_MAX_LINES) return null;
    var c = text.charAt(i);

    if (c === '"') {
      begin();
      var str = i;
      i++;
      while (i < n) {
        var s = text.charAt(i);
        i++;
        if (s === "\\") i++;
        else if (s === '"') break;
      }
      out.push(text.slice(str, i));
    } else if (c === " " || c === "\t" || c === "\n" || c === "\r") {
      i++;
    } else if (c === "{" || c === "[") {
      begin();
      out.push(c);
      depth++;
      fresh = true;
      i++;
    } else if (c === "}" || c === "]") {
      depth--;
      if (fresh) fresh = false;
      else newline();
      out.push(c);
      i++;
    } else if (c === ",") {
      out.push(",");
      newline();
      i++;
    } else if (c === ":") {
      out.push(": ");
      size++;
      i++;
    } else {
      begin();
      var lit = i;
      while (i < n && '{}[],: \t\n\r"'.indexOf(text.charAt(i)) < 0) i++;
      out.push(text.slice(lit, i));
    }
  }

  return out.join("");
}

function vwFormatSize(bytes) {
  var mb = bytes / (1024 * 1024);
  if (mb < 1) return Math.max(1, Math.round(bytes / 1024)) + " KB";
  return (mb >= 10 ? Math.round(mb) : Math.round(mb * 10) / 10) + " MB";
}

window.onerror = function (message) {
  vwError("Something went wrong while rendering", String(message));
};
