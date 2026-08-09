package com.tinniestudio.api.modules.analytics.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IsoWeekUtil — Monday-boundary (ISO week) calculation")
class IsoWeekUtilTest {

    // 2024-01-01 is a Monday, 2024-01-07 is the following Sunday.
    private static final LocalDate MONDAY   = LocalDate.of(2024, 1, 1);
    private static final LocalDate WEDNESDAY = LocalDate.of(2024, 1, 3);
    private static final LocalDate SUNDAY   = LocalDate.of(2024, 1, 7);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2024, 1, 8);

    @Test
    @DisplayName("mondayOf(Monday) returns the same date")
    void mondayOf_onMonday_returnsSameDate() {
        assertThat(IsoWeekUtil.mondayOf(MONDAY)).isEqualTo(MONDAY);
    }

    @Test
    @DisplayName("mondayOf(mid-week) returns that week's Monday")
    void mondayOf_midWeek_returnsWeeksMonday() {
        assertThat(IsoWeekUtil.mondayOf(WEDNESDAY)).isEqualTo(MONDAY);
    }

    @Test
    @DisplayName("a Sunday event rolls into the week that STARTED the preceding Monday, " +
            "not the next Monday — the exact off-by-one this requirement calls out")
    void mondayOf_sunday_rollsIntoPrecedingMonday_notNextWeek() {
        LocalDate weekStart = IsoWeekUtil.mondayOf(SUNDAY);
        assertThat(weekStart).isEqualTo(MONDAY);
        assertThat(weekStart).isNotEqualTo(NEXT_MONDAY);
    }

    @Test
    @DisplayName("mondayOf(next Monday) advances to the new week, not the previous one")
    void mondayOf_nextMonday_advancesToNewWeek() {
        assertThat(IsoWeekUtil.mondayOf(NEXT_MONDAY)).isEqualTo(NEXT_MONDAY);
    }

    @Test
    @DisplayName("sundayOf returns Monday + 6 days")
    void sundayOf_returnsMondayPlusSixDays() {
        assertThat(IsoWeekUtil.sundayOf(WEDNESDAY)).isEqualTo(SUNDAY);
        assertThat(IsoWeekUtil.sundayOf(MONDAY)).isEqualTo(SUNDAY);
        assertThat(IsoWeekUtil.sundayOf(SUNDAY)).isEqualTo(SUNDAY);
    }
}
