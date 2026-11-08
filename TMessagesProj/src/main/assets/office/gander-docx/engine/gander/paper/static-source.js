/* Modifications Copyright (C) 2026 Ettacent */

"use strict";

var OfficeStaticSource = (function () {
  var MAX_BYTES = 2097152, MAX_PIXELS = 350000;
  function inspect(bytes, expectedWidth, expectedHeight) {
    var no = function (reason) { return {ok:false, reason:reason}; };
    if (!(bytes instanceof Uint8Array) || bytes.length < 10 || bytes.length > MAX_BYTES) return no(1);
    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    function dimensions(w, h) {
      return Number.isInteger(w) && Number.isInteger(h) && w >= 8 && h >= 8 && w <= 2048 && h <= 2048 &&
        w * h <= MAX_PIXELS && (expectedWidth===undefined || w===expectedWidth) && (expectedHeight===undefined || h===expectedHeight);
    }
    function ascii(at, length) {
      var result = ""; for (var i=0; i<length; i++) result += String.fromCharCode(bytes[at+i]); return result;
    }
    if (bytes.length >= 45 && view.getUint32(0) === 0x89504e47 && view.getUint32(4) === 0x0d0a1a0a) {
      var data = false, width = 0, height = 0, srgb = false, chromaticity = false;
      for (var p=8, chunks=0; p <= bytes.length-12 && chunks<4096; chunks++) {
        var length = view.getUint32(p), type = ascii(p+4,4);
        if (length > bytes.length-p-12) return no(3);
        if (p===8) {
          if (type!=="IHDR" || length!==13) return no(3);
          width=view.getUint32(p+8);height=view.getUint32(p+12);
          if (!dimensions(width,height)) return no(6);
        }
        if (type==="acTL" || type==="fdAT") return no(4);
        if (["iCCP","cICP","eXIf"].indexOf(type)>=0) return no(5);
        if (type==="cHRM") {
          var standard=[31270,32900,64000,33000,30000,60000,15000,6000];
          if(length!==32 || standard.some(function(v,i){return view.getUint32(p+8+i*4)!==v;}))return no(5);
          chromaticity=true;
        }
        if (type==="gAMA" && (length!==4 || view.getUint32(p+8)!==45455)) return no(5);
        if (type==="sRGB") { if(length!==1 || bytes[p+8]>3)return no(5);srgb=true; }
        if (type==="IDAT") data=true;
        if (type==="IEND") { if(chromaticity&&!srgb)return no(5);return length===0 && data && p+12===bytes.length ? {ok:true,format:1,width:width,height:height} : no(3); }
        p+=length+12;
      }
      return no(3);
    }
    if (bytes[0]!==255 || bytes[1]!==216) return no(2);
    var p=2, frame=false, scans=0, entropy=false, width=0, height=0;
    for (var markers=0; p<bytes.length && markers<4096; markers++) {
      var marker=-1;
      if (entropy) {
        while (p<bytes.length) {
          if (bytes[p++]!==255) continue;
          while (p<bytes.length && bytes[p]===255) p++;
          if (p===bytes.length) return no(3);
          marker=bytes[p++];
          if (marker===0 || marker>=208 && marker<=215) { marker=-1; continue; }
          entropy=false; break;
        }
        if (marker<0) return no(3);
      } else {
        if (bytes[p++]!==255) return no(3);
        while (p<bytes.length && bytes[p]===255) p++;
        if (p===bytes.length) return no(3);
        marker=bytes[p++];
      }
      if (marker===217) return frame && scans && p===bytes.length ? {ok:true,format:2,width:width,height:height} : no(4);
      if (marker===216 || marker>=208 && marker<=215 || marker===0) return no(4);
      if (p+2>bytes.length) return no(3);
      var size=view.getUint16(p);
      if (size<2 || size>bytes.length-p) return no(3);
      if (marker>=225 && marker<=239) return no(5);
      if (marker===192 || marker===194) {
        if (frame || size<11 || bytes[p+2]!==8 || (bytes[p+7]!==1 && bytes[p+7]!==3) || size!==8+3*bytes[p+7]) return no(4);
        height=view.getUint16(p+3);width=view.getUint16(p+5);if(!dimensions(width,height))return no(6);frame=true;
      } else if (marker>=193 && marker<=207 && marker!==196) return no(4);
      if (marker===218) { if(!frame || size<6 || ++scans>256)return no(3);entropy=true; }
      p+=size;
    }
    return no(3);
  }
  return Object.freeze({inspect:inspect,maxBytes:MAX_BYTES,maxPixels:MAX_PIXELS});
})();
