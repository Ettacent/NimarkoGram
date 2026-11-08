/* Modifications Copyright (C) 2026 Ettacent */

"use strict";

importScripts("static-source.js", "paper-pixels.js", "paper-contrast.js", "whole-graphic.js");
var officePaperBusy = false;
var officePaperCapable = typeof OffscreenCanvas === "function" && typeof createImageBitmap === "function" && typeof OffscreenCanvas.prototype.convertToBlob === "function";











async function officePaperExecute(job) {
  if (job?.op === "data-probe") {
    if (!Number.isInteger(job.id) || job.id < 1 || job.id > 32768 || job.provenance !== "archive-raster" || typeof job.data !== "string" || job.data.length > 2796250) return { type: 3, id: job?.id, code: 1 };
    try {
      var match = /^data:image\/(png|jpeg);base64,([A-Za-z0-9+/=\s]+)$/.exec(job.data);if (!match) return { type: 3, id: job.id, code: 1 };
      var binary = atob(match[2]),bytes = new Uint8Array(binary.length);if (bytes.length > 2097152) return { type: 3, id: job.id, code: 1 };
      for (var i = 0; i < bytes.length; i++) bytes[i] = binary.charCodeAt(i);
      var blob = new Blob([bytes], { type: "image/" + match[1] }),result = await officePaperExecute({ id: job.id, op: "probe", provenance: "archive-raster", blob: blob });
      if (result.probed) result.sourceBlob = blob;return result;
    } catch (_) {return { type: 3, id: job.id, code: 1 };}
  }
  var bitmap = null,canvas = null,inkCanvas = null,probe = job?.op === "probe";
  function preserve(reason, meta) {return { type: 2, id: job.id, accepted: false, width: meta?.width || job.w, height: meta?.height || job.h, blob: null, exposure: null, reason: reason, ...(probe ? { probed: !!meta?.ok, format: meta?.format || 0 } : {}) };}
  if (!officePaperCapable || !job || !Number.isInteger(job.id) || job.id < 1 || job.id > 32768 || !(job.blob instanceof Blob) || job.blob.size < 10 || job.blob.size > 2097152 ||
  !probe && (!Number.isInteger(job.w) || !Number.isInteger(job.h) || job.w < 8 || job.h < 8 || job.w > 2048 || job.h > 2048 || job.w * job.h > 350000)) return { type: 3, id: job && job.id, code: 1 };
  try {
    var compose = job.op === "compose";
    if (compose ? job.provenance !== "prepared-matte" : job.provenance !== "archive-raster") return preserve(7);
    var source = OfficeStaticSource.inspect(new Uint8Array(await job.blob.arrayBuffer()), probe ? undefined : job.w, probe ? undefined : job.h);
    if (probe) return preserve(source.reason || 0, source);
    if (!source.ok || compose && source.format !== 1) return preserve(source.reason || 2);
    bitmap = await createImageBitmap(job.blob);
    if (bitmap.width !== job.w || bitmap.height !== job.h) throw new Error("dimension");
    canvas = new OffscreenCanvas(job.w, job.h);var context = canvas.getContext("2d", { willReadFrequently: !compose });if (!context) throw new Error("context");
    context.drawImage(bitmap, 0, 0);bitmap.close();bitmap = null;
    if (compose) {
      var rgb = job.rgb === undefined ? [job.gray, job.gray, job.gray] : job.rgb;
      if (!Array.isArray(rgb) || rgb.length !== 3 || !rgb.every(function (v) {return Number.isInteger(v) && v >= 0 && v <= 255;})) throw new Error("backing color");
      if (job.inkBlob !== null) {
        if (!(job.inkBlob instanceof Blob) || job.inkBlob.size < 10 || job.inkBlob.size > 2100000) throw new Error("ink layer");
        var inkSource = OfficeStaticSource.inspect(new Uint8Array(await job.inkBlob.arrayBuffer()), job.w, job.h);
        var mix = vwArtworkInkMix(job.inkMin, rgb);if (!inkSource.ok || inkSource.format !== 1 || mix === null) throw new Error("ink policy");
        bitmap = await createImageBitmap(job.inkBlob);if (bitmap.width !== job.w || bitmap.height !== job.h) throw new Error("ink dimensions");
        inkCanvas = new OffscreenCanvas(job.w, job.h);var inkContext = inkCanvas.getContext("2d");if (!inkContext) throw new Error("ink context");
        inkContext.drawImage(bitmap, 0, 0);bitmap.close();bitmap = null;
        inkContext.globalCompositeOperation = "source-atop";inkContext.globalAlpha = mix;
        inkContext.fillStyle = "#fff";inkContext.fillRect(0, 0, job.w, job.h);
        context.drawImage(inkCanvas, 0, 0);
      }
      context.globalCompositeOperation = "destination-over";context.fillStyle = "rgb(" + rgb.join(",") + ")";context.fillRect(0, 0, job.w, job.h);
      var rendered = await canvas.convertToBlob({ type: "image/png" });if (rendered.size > 2100000) throw new Error("output");
      return { type: 2, id: job.id, accepted: true, width: job.w, height: job.h, blob: rendered, format: 1 };
    }
    var pixels = context.getImageData(0, 0, job.w, job.h);
    var accepted = vwMatteArtwork(pixels.data, job.w, job.h),wholePalette = false;
    if (!accepted) {var graphic = artGraphicEvidence(pixels.data, job.w, job.h);if (artAdmitGraphic(graphic)) {var contrast = artGraphicContrast(pixels.data, job.w, job.h, graphic);if (contrast.safe) {wholePalette = artPreparePalette(pixels.data, job.w, job.h, graphic.whitePoint);accepted = wholePalette;}}}
    var exposure = accepted ? vwArtworkExposure(pixels.data, job.w, job.h) : null;
    var layers = accepted ? wholePalette ? { minimum: [255, 255, 255], pixels: 0 } : vwArtworkDarkLayers(pixels.data, job.w, job.h) : null,blob = null,inkBlob = null;
    if (accepted) {
      if (!layers) throw new Error("ink split");
      context.putImageData(pixels, 0, 0);blob = await canvas.convertToBlob({ type: "image/png" });if (blob.size > 2100000) throw new Error("output");
      if (layers.pixels) {
        inkCanvas = new OffscreenCanvas(job.w, job.h);var inkContext = inkCanvas.getContext("2d");if (!inkContext) throw new Error("ink context");
        var inkData = inkContext.createImageData(job.w, job.h);inkData.data.set(layers.data);inkContext.putImageData(inkData, 0, 0);
        inkBlob = await inkCanvas.convertToBlob({ type: "image/png" });if (inkBlob.size > 2100000) throw new Error("ink output");
      }
    }
    return { type: 2, id: job.id, accepted: accepted, width: job.w, height: job.h, blob: blob, inkBlob: inkBlob, inkMin: layers?.minimum || null, inkPixels: layers?.pixels || 0, darkInkRevision: 1, exposure: exposure, format: source.format, reason: accepted ? 0 : 20
    };
  } catch (_) {return { type: 3, id: job.id, code: 2 };} finally
  {if (bitmap) bitmap.close();if (canvas) canvas.width = canvas.height = 0;if (inkCanvas) inkCanvas.width = inkCanvas.height = 0;}
}
self.postMessage({ type: 0, capable: officePaperCapable });
self.onmessage = async function (event) {
  if (officePaperBusy) {self.postMessage({ type: 3, id: event.data?.id, code: 3 });return;}
  officePaperBusy = true;try {self.postMessage(await officePaperExecute(event.data));} finally {officePaperBusy = false;}
};
