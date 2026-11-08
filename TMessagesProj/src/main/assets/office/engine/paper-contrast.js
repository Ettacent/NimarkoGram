/* Modifications Copyright (C) 2026 Ettacent */

"use strict";
var VW_MATTE_PIXELS = 350000;

function vwPaperAssessment(exposure, luminance) {
  var result = {choice: 2, paper: luminance, exposed: 0, poor: 0, upper: 0, required: 0, achieved: 0, colour: null};
  if (!Array.isArray(exposure) || exposure.length !== 32 || !Number.isFinite(luminance) || luminance < 0 || luminance > 1) return result;
  var total = 0, poor = 0, upper = 0;
  for (var i = 0; i < 32; i++) {
    var count = exposure[i];
    if (!Number.isInteger(count) || count < 0 || count > VW_MATTE_PIXELS) return result;
    total += count;
    if (i > 6 || !count) continue;
    var nearest = Math.max(i / 32, Math.min((i + 1) / 32, luminance));
    if ((Math.max(nearest, luminance) + .05) / (Math.min(nearest, luminance) + .05) < 3) {
      poor += count; upper = Math.max(upper, (i + 1) / 32);
    }
  }
  if (!total || total > VW_MATTE_PIXELS) return result;
  result.exposed = total; result.poor = poor; result.upper = upper;
  if (!poor) { result.choice = 0; return result; }
  if (poor < Math.max(4, Math.ceil(total * .01))) return result;
  var required = 3 * (upper + .05) - .05;
  var encoded = required <= .0031308 ? required * 12.92 : 1.055 * Math.pow(required, 1 / 2.4) - .055;
  var gray = Math.min(255, Math.ceil(encoded * 255)), channel = gray / 255;
  var actual = channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
  var hex = gray.toString(16).padStart(2, "0");
  result.choice = 1; result.required = required; result.achieved = (actual + .05) / (upper + .05);
  result.colour = "#" + hex + hex + hex;
  return result;
}

function vwPaperBacking(exposure, luminance) {
  return vwPaperAssessment(exposure, luminance).choice;
}
