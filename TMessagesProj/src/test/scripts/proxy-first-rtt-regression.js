const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../../jni/tgnet/ConnectionsManager.cpp'), 'utf8');
const guard = source.match(/if \((connectionType == ConnectionTypeGeneric && connection->getConnectionNum\(\) == 0[\s\S]*?&& !sendingPing)\) \{/);
assert(guard);
assert(source.includes('mainConnectionRtt.matches(response->ping_id, connection->getConnectionToken(), currentDatacenterId)'));
assert(source.includes('measured == MainConnectionRtt::WarmedUp'));
assert(source.includes('pingTimeMs = getCurrentTimeMonotonicMillis();\n        lastPingTime = pingTimeMs;'));
const cpp = `#include <cassert>
#include <string>
#include "MainConnectionRtt.h"
int main(){
 const int ConnectionTypeGeneric=1;int connectionType=1,currentDatacenterId=2;
 MainConnectionRtt mainConnectionRtt;
 auto getCurrentTimeMonotonicMillis=[](){return int64_t(2000);};
 bool sendingPing=false;std::string proxyAddress="127.0.0.1";
 struct Connection {int num=0;int getConnectionNum(){return num;}} c;auto connection=&c;
 struct Dc {int id=2;int getDatacenterId(){return id;}} dc;auto datacenter=&dc;
 auto needsPing=[&](){return ${guard[1]};};
 assert(needsPing());sendingPing=true;assert(!needsPing());sendingPing=false;
 c.num=1;assert(!needsPing());c.num=0;
 dc.id=3;assert(!needsPing());dc.id=2;
 connectionType=2;assert(!needsPing());connectionType=1;
 proxyAddress.clear();assert(!needsPing());
 mainConnectionRtt.sent(1,10,2,1000);
 assert(mainConnectionRtt.received(999,10,2,1200)==MainConnectionRtt::Ignored);
 assert(mainConnectionRtt.received(1,11,2,1200)==MainConnectionRtt::Ignored);
 assert(mainConnectionRtt.received(1,10,3,1200)==MainConnectionRtt::Ignored);
 assert(mainConnectionRtt.received(1,10,2,1700)==MainConnectionRtt::WarmedUp);
 assert(mainConnectionRtt.value(1700)==0);
 mainConnectionRtt.sent(2,10,2,1700);
 assert(mainConnectionRtt.received(2,10,2,1740)==MainConnectionRtt::Measured);
 assert(mainConnectionRtt.value(1740)==40);
 assert(mainConnectionRtt.received(2,10,2,1800)==MainConnectionRtt::Ignored);
 mainConnectionRtt.sent(3,10,2,1800);mainConnectionRtt.received(3,10,2,1860);
 assert(mainConnectionRtt.value(1860)==50);
 proxyAddress="127.0.0.1";assert(!needsPing());
 assert(mainConnectionRtt.value(62000)==0);
 mainConnectionRtt.sent(4,11,2,62001);assert(mainConnectionRtt.value(62001)==0);
 assert(mainConnectionRtt.received(4,11,2,63001)==MainConnectionRtt::WarmedUp);
 mainConnectionRtt.sent(5,11,2,63001);mainConnectionRtt.received(5,11,2,63036);
 assert(mainConnectionRtt.value(63036)==35);
 mainConnectionRtt.reset();assert(mainConnectionRtt.value(63036)==0);
 assert(mainConnectionRtt.received(5,11,2,63040)==MainConnectionRtt::Ignored);
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-first-rtt-'));
try {
    fs.writeFileSync(path.join(tmp, 'test.cpp'), cpp);
    cp.execFileSync('c++', ['-std=c++17', '-I', path.resolve(__dirname, '../../../jni/tgnet'), 'test.cpp', '-o', 'test'], {cwd: tmp});
    cp.execFileSync(path.join(tmp, 'test'));
    console.log('PASS: real RTT helper: warmup excluded, id/token/DC matching, duplicate rejected, expiry, route reset, fresh smoothing; native connection guard');
} finally {
    fs.rmSync(tmp, {recursive: true, force: true});
}
