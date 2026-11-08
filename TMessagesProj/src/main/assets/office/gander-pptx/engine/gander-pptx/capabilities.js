/* Modifications Copyright (C) 2026 Ettacent */


(() => {
  'use strict';
  const REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const XML_LIMIT = 2 * 1024 * 1024;
  const reject = feature => { throw {officeCode: 3, feature}; };
  function parse(text) {
    if (/<!DOCTYPE|<!ENTITY/i.test(text)) reject('xmlDeclaration');
    const xml = new DOMParser().parseFromString(text, 'application/xml');
    if (xml.querySelector('parsererror')) throw {officeCode: 1};
    return xml;
  }
  function resolve(part, target) {
    if (!target || /[\\%?#]/.test(target) || /^[a-z][a-z0-9+.-]*:/i.test(target)) reject('packageResourcePath');
    const components = target.startsWith('/') ? [] : part.split('/').slice(0, -1);
    for (const item of target.split('/')) {
      if (!item || item === '.') continue;
      if (item === '..') { if (!components.length) reject('packageResourcePath'); components.pop(); }
      else components.push(item);
    }
    return components.join('/');
  }
  function safeSVG(text) {
    if(text.length>XML_LIMIT)reject('candidateImageLimit');
    const xml = parse(text);
    if(xml.getElementsByTagName('*').length>40000)reject('candidateNodeLimit');
    for (const node of xml.getElementsByTagName('*')) {
      if (/^(script|foreignObject|iframe|object|embed|audio|video|animate|animateTransform|set)$/i.test(node.localName)) reject('activeSvg');
      if (node.localName === 'style' && /@import|url\(|\\/i.test(node.textContent)) reject('externalSvg');
      for (const attr of node.attributes) {
        if (/^on/i.test(attr.name)) reject('activeSvg');
        if (/href$/.test(attr.localName) && !/^#/.test(attr.value)) reject('externalSvg');
        if (/url\(/i.test(attr.value) && !/^url\(\s*['"]?#[\w:.-]+['"]?\s*\)$/i.test(attr.value)) reject('externalSvg');
      }
    }
  }
  function animatedRaster(data, png) {
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    let at = png ? 8 : 12;
    while (at + (png ? 12 : 8) <= data.length) {
      const size = view.getUint32(at + (png ? 0 : 4), !png);
      const type = view.getUint32(at + (png ? 4 : 0));
      const step = size + (png ? 12 : 8 + (size & 1));
      if (step > data.length - at) reject('unsupportedImageOrMedia');
      if (type === (png ? 0x6163544c : 0x414e494d)) return true;
      if (png && type === 0x49454e44) break;
      at += step;
    }
    return false;
  }
  function inspect(bytes, guard) {
    guard();
    const zip = new JSZip();
    vwOpenPackage.call(zip, bytes);
    const entries = Object.keys(zip.files);
    if (entries.length > 4096) reject('candidateEntryLimit');
    if (!zip.file('ppt/presentation.xml') || !zip.file('ppt/_rels/presentation.xml.rels')) throw {officeCode: 1};
    let total = 0, elements = 0, imageCount = 0;
    const documents = new Map(), imageParts=[];
    for (const name of entries) {
      guard();
      const entry = zip.files[name];
      if (entry.dir) continue;
      if (/^ppt\/(charts|diagrams|embeddings|activeX)\//i.test(name) || /vbaProject/i.test(name)) reject('chartDiagramOleOrMacro');
      if (/^ppt\/media\//i.test(name)) {
        if (++imageCount > 256) reject('candidateImageLimit');
        if (!/\.(png|jpe?g|gif|bmp|webp|svg)$/i.test(name)) reject('unsupportedImageOrMedia');
        const data = entry.asUint8Array();
        if (data.byteLength > 12 * 1024 * 1024) reject('candidateImageLimit');
        if(/\.png$/i.test(name)&&data.length>=24){
          const view=new DataView(data.buffer,data.byteOffset,data.byteLength);
          if(view.getUint32(0)!==0x89504e47||view.getUint32(4)!==0x0d0a1a0a)reject('unsupportedImageOrMedia');
          if(view.getUint32(16)*view.getUint32(20)>16*1024*1024)reject('candidateImageLimit');
        }
        if (/\.svg$/i.test(name)) safeSVG(new TextDecoder().decode(data));
        if (/\.png$/i.test(name) && animatedRaster(data, true)) reject('animatedImage');
        if (/\.webp$/i.test(name) && animatedRaster(data, false)) reject('animatedImage');
        if (/\.gif$/i.test(name)) reject('animatedImage');
        const ext=name.split('.').at(-1).toLowerCase();
        imageParts.push({data,type:'image/'+(ext==='jpg'?'jpeg':ext==='svg'?'svg+xml':ext)});
      }
      if (!/^(ppt\/.*\.xml|ppt\/.*\.rels|\[Content_Types\]\.xml)$/i.test(name)) continue;
      const text = entry.asText();
      total += text.length;
      if (text.length > XML_LIMIT || total > 12 * 1024 * 1024) reject('candidateXmlLimit');
      const xml = parse(text);
      const nodes = xml.getElementsByTagName('*');
      elements += nodes.length;
      if (nodes.length > 40000 || elements > 160000) reject('candidateNodeLimit');
      documents.set(name, xml);
      for (const node of nodes) {
        const local = node.localName;
        if (node.namespaceURI?.startsWith('http://purl.oclc.org/ooxml')) reject('strictOoxmlNamespace');
        const expectedPrefix = {
          'http://schemas.openxmlformats.org/presentationml/2006/main': 'p',
          'http://schemas.openxmlformats.org/drawingml/2006/main': 'a'
        }[node.namespaceURI];
        if (expectedPrefix && node.prefix !== expectedPrefix) reject('unsupportedNamespaceAlias');
        for (const attr of node.attributes) {
          if (attr.namespaceURI === REL && attr.prefix !== 'r') reject('unsupportedNamespaceAlias');
        }
        if (local === 'pattFill' && node.parentNode?.localName !== 'bgPr') reject('unsupportedShapePattern');
        if (local === 'tile') reject('unsupportedPictureTile');
        if (local === 'prstGeom' && node.parentNode?.parentNode?.localName === 'pic' && node.getAttribute('prst') !== 'rect') reject('unsupportedPictureGeometry');
        if (local === 'fillRect' || local === 'srcRect') {
          const picture = node.parentNode?.parentNode;
          for (const attr of node.attributes) {
            if (['l','r','t','b'].includes(attr.localName) && Number(attr.value) !== 0 &&
                (local === 'fillRect' || picture?.localName !== 'pic')) reject('unsupportedPictureFillRectangle');
          }
        }
        if (local === 'blip') {
          for (const child of node.children) {
            if (!['alphaModFix', 'extLst'].includes(child.localName)) reject('unsupportedPictureEffect');
          }
        }
        if (/^(chart|relIds|oleObj|oMath|oMathPara|videoFile|audioFile|media|contentPart|webVideoPr|AlternateContent|blipOleObj)$/.test(local)) reject('unsupportedEmbeddedContent');
        if ((local === 'Default' || local === 'Override') && node.hasAttribute?.('ContentType')) {
          const mime = node.getAttribute('ContentType').toLowerCase();
          if (/^(audio|video)\//.test(mime) || /^image\//.test(mime) && !/^image\/(png|jpe?g|gif|bmp|webp|svg\+xml)$/.test(mime)) reject('unsupportedImageOrMedia');
        }
        if (local === 'graphicData' && !/\/table$/.test(node.getAttribute('uri') || '')) reject('unsupportedGraphicFrame');
        if (local === 'prstGeom' && !OfficePptxShapes.has(node.getAttribute('prst'))) reject('unsupportedPresetGeometry');
        if (local === 'Relationship') {
          const type = node.getAttribute('Type') || '', target = node.getAttribute('Target') || '';
          if (/\/(chart|diagramData|diagramLayout|diagramColors|diagramQuickStyle|oleObject|video|audio|media|package|control)$/.test(type)) reject('unsupportedResource');
          if (node.getAttribute('TargetMode')?.toLowerCase() === 'external') {
            if (!/\/hyperlink$/.test(type)) reject('externalResource');
          } else if (target) {
            const owner = name.replace(/_rels\//, '').replace(/\.rels$/, '');
            const resource = resolve(owner, target);
            if (!zip.file(resource)) reject('missingResource');
          }
        }
        if (local === 'sldSz') {
          const width = +node.getAttribute('cx') / 9525, height = +node.getAttribute('cy') / 9525;
          if (!(width > 0 && height > 0 && width <= 8192 && height <= 8192)) reject('candidateGeometryLimit');
        }
      }
    }
    const presentation = documents.get('ppt/presentation.xml');
    if (presentation.documentElement.prefix !== 'p') reject('unsupportedNamespaceAlias');
    const relationships = documents.get('ppt/_rels/presentation.xml.rels');
    const map = new Map([...relationships.getElementsByTagName('*')]
      .filter(n => n.localName === 'Relationship').map(n => [n.getAttribute('Id'), resolve('ppt/presentation.xml', n.getAttribute('Target'))]));
    const order = [...presentation.getElementsByTagName('*')].filter(n => n.localName === 'sldId')
      .map(n => map.get(n.getAttributeNS(REL, 'id')));
    if (!order.length || order.length > 200 || order.some(n => !n || !zip.file(n)) || new Set(order).size !== order.length) reject('candidateSlideOrderOrLimit');
    guard();
    return {order,imageParts,notes: ['staticSlidesOnly', 'fontAndLayoutApproximate']};
  }
  window.OfficePptxCapabilities = Object.freeze({inspect, safeSVG});
})();
