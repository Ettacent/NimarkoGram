package com.exteragram.messenger.badges;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ApiBadgeSource {

    public final ConcurrentHashMap<Long, BadgeEntry> cache = new BridgeCacheMap();

    private final app.nimarkogram.messenger.badges.ApiBadgeSource real;

    public ApiBadgeSource(app.nimarkogram.messenger.badges.ApiBadgeSource real) {
        this.real = real;
    }

    public ApiBadgeSource() {
        this(app.nimarkogram.messenger.badges.BadgesController.getInstance().apiBadgeSource);
    }

    public void forceNotify() {
        real.forceNotify();
    }

    private final class BridgeCacheMap extends ConcurrentHashMap<Long, BadgeEntry> {

        @Override
        public int size() {
            return real.cache.size();
        }

        @Override
        public boolean isEmpty() {
            return real.cache.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return real.cache.containsKey(key);
        }

        @Override
        public BadgeEntry get(Object key) {
            return BadgeEntry.fromReal(real.cache.get(key));
        }

        @Override
        public BadgeEntry put(Long key, BadgeEntry value) {
            app.nimarkogram.messenger.badges.BadgeEntry prev =
                    real.cache.put(key, value != null ? value.toReal() : null);
            return BadgeEntry.fromReal(prev);
        }

        @Override
        public BadgeEntry remove(Object key) {
            return BadgeEntry.fromReal(real.cache.remove(key));
        }

        @Override
        public void clear() {
            real.cache.clear();
        }

        @Override
        public Set<Map.Entry<Long, BadgeEntry>> entrySet() {
            final Set<Map.Entry<Long, app.nimarkogram.messenger.badges.BadgeEntry>> backing =
                    real.cache.entrySet();
            return new AbstractSet<Map.Entry<Long, BadgeEntry>>() {
                @Override
                public Iterator<Map.Entry<Long, BadgeEntry>> iterator() {
                    final Iterator<Map.Entry<Long, app.nimarkogram.messenger.badges.BadgeEntry>> it =
                            backing.iterator();
                    return new Iterator<Map.Entry<Long, BadgeEntry>>() {
                        @Override public boolean hasNext() { return it.hasNext(); }
                        @Override public Map.Entry<Long, BadgeEntry> next() {
                            final Map.Entry<Long, app.nimarkogram.messenger.badges.BadgeEntry> e = it.next();
                            return new AbstractMap.SimpleEntry<>(e.getKey(), BadgeEntry.fromReal(e.getValue()));
                        }
                        @Override public void remove() { it.remove(); }
                    };
                }
                @Override public int size() { return backing.size(); }
            };
        }

        @Override
        public Set<Long> keySet() {
            return real.cache.keySet();
        }

        @Override
        public Collection<BadgeEntry> values() {
            final Collection<app.nimarkogram.messenger.badges.BadgeEntry> backing = real.cache.values();
            return new java.util.AbstractCollection<BadgeEntry>() {
                @Override public Iterator<BadgeEntry> iterator() {
                    final Iterator<app.nimarkogram.messenger.badges.BadgeEntry> it = backing.iterator();
                    return new Iterator<BadgeEntry>() {
                        @Override public boolean hasNext() { return it.hasNext(); }
                        @Override public BadgeEntry next() { return BadgeEntry.fromReal(it.next()); }
                        @Override public void remove() { it.remove(); }
                    };
                }
                @Override public int size() { return backing.size(); }
            };
        }
    }
}
