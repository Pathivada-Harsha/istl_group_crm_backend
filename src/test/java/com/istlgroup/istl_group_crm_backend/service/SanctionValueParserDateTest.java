package com.istlgroup.istl_group_crm_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * A sanction letter that names only a month and year for a date (no day) —
 * e.g. a Scheduled COD stated simply as "March 2026" — must read as that
 * month's LAST day, never its first.
 */
class SanctionValueParserDateTest {

    @Test
    void monthYearOnly_resolvesToLastDayOfMonth() {
        assertEquals(LocalDate.of(2026, 3, 31), SanctionValueParser.parseDate("March 2026"));
        assertEquals(LocalDate.of(2026, 2, 28), SanctionValueParser.parseDate("Feb 2026"));
        assertEquals(LocalDate.of(2024, 2, 29), SanctionValueParser.parseDate("February 2024"));
        assertEquals(LocalDate.of(2026, 4, 30), SanctionValueParser.parseDate("04/2026"));
    }

    @Test
    void fullyDatedString_stillWinsOverMonthYearFallback() {
        assertEquals(LocalDate.of(2026, 3, 14), SanctionValueParser.parseDate("14 March 2026"));
        assertEquals(LocalDate.of(2026, 3, 14), SanctionValueParser.parseDate("14/03/2026"));
    }

    @Test
    void garbage_returnsNull() {
        assertNull(SanctionValueParser.parseDate("not a date"));
    }
}
