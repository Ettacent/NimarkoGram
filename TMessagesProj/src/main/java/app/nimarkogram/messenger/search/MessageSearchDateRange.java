/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.search;

import java.util.Calendar;
import java.util.TimeZone;

public final class MessageSearchDateRange {
    public static final MessageSearchDateRange ALL = new MessageSearchDateRange(0, 0, 0, 0);

    private final long startDayMillis;
    private final long endDayMillis;
    private final int minDate;
    private final int maxDate;

    private MessageSearchDateRange(long startDayMillis, long endDayMillis, int minDate, int maxDate) {
        this.startDayMillis = startDayMillis;
        this.endDayMillis = endDayMillis;
        this.minDate = minDate;
        this.maxDate = maxDate;
    }

    public static MessageSearchDateRange ofDays(long firstMillis, long lastMillis) {
        return ofDays(firstMillis, lastMillis, TimeZone.getDefault());
    }

    public static MessageSearchDateRange ofDays(long firstMillis, long lastMillis, TimeZone timeZone) {
        if (timeZone == null || firstMillis <= 0 || lastMillis <= 0
                || firstMillis / 1000L > Integer.MAX_VALUE || lastMillis / 1000L > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported message search dates");
        }
        TimeZone capturedZone = (TimeZone) timeZone.clone();
        Calendar first = dayStart(firstMillis, capturedZone);
        Calendar last = dayStart(lastMillis, capturedZone);
        long start = first.getTimeInMillis();
        long end = last.getTimeInMillis();
        if (start > end) throw new IllegalArgumentException("Reversed message search dates");
        Calendar following = (Calendar) last.clone();
        following.add(Calendar.DAY_OF_MONTH, 1);
        long until = dayStart(following.getTimeInMillis(), capturedZone).getTimeInMillis();
        long minimum = start / 1000L - 1;
        long maximum = until / 1000L;
        if (start <= 0 || until <= end || start % 1000L != 0 || until % 1000L != 0
                || minimum <= 0 || maximum > Integer.MAX_VALUE || maximum <= minimum) {
            throw new IllegalArgumentException("Message search dates exceed protocol bounds");
        }
        return new MessageSearchDateRange(start, end, (int) minimum, (int) maximum);
    }

    private static Calendar dayStart(long millis, TimeZone timeZone) {
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long start = calendar.getTimeInMillis();
        Calendar previous = (Calendar) calendar.clone();
        previous.add(Calendar.DAY_OF_MONTH, -1);
        long rollback = (long) timeZone.getOffset(previous.getTimeInMillis()) - timeZone.getOffset(start);
        if (rollback > 0) {
            Calendar earlier = (Calendar) calendar.clone();
            earlier.setTimeInMillis(start - rollback);
            if (earlier.get(Calendar.ERA) == calendar.get(Calendar.ERA)
                    && earlier.get(Calendar.YEAR) == calendar.get(Calendar.YEAR)
                    && earlier.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)) calendar = earlier;
        }
        return calendar;
    }

    public boolean isActive() { return minDate != 0; }
    public int getMinDate() { return minDate; }
    public int getMaxDate() { return maxDate; }
    public long getStartDayMillis() { return startDayMillis; }
    public long getEndDayMillis() { return endDayMillis; }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof MessageSearchDateRange)) return false;
        MessageSearchDateRange other = (MessageSearchDateRange) object;
        return startDayMillis == other.startDayMillis && endDayMillis == other.endDayMillis
                && minDate == other.minDate && maxDate == other.maxDate;
    }

    @Override
    public int hashCode() {
        int result = (int) (startDayMillis ^ startDayMillis >>> 32);
        result = 31 * result + (int) (endDayMillis ^ endDayMillis >>> 32);
        result = 31 * result + minDate;
        return 31 * result + maxDate;
    }
}
