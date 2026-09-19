const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/org/telegram/ui/Cells/ChatMessageCell.java'), 'utf8');
assert(!source.includes('imageDrawn = photoImage.draw(canvas);'));
const start = source.indexOf('protected boolean drawPhotoImage(Canvas canvas)');
const end = source.indexOf('private boolean drawPhotoImageInternal(Canvas canvas)', start);
const draw = source.slice(start, end);
assert(draw.includes('skipFrameUpdate || drawForBlur'));
assert(draw.includes('SizeNotifierFrameLayout.drawingBlur'));
assert(draw.includes('canvas instanceof SizeNotifierFrameLayout.SimplerCanvas'));
assert(draw.includes('finally {\n            photoImage.setSkipUpdateFrame(skipFrameUpdate);'));
assert(draw.includes('return drawPhotoImageInternal(canvas);'));
const inline = source.slice(source.indexOf('final int photoWidth = width - dp(17);'),
    source.indexOf('private boolean allowDrawPhotoImage'));
assert((inline.match(/imageDrawn = drawPhotoImage\(canvas\)/g) || []).length >= 6);
console.log('PASS: six inline branches share media renderer; blur-pass frame guard restored in finally');
