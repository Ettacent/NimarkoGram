/* Modifications Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent. Gander (MIT); see the accompanying Gander license. */
window.OfficePdfCapabilities = function (message) {
  var match = /(?:Chrome|Chromium)\/(\d+)/.exec(navigator.userAgent);
  var major = Number(message.webViewMajor) || (match ? Number(match[1]) : 0);
  if (!major || major < 125) return 'webView125Required';
  if (message.nativeGestures === false) return 'nativeGesturesRequired';
  if (!window.visualViewport || !window.IntersectionObserver || !window.ReadableStream ||
      !window.TransformStream || !window.Worker || !window.WebAssembly || !window.crypto || !crypto.subtle ||
      !window.CSS || !CSS.highlights || typeof window.Highlight !== 'function' ||
      typeof Promise.withResolvers !== 'function' ||
      !CSS.supports('font-size', 'round(down, 1.5px, 1px)') ||
      !('filter' in document.createElement('canvas').getContext('2d'))) return 'pdfCapabilitiesRequired';
  return '';
};
var vwPdfBlocked = false;
var vwNight = false;
