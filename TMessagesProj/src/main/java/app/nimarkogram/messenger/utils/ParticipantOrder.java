/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

public final class ParticipantOrder {
    private ParticipantOrder() {
    }

    public static <T> ArrayList<T> reconcile(List<T> latest, List<Long> previousIds,
                                            ToLongFunction<T> id) {
        ArrayList<T> result = new ArrayList<>();
        if (latest == null || latest.isEmpty()) return result;
        Map<Long, T> remaining = new LinkedHashMap<>();
        for (T item : latest) {
            if (item == null) continue;
            long key = id.applyAsLong(item);
            if (key != 0) remaining.put(key, item);
        }
        if (previousIds != null) {
            for (Long key : previousIds) {
                if (key == null || key == 0) continue;
                T item = remaining.remove(key);
                if (item != null) result.add(item);
            }
        }
        for (T item : latest) {
            if (item == null) continue;
            long key = id.applyAsLong(item);
            T current = key == 0 ? item : remaining.remove(key);
            if (current != null) result.add(current);
        }
        return result;
    }
}
