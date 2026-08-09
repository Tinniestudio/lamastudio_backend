package com.tinniestudio.api.modules.analytics.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Pure helpers for ISO-8601 week boundaries (Monday-start, Sunday-end) used by the
 * weekly analytics rollup (Batch 16 #6: "one point per calendar week, mon-sun" —
 * a fixed calendar week, not a rolling 7-day window).
 */
public final class IsoWeekUtil {

    private IsoWeekUtil() {}

    /** Returns the Monday on/before {@code date} — the start of {@code date}'s ISO week. */
    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Returns the Sunday on/after {@code date} — the end of {@code date}'s ISO week. */
    public static LocalDate sundayOf(LocalDate date) {
        return mondayOf(date).plusDays(6);
    }
}
