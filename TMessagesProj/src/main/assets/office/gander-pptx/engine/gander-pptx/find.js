/* Modifications Copyright (C) 2026 Ettacent */

"use strict";

var vwFind = function () {











  var MAX_MATCHES = 1000;

  var MAX_PAINTED = 1000;

  var BLOCK = /^(ADDRESS|ARTICLE|ASIDE|BLOCKQUOTE|BR|CAPTION|DD|DIV|DL|DT|FIGCAPTION|FIGURE|FOOTER|H[1-6]|HEADER|HR|LI|MAIN|NAV|OL|P|PRE|SECTION|TABLE|TBODY|TD|TFOOT|TH|THEAD|TR|UL)$/;

  var port = null;
  var query = "";
  var matches = [];
  var active = -1;
  var capped = false;
  var cache = [];
  var rerunTimer = 0;
  var suspended = false;
  var moving = false;
  var waiting = false;

  function parts() {
    if (window.vwFindParts) return window.vwFindParts;
    var root = document.querySelector("[data-vw-find]") || document.body;
    return {
      count: function () {return 1;},
      root: function () {return root;},
      show: function () {return root;},
      shown: function () {return 0;}
    };
  }

  function fold(s) {
    var low = s.toLowerCase();
    if (low.length === s.length) return low;
    var out = "";
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i).toLowerCase();
      out += c.length === 1 ? c : s.charAt(i);
    }
    return out;
  }

  function flatten(root) {

    {
      var pieces = [];
      var length = 0;
      var nodes = [];
      var starts = [];
      var spaced = [];
      var exact = [];
      var inPre = 0;
      var lastWasSpace = true;

      function add(s) {
        pieces.push(s);
        length += s.length;
      }

      function separate() {
        if (!lastWasSpace || pieces.length && pieces[pieces.length - 1] !== "\n") add("\n");
        lastWasSpace = true;
      }

      (function walk(node) {
        for (var child = node.firstChild; child; child = child.nextSibling) {
          if (child.nodeType === 3) {
            var raw = child.data;
            if (!raw) continue;
            nodes.push(child);
            starts.push(length);
            spaced.push(lastWasSpace);
            var text = inPre ? raw : raw.replace(/\s+/g, " ");
            var dropped = !inPre && lastWasSpace && text.charAt(0) === " ";
            if (dropped) text = text.slice(1);
            exact.push(!dropped && text.length === raw.length);
            if (text) {
              add(text);
              lastWasSpace = /\s/.test(text.charAt(text.length - 1));
            }
          } else if (child.nodeType === 1) {
            var tag = child.tagName;
            if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT" || tag === "TEMPLATE" ||
            child.hidden) continue;
            var block = BLOCK.test(tag);
            if (block) separate();
            if (tag === "PRE") inPre++;
            walk(child);
            if (tag === "PRE") inPre--;
            if (block) separate();
          }
        }
      })(root);

      return {
        root: root, text: fold(pieces.join("")), nodes: nodes, starts: starts, spaced: spaced, exact: exact
      };
    }

  }

  function flattened(i) {
    var root = parts().root(i);
    var hit = cache[i];
    if (hit && hit.root === root) return hit;
    return cache[i] = flatten(root);
  }

  function rawOffset(flat, i, k) {
    var node = flat.nodes[i];
    if (flat.exact[i]) return Math.min(k, node.data.length);
    var raw = node.data;
    var spacedBefore = flat.spaced[i];
    var emitted = 0;
    var space = spacedBefore;
    for (var r = 0; r < raw.length; r++) {
      if (emitted >= k) return r;
      if (/\s/.test(raw.charAt(r))) {
        if (!space) {emitted++;space = true;}
      } else {
        emitted++;
        space = false;
      }
    }
    return raw.length;
  }

  function nodeAt(flat, at) {
    var lo = 0,hi = flat.starts.length - 1,found = -1;
    while (lo <= hi) {
      var mid = lo + hi >> 1;
      if (flat.starts[mid] <= at) {found = mid;lo = mid + 1;} else {hi = mid - 1;}
    }
    return found;
  }

  function rangeFor(flat, match) {
    var first = nodeAt(flat, match.at);
    var last = nodeAt(flat, match.at + match.len - 1);
    if (first < 0 || last < 0) return null;
    var range = document.createRange();
    try {
      var a = flat.nodes[first],b = flat.nodes[last];
      range.setStart(a, rawOffset(flat, first, match.at - flat.starts[first]));
      range.setEnd(b, rawOffset(flat, last, match.at + match.len - flat.starts[last]));
    } catch (e) {
      return null;
    }
    return range;
  }

  function search() {

    {
      matches = [];
      capped = false;
      var q = fold(query.replace(/\s+/g, " "));
      if (!q) return;
      var p = parts();
      for (var i = 0; i < p.count() && !capped; i++) {
        var text = flattened(i).text;
        var at = text.indexOf(q);
        while (at !== -1) {
          if (matches.length >= MAX_MATCHES) {capped = true;break;}
          matches.push({ part: i, at: at, len: q.length });
          at = text.indexOf(q, at + q.length);
        }
      }
    }

  }

  function loading() {
    var p = parts();
    if (p.busy && p.busy()) return true;
    var card = document.getElementById("vw-status");
    return !!card && card.style.display !== "none" && card.className.indexOf("vw-error") < 0;
  }

  function report() {
    if (!port) return;
    var total = matches.length;
    var at = total && active >= 0 ? active + 1 : 0;
    port.postMessage(at + " " + total + " " + (loading() ? 0 : 1) + " " + (capped ? 1 : 0));
  }

  function paint() {

    {
      if (!window.CSS || !CSS.highlights) return;
      var p = parts();
      var shown = p.shown();
      var hits = [];
      var current = null;
      var from = Math.max(0, active - MAX_PAINTED / 2);
      for (var i = from; i < matches.length && hits.length < MAX_PAINTED; i++) {
        var part = matches[i].part;
        if (p.drawn ? !p.drawn(part) : part !== shown) continue;
        var r = rangeFor(flattened(part), matches[i]);
        if (!r) continue;
        if (i === active) current = r;else hits.push(r);
      }
      if (hits.length) CSS.highlights.set("vw-find", new Highlight(...hits));else
      CSS.highlights.delete("vw-find");
      if (current) CSS.highlights.set("vw-find-active", new Highlight(current));else
      CSS.highlights.delete("vw-find-active");
    }

  }

  function reveal() {

    {
      var match = matches[active];
      if (!match) return;
      var p = parts();
      if (p.shown() !== match.part) {
        moving = true;
        p.show(match.part);
        cache[match.part] = null;
        setTimeout(function () {moving = false;}, 0);
      }
      var range = rangeFor(flattened(match.part), match);
      if (!range) return;
      var holder = range.startContainer.parentElement;
      if (holder) holder.scrollIntoView({ block: "center", inline: "nearest" });
      var box = range.getBoundingClientRect();
      if (box.height && (box.top < 0 || box.bottom > window.innerHeight)) {
        window.scrollBy(0, box.top - window.innerHeight / 2);
      }
      if (!window.CSS || !CSS.highlights) {
        var selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
      }
    }

  }

  function stepTo(i) {
    var n = matches.length;
    active = n ? (i % n + n) % n : -1;
    reveal();
    paint();
    report();
  }

  function begin() {
    var p = parts();
    waiting = !!(p.top && p.busy && p.busy());
    if (!waiting) {
      stepTo(ahead());
      return;
    }
    active = -1;
    paint();
    report();
  }

  function ahead() {
    var p = parts();
    if (!p.top) return 0;
    var shown = p.shown();
    var top = p.top();
    for (var i = 0; i < matches.length; i++) {
      if (matches[i].part < shown) continue;
      if (matches[i].part > shown) return i;
      var range = rangeFor(flattened(shown), matches[i]);
      if (range && range.getBoundingClientRect().bottom > top) return i;
    }
    return 0;
  }

  function clear() {
    query = "";
    matches = [];
    active = -1;
    waiting = false;
    if (window.CSS && CSS.highlights) {
      CSS.highlights.delete("vw-find");
      CSS.highlights.delete("vw-find-active");
    }
    report();
  }

  function changed(records) {
    if (moving) return;
    forget(records);
    if (suspended || !query || rerunTimer || waiting) return;
    rerunTimer = setTimeout(function () {
      rerunTimer = 0;
      var was = matches[active];
      search();
      var keep = -1;
      if (was) {
        for (var i = 0; i < matches.length; i++) {
          if (matches[i].part === was.part && matches[i].at === was.at) {keep = i;break;}
        }
      }
      active = keep >= 0 ? keep : Math.min(Math.max(active, 0), matches.length - 1);
      paint();
      report();
    }, 250);
  }

  function forget(records) {
    if (!records) {cache = [];return;}
    for (var i = 0; i < cache.length; i++) {
      var hit = cache[i];
      if (!hit) continue;
      for (var r = 0; r < records.length; r++) {
        var target = records[r].target;
        if (hit.root === target || hit.root.contains(target)) {cache[i] = null;break;}
      }
    }
  }

  function onCommand(msg) {
    if (vwBarSaid(msg)) return;
    var verb = msg.charAt(0);
    if (verb === "q") {
      var q = msg.slice(1);
      if (q === query) return;
      query = q;
      if (!q) {clear();return;}
      search();
      begin();
    } else if (verb === "n") {
      if (matches.length) stepTo(active + 1);
    } else if (verb === "p") {
      if (matches.length) stepTo(active - 1);
    } else if (verb === "c") {
      clear();
    } else if (window.vwPageCommand) {
      window.vwPageCommand(msg);
    }
  }


  document.addEventListener("DOMContentLoaded", function () {
    if (!window.MutationObserver) return;
    new MutationObserver(changed).observe(document.body, {
      childList: true, subtree: true, characterData: true
    });
  });

  if (typeof vwStatusDone === "function") {
    var done = vwStatusDone;
    vwStatusDone = function () {
      done.apply(this, arguments);
      if (query) changed();else report();
    };
  }

  function reread() {
    if (!query || matches.length && !waiting) {changed();return;}
    waiting = false;
    cache = [];
    search();
    stepTo(ahead());
  }

  function post(msg) {
    if (!port) return false;
    port.postMessage(msg);
    return true;
  }

  return { changed: changed, reread: reread, post: post, command: onCommand, report: report,
    step: function (steps) {if (matches.length) stepTo(active + steps);},
    setPaused: function (value) {suspended = value;if (value) {clearTimeout(rerunTimer);rerunTimer = 0;}},
    setSink: function (sink) {port = { postMessage: sink };} };
}();
