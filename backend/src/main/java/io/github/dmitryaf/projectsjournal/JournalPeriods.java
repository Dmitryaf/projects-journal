package io.github.dmitryaf.projectsjournal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class JournalPeriods {

    private static final LocalDate FIRST_ANCHOR = LocalDate.of(1900, 1, 1);
    private static final LocalDate LAST_ANCHOR = LocalDate.of(9998, 12, 31);
    private static final Pattern ISO_DAY = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final DateTimeFormatter UTC = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Map<String, String> ZONE_NAMES = ZoneId.getAvailableZoneIds().stream()
            .collect(Collectors.toUnmodifiableMap(name -> name.toLowerCase(Locale.ROOT), name -> name));

    private JournalPeriods() {
    }

    static ZoneId zone(String name) {
        if (name == null || name.isEmpty() || name.length() > 100) {
            throw ApiJson.invalid("timezone");
        }
        String resolved = ZONE_NAMES.get(name.toLowerCase(Locale.ROOT));
        if (resolved == null) {
            throw ApiJson.invalid("timezone");
        }
        return ZoneId.of(resolved);
    }

    static LocalDate day(String value, String field) {
        if (value != null && ISO_DAY.matcher(value).matches()) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException ignored) {
                // An ISO-shaped string can still name an impossible day.
            }
        }
        throw ApiJson.invalid(field);
    }

    static Instant startOfDay(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }

    static String utc(Instant instant) {
        return UTC.format(instant);
    }

    static Bounds bounds(String kind, String anchorDate, String timezone) {
        LocalDate anchor = day(anchorDate, "anchorDate");
        if (anchor.isBefore(FIRST_ANCHOR) || anchor.isAfter(LAST_ANCHOR)) {
            throw ApiJson.invalid("anchorDate");
        }
        ZoneId zone = zone(timezone);
        LocalDate start;
        LocalDate next;
        switch (kind) {
            case "week" -> {
                start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                next = start.plusWeeks(1);
            }
            case "month" -> {
                start = anchor.withDayOfMonth(1);
                next = start.plusMonths(1);
            }
            default -> throw ApiJson.invalid("kind");
        }
        return new Bounds(start.toString(), next.minusDays(1).toString(), timezone,
                utc(startOfDay(start, zone)), utc(startOfDay(next, zone)));
    }

    record Bounds(String periodStart, String periodEnd, String timezone, String startAt, String nextStartAt) {
    }
}
