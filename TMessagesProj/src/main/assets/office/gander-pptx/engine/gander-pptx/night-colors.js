/* Modifications Copyright (C) 2026 Ettacent */

var VW_NIGHT_MATRIX = [
  [0.574, -1.430, -0.144, 1],
  [-0.426, -0.430, -0.144, 1],
  [-0.426, -1.430, 0.856, 1]
];

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
