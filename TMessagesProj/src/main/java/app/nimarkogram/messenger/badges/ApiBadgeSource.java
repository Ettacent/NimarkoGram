/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.badges;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
public final class ApiBadgeSource {

    private final StartupCache startupCache = new StartupCache();
    public final ConcurrentHashMap<Long, BadgeEntry> cache = startupCache;

    Map<Long, BadgeEntry> projectHydration(Map<Long, BadgeEntry> disk) {
        return startupCache.project(disk);
    }

    void hydrate(Map<Long, BadgeEntry> disk) {
        startupCache.hydrate(disk);
    }

    boolean finishHydration() {
        return startupCache.finish();
    }

    boolean clearForHydration(long key) {
        return startupCache.clearForHydration(key);
    }

    private static final class StartupState {
        final Set<Long> touched = new HashSet<>();
        boolean active = true;
        boolean dirty;

        void changed(long key) {
            if (active && key != 0) {
                touched.add(key);
                dirty = true;
            }
        }
    }

    public static final class StartupCache extends ConcurrentHashMap<Long, BadgeEntry> {
        private static final long serialVersionUID = 1L;
        private transient volatile StartupState startup = new StartupState();

        private StartupCache() {}

        Map<Long, BadgeEntry> project(Map<Long, BadgeEntry> disk) {
            StartupState state = startup;
            if (state == null) return new LinkedHashMap<>(this);
            synchronized (state) {
                Map<Long, BadgeEntry> projected = new LinkedHashMap<>(this);
                for (Long key : projected.keySet()) state.changed(key);
                for (Map.Entry<Long, BadgeEntry> row : disk.entrySet()) {
                    if (!state.touched.contains(row.getKey())) projected.putIfAbsent(row.getKey(), row.getValue());
                }
                return projected;
            }
        }

        void hydrate(Map<Long, BadgeEntry> disk) {
            StartupState state = startup;
            if (state == null) return;
            synchronized (state) {
                for (Map.Entry<Long, BadgeEntry> row : disk.entrySet()) {
                    if (!state.touched.contains(row.getKey())) super.putIfAbsent(row.getKey(), row.getValue());
                }
            }
        }


        boolean finish() {
            StartupState state = startup;
            if (state == null) return false;
            synchronized (state) {
                boolean dirty = state.dirty;
                state.active = false;
                state.touched.clear();
                startup = null;
                return dirty;
            }
        }

        boolean clearForHydration(long key) {
            StartupState state = startup;
            if (state == null) return super.remove(key) != null;
            synchronized (state) {
                BadgeEntry old = super.remove(key);
                state.changed(key);
                return old != null;
            }
        }

        @Override public BadgeEntry put(Long key, BadgeEntry value) {
            StartupState state = startup;
            if (state == null) return super.put(key, value);
            synchronized (state) {
                BadgeEntry old = super.put(key, value);
                state.changed(key);
                return old;
            }
        }

        @Override public BadgeEntry putIfAbsent(Long key, BadgeEntry value) {
            StartupState state = startup;
            if (state == null) return super.putIfAbsent(key, value);
            synchronized (state) {
                BadgeEntry old = super.putIfAbsent(key, value);
                if (old == null) state.changed(key);
                return old;
            }
        }

        @Override public void putAll(Map<? extends Long, ? extends BadgeEntry> values) {
            StartupState state = startup;
            if (state == null) { super.putAll(values); return; }
            values.forEach(this::put);
        }

        @Override public BadgeEntry remove(Object key) {
            StartupState state = startup;
            if (state == null) return super.remove(key);
            synchronized (state) {
                BadgeEntry old = super.remove(key);
                if (old != null) state.changed((Long) key);
                return old;
            }
        }

        @Override public boolean remove(Object key, Object value) {
            StartupState state = startup;
            if (state == null) return super.remove(key, value);
            synchronized (state) {
                boolean removed = super.remove(key, value);
                if (removed) state.changed((Long) key);
                return removed;
            }
        }

        @Override public void clear() {
            StartupState state = startup;
            if (state == null) { super.clear(); return; }
            synchronized (state) {
                for (Long key : super.keySet()) state.changed(key);
                super.clear();
            }
        }

        @Override public BadgeEntry replace(Long key, BadgeEntry value) {
            StartupState state = startup;
            if (state == null) return super.replace(key, value);
            synchronized (state) {
                BadgeEntry old = super.replace(key, value);
                if (old != null) state.changed(key);
                return old;
            }
        }

        @Override public boolean replace(Long key, BadgeEntry old, BadgeEntry value) {
            StartupState state = startup;
            if (state == null) return super.replace(key, old, value);
            synchronized (state) {
                boolean replaced = super.replace(key, old, value);
                if (replaced) state.changed(key);
                return replaced;
            }
        }

        @Override public BadgeEntry compute(Long key, BiFunction<? super Long, ? super BadgeEntry, ? extends BadgeEntry> function) {
            StartupState state = startup;
            if (state == null) return super.compute(key, function);
            synchronized (state) {
                boolean present = super.containsKey(key);
                BadgeEntry value = super.compute(key, function);
                if (present || value != null) state.changed(key);
                return value;
            }
        }

        @Override public BadgeEntry computeIfAbsent(Long key, Function<? super Long, ? extends BadgeEntry> function) {
            StartupState state = startup;
            if (state == null) return super.computeIfAbsent(key, function);
            synchronized (state) {
                boolean present = super.containsKey(key);
                BadgeEntry value = super.computeIfAbsent(key, function);
                if (!present && value != null) state.changed(key);
                return value;
            }
        }

        @Override public BadgeEntry computeIfPresent(Long key, BiFunction<? super Long, ? super BadgeEntry, ? extends BadgeEntry> function) {
            StartupState state = startup;
            if (state == null) return super.computeIfPresent(key, function);
            synchronized (state) {
                boolean present = super.containsKey(key);
                BadgeEntry value = super.computeIfPresent(key, function);
                if (present) state.changed(key);
                return value;
            }
        }

        @Override public BadgeEntry merge(Long key, BadgeEntry value, BiFunction<? super BadgeEntry, ? super BadgeEntry, ? extends BadgeEntry> function) {
            StartupState state = startup;
            if (state == null) return super.merge(key, value, function);
            synchronized (state) {
                BadgeEntry result = super.merge(key, value, function);
                state.changed(key);
                return result;
            }
        }

        @Override public void replaceAll(BiFunction<? super Long, ? super BadgeEntry, ? extends BadgeEntry> function) {
            StartupState state = startup;
            if (state == null) { super.replaceAll(function); return; }
            Objects.requireNonNull(function);
            synchronized (state) {
                super.replaceAll((key, old) -> {
                    BadgeEntry value = Objects.requireNonNull(function.apply(key, old));
                    state.changed(key);
                    return value;
                });
            }
        }

        private Map.Entry<Long, BadgeEntry> writableEntry(Map.Entry<Long, BadgeEntry> row) {
            return new AbstractMap.SimpleEntry<Long, BadgeEntry>(row) {
                @Override public BadgeEntry setValue(BadgeEntry value) {
                    Objects.requireNonNull(value);
                    BadgeEntry old = super.setValue(value);
                    StartupCache.this.put(getKey(), value);
                    return old;
                }
            };
        }

        private Spliterator<Map.Entry<Long, BadgeEntry>> entries(Spliterator<Map.Entry<Long, BadgeEntry>> source) {
            return new Spliterator<Map.Entry<Long, BadgeEntry>>() {
                @Override public boolean tryAdvance(Consumer<? super Map.Entry<Long, BadgeEntry>> action) {
                    Objects.requireNonNull(action);
                    return source.tryAdvance(row -> action.accept(writableEntry(row)));
                }
                @Override public Spliterator<Map.Entry<Long, BadgeEntry>> trySplit() {
                    Spliterator<Map.Entry<Long, BadgeEntry>> split = source.trySplit();
                    return split == null ? null : entries(split);
                }
                @Override public long estimateSize() { return source.estimateSize(); }
                @Override public int characteristics() { return source.characteristics(); }
            };
        }

        private Iterator<Map.Entry<Long, BadgeEntry>> entries() {
            Iterator<Map.Entry<Long, BadgeEntry>> iterator = super.entrySet().iterator();
            return new Iterator<Map.Entry<Long, BadgeEntry>>() {
                Long lastKey;
                boolean removable;
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public Map.Entry<Long, BadgeEntry> next() {
                    Map.Entry<Long, BadgeEntry> row = iterator.next();
                    lastKey = row.getKey();
                    removable = true;
                    return writableEntry(row);
                }
                @Override public void remove() {
                    if (!removable) throw new IllegalStateException();
                    StartupCache.this.remove(lastKey);
                    removable = false;
                }
            };
        }

        @Override public Set<Map.Entry<Long, BadgeEntry>> entrySet() {
            if (startup == null) return super.entrySet();
            Set<Map.Entry<Long, BadgeEntry>> backing = super.entrySet();
            return new AbstractSet<Map.Entry<Long, BadgeEntry>>() {
                @Override public int size() { return backing.size(); }
                @Override public boolean contains(Object value) { return backing.contains(value); }
                @Override public Iterator<Map.Entry<Long, BadgeEntry>> iterator() { return entries(); }
                @Override public Spliterator<Map.Entry<Long, BadgeEntry>> spliterator() {
                    return startup == null ? backing.spliterator() : entries(backing.spliterator());
                }
                @Override public boolean add(Map.Entry<Long, BadgeEntry> row) { return StartupCache.this.put(row.getKey(), row.getValue()) == null; }
                @Override public boolean remove(Object value) {
                    if (!(value instanceof Map.Entry)) return false;
                    Map.Entry<?, ?> row = (Map.Entry<?, ?>) value;
                    return row.getKey() != null && row.getValue() != null && StartupCache.this.remove(row.getKey(), row.getValue());
                }
                @Override public void clear() { StartupCache.this.clear(); }
                @Override public boolean removeIf(Predicate<? super Map.Entry<Long, BadgeEntry>> filter) {
                    if (startup == null) return backing.removeIf(filter);
                    Objects.requireNonNull(filter);
                    boolean changed = false;
                    for (Map.Entry<Long, BadgeEntry> row : backing) {
                        if (filter.test(new AbstractMap.SimpleImmutableEntry<>(row))) changed |= StartupCache.this.remove(row.getKey(), row.getValue());
                    }
                    return changed;
                }
                @Override public boolean removeAll(Collection<?> values) {
                    return startup == null ? backing.removeAll(values) : super.removeAll(values);
                }
                @Override public boolean retainAll(Collection<?> values) {
                    return startup == null ? backing.retainAll(values) : super.retainAll(values);
                }
            };
        }

        @Override public Collection<BadgeEntry> values() {
            if (startup == null) return super.values();
            Collection<BadgeEntry> backing = super.values();
            return new AbstractCollection<BadgeEntry>() {
                @Override public int size() { return backing.size(); }
                @Override public boolean contains(Object value) { return backing.contains(value); }
                @Override public Spliterator<BadgeEntry> spliterator() { return backing.spliterator(); }
                @Override public boolean add(BadgeEntry value) { return backing.add(value); }
                @Override public boolean addAll(Collection<? extends BadgeEntry> values) { return backing.addAll(values); }
                @Override public Iterator<BadgeEntry> iterator() {
                    Iterator<Map.Entry<Long, BadgeEntry>> iterator = entries();
                    return new Iterator<BadgeEntry>() {
                        @Override public boolean hasNext() { return iterator.hasNext(); }
                        @Override public BadgeEntry next() { return iterator.next().getValue(); }
                        @Override public void remove() { iterator.remove(); }
                    };
                }
                @Override public void clear() { StartupCache.this.clear(); }
                @Override public boolean remove(Object value) {
                    if (startup == null) return backing.remove(value);
                    if (value == null) return false;
                    for (Map.Entry<Long, BadgeEntry> row : StartupCache.super.entrySet()) {
                        if (value.equals(row.getValue()) && StartupCache.this.remove(row.getKey(), row.getValue())) return true;
                    }
                    return false;
                }
                @Override public boolean removeIf(Predicate<? super BadgeEntry> filter) {
                    if (startup == null) return backing.removeIf(filter);
                    Objects.requireNonNull(filter);
                    boolean changed = false;
                    for (Map.Entry<Long, BadgeEntry> row : StartupCache.super.entrySet()) {
                        if (filter.test(row.getValue())) changed |= StartupCache.this.remove(row.getKey(), row.getValue());
                    }
                    return changed;
                }
                @Override public boolean removeAll(Collection<?> values) {
                    return startup == null ? backing.removeAll(values) : super.removeAll(values);
                }
                @Override public boolean retainAll(Collection<?> values) {
                    return startup == null ? backing.retainAll(values) : super.retainAll(values);
                }
            };
        }
    }

    private final AtomicBoolean notifyPending = new AtomicBoolean(false);
    private final Runnable notifyRunnable = () -> {
        notifyPending.set(false);
        try {

            int mask = MessagesController.UPDATE_MASK_EMOJI_STATUS;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.isValidAccount(a)) {
                    NotificationCenter.getInstance(a)
                            .postNotificationName(NotificationCenter.updateInterfaces, mask);
                }
            }
        } catch (Throwable ignored) {}
    };

    void scheduleNotify() {
        if (!notifyPending.compareAndSet(false, true)) return;

        AndroidUtilities.runOnUIThread(notifyRunnable, 750);
    }

    public void forceNotify() {
        scheduleNotify();
    }

    ApiBadgeSource() {}
}
