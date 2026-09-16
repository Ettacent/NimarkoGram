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
        public void putAll(Map<? extends Long, ? extends BadgeEntry> values) {
            values.forEach(this::put);
        }
        @Override
        public BadgeEntry putIfAbsent(Long key, BadgeEntry value) {
            return BadgeEntry.fromReal(real.cache.putIfAbsent(key, value.toReal()));
        }
        @Override
        public boolean remove(Object key, Object value) {
            return value instanceof BadgeEntry && real.cache.remove(key, ((BadgeEntry) value).toReal());
        }
        @Override
        public BadgeEntry replace(Long key, BadgeEntry value) {
            return BadgeEntry.fromReal(real.cache.replace(key, value.toReal()));
        }
        @Override
        public boolean replace(Long key, BadgeEntry previous, BadgeEntry value) {
            return real.cache.replace(key, previous.toReal(), value.toReal());
        }
        @Override
        public BadgeEntry computeIfAbsent(Long key, java.util.function.Function<? super Long, ? extends BadgeEntry> function) {
            java.util.Objects.requireNonNull(function);
            return BadgeEntry.fromReal(real.cache.computeIfAbsent(key, id -> {
                BadgeEntry value = function.apply(id);
                return value == null ? null : value.toReal();
            }));
        }
        @Override
        public BadgeEntry computeIfPresent(Long key, java.util.function.BiFunction<? super Long, ? super BadgeEntry, ? extends BadgeEntry> function) {
            java.util.Objects.requireNonNull(function);
            return BadgeEntry.fromReal(real.cache.computeIfPresent(key, (id, previous) -> {
                BadgeEntry value = function.apply(id, BadgeEntry.fromReal(previous));
                return value == null ? null : value.toReal();
            }));
        }
        @Override
        public BadgeEntry compute(Long key, java.util.function.BiFunction<? super Long, ? super BadgeEntry, ? extends BadgeEntry> function) {
            java.util.Objects.requireNonNull(function);
            return BadgeEntry.fromReal(real.cache.compute(key, (id, previous) -> {
                BadgeEntry value = function.apply(id, BadgeEntry.fromReal(previous));
                return value == null ? null : value.toReal();
            }));
        }
        @Override
        public BadgeEntry merge(Long key, BadgeEntry value, java.util.function.BiFunction<? super BadgeEntry, ? super BadgeEntry, ? extends BadgeEntry> function) {
            java.util.Objects.requireNonNull(function);
            return BadgeEntry.fromReal(real.cache.merge(key, value.toReal(), (previous, incoming) -> {
                BadgeEntry merged = function.apply(BadgeEntry.fromReal(previous), BadgeEntry.fromReal(incoming));
                return merged == null ? null : merged.toReal();
            }));
        }
        @Override
        public void replaceAll(java.util.function.BiFunction<? super Long, ? super BadgeEntry, ? extends BadgeEntry> function) {
            java.util.Objects.requireNonNull(function);
            real.cache.replaceAll((id, value) -> function.apply(id, BadgeEntry.fromReal(value)).toReal());
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
