const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');

const root = path.resolve(__dirname, '../../../..');
const apk = process.argv[2];
const assets = path.join(root, 'TMessagesProj_AppStandalone/build/generated/telegramStrings/afatStandalone/assets');
const hash = value => {
    let result = 0;
    for (let i = 0; i < value.length; i++) result = (Math.imul(result, 31) + value.charCodeAt(i)) | 0;
    return result;
};
function readPack(name) {
    const data = apk ? cp.execFileSync('unzip', ['-p', apk, `assets/${name}`]) : fs.readFileSync(path.join(assets, name));
    let offset = 4;
    const values = new Map();
    for (let i = 0, count = data.readInt32LE(0); i < count; i++) {
        const key = data.readInt32LE(offset);
        offset += 4;
        let length = data[offset++], header = 1;
        if (length === 254) {
            length = data.readUIntLE(offset, 3);
            offset += 3;
            header = 4;
        }
        assert(offset + length <= data.length, 'Truncated localization');
        assert(!values.has(key), 'Duplicate localization hash');
        values.set(key, data.toString('utf8', offset, offset + length));
        offset += length + (4 - (length + header) % 4) % 4;
    }
    assert.equal(offset, data.length);
    return values;
}
const bindings = apk
    ? cp.execFileSync('unzip', ['-p', apk, 'assets/string_resource_ids.bin'])
    : fs.readFileSync(path.join(root, 'TMessagesProj_AppStandalone/build/generated/stringResourceIds/afatStandalone/assets/string_resource_ids.bin'));
const boundHashes = new Set();
assert.equal(bindings.length, 4 + bindings.readInt32LE(0) * 8);
for (let offset = 4; offset < bindings.length; offset += 8) {
    boundHashes.add(bindings.readInt32LE(offset + 4));
}
let checks = 0;
for (const [directory, language] of [['values', 'en'], ['values-ru', 'ru'], ['values-zh-rCN', 'zh_cn'], ['values-zh-rTW', 'zh_tw']]) {
    const values = readPack(`localization_${language}.bin`);
    for (const file of ['strings_nimarko.xml', 'banner_settings.xml', 'wl.xml']) {
        const xml = fs.readFileSync(path.join(root, 'TMessagesProj/src/main/res', directory, file), 'utf8');
        for (const match of xml.matchAll(/<string\s+name="([^"]+)"[^>]*>/g)) {
            assert(values.has(hash(match[1])), `${language}: missing ${match[1]} from ${file}`);
            assert(boundHashes.has(hash(match[1])), `${language}: missing resource binding for ${match[1]}`);
            checks++;
        }
    }
    assert(values.get(hash('AppName')).includes('Nimarko'), `${language}: branding lost`);
}
console.log(`PASS: ${checks} custom localization entries in generated ${apk ? 'APK' : 'packs'}, four locales and app branding`);
