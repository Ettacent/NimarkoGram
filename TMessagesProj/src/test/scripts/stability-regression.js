const cp = require('node:child_process');
const path = require('node:path');

const tests = [
    'preview-rotation-regression.js',
    'round-video-return-regression.js',
    'story-pause-frame-regression.js',
    'chat-date-position-regression.js',
    'poll-menu-reactions-regression.js',
    'topics-typing-width-regression.js',
    'folder-icon-transition-regression.js',
    'folder-selection-regression.js',
    'rich-message-viewport-regression.js',
    'composer-controls-width-regression.js',
    'media-glow-dismiss-regression.js',
    'paid-reaction-particles-regression.js',
    'quote-reactions-qr-regression.js',
    'profile-common-groups-scroll-regression.js',
    'profile-loading-rows-regression.js',
    'section-header-background-regression.js',
    'profile-rating-transition-regression.js',
    'caption-quote-width-regression.js',
    'message-menu-profile-entry-regression.js',
];
let failed = 0;
for (const test of tests) {
    console.log(`\nRunning ${test}`);
    const result = cp.spawnSync(process.execPath, [path.join(__dirname, test)], {
        cwd: __dirname,
        stdio: 'inherit',
        timeout: 120000,
    });
    if (result.error || result.status !== 0) {
        failed++;
        console.error(`FAIL: ${test}: ${result.error?.message || result.signal || result.status}`);
    }
}
console.log(`\nStability regression: ${tests.length - failed}/${tests.length} passed`);
process.exitCode = failed ? 1 : 0;
