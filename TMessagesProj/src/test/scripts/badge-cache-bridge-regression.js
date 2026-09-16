const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/com/exteragram/messenger/badges/ApiBadgeSource.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start), depth = 1;
    while (depth && ++end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    return source.slice(start, end + 1).replaceAll('app.nimarkogram.messenger.badges.BadgeEntry', 'RealEntry');
}
const methods = [
    'public BadgeEntry get(', 'public BadgeEntry put(', 'public BadgeEntry remove(',
    'public void putAll(', 'public BadgeEntry putIfAbsent(', 'public boolean remove(',
    'public BadgeEntry replace(', 'public boolean replace(', 'public BadgeEntry computeIfAbsent(',
    'public BadgeEntry computeIfPresent(', 'public BadgeEntry compute(', 'public BadgeEntry merge(',
    'public void replaceAll(', 'public void clear(', 'public int size('
].map(method).join('\n');
const code = `
import java.util.*;
import java.util.concurrent.*;
class RealEntry {
 final int value; RealEntry(int v) { value=v; }
 public boolean equals(Object o) { return o instanceof RealEntry && ((RealEntry)o).value==value; }
 public int hashCode() { return value; }
}
class BadgeEntry {
 final int value; BadgeEntry(int v) { value=v; }
 RealEntry toReal() { return new RealEntry(value); }
 static BadgeEntry fromReal(RealEntry r) { return r==null?null:new BadgeEntry(r.value); }
}
public class BadgeBridgeTest {
 static class Source { final ConcurrentHashMap<Long,RealEntry> cache=new ConcurrentHashMap<>(); }
 static class Bridge extends ConcurrentHashMap<Long,BadgeEntry> {
  final Source real = new Source();
  ${methods}
 }
 static void check(boolean b) { if(!b) throw new AssertionError(); }
 public static void main(String[] args) {
  Bridge b=new Bridge();
  b.put(1L,new BadgeEntry(10));
  check(b.replace(1L,new BadgeEntry(11)).value==10);
  check(b.real.cache.get(1L).value==11);
  check(!b.remove(1L,new BadgeEntry(10)));
  check(b.replace(1L,new BadgeEntry(11),new BadgeEntry(12)));
  check(b.putIfAbsent(1L,new BadgeEntry(99)).value==12);
  b.putAll(Map.of(2L,new BadgeEntry(20)));
  b.computeIfAbsent(2L,k->{throw new AssertionError();});
  b.computeIfPresent(2L,(k,v)->new BadgeEntry(v.value+1));
  check(b.real.cache.get(2L).value==21);
  b.compute(2L,(k,v)->null); check(!b.real.cache.containsKey(2L));
  b.merge(1L,new BadgeEntry(8),(a,c)->new BadgeEntry(a.value+c.value));
  b.replaceAll((k,v)->new BadgeEntry(v.value+1));
  check(b.real.cache.get(1L).value==21);
  check(b.remove(1L,new BadgeEntry(21))); check(b.size()==0);
  b.computeIfAbsent(3L,k->new BadgeEntry(3));
  b.merge(3L,new BadgeEntry(3),(a,c)->null); check(b.size()==0);
  b.put(4L,new BadgeEntry(4)); b.clear(); check(b.real.cache.isEmpty());
  System.out.println("PASS: badge bridge updates and removals reach authoritative cache");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'badge-bridge-'));
try {
    fs.writeFileSync(path.join(dir, 'BadgeBridgeTest.java'), code);
    cp.execFileSync('javac', ['BadgeBridgeTest.java'], {cwd: dir});
    process.stdout.write(cp.execFileSync('java', ['BadgeBridgeTest'], {cwd: dir}));
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
