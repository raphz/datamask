package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;

/**
 * Widens a date to its year.
 *
 * <p>A birth date is directly identifying — it is one of the three attributes that re-identifies
 * most of a population — while the year alone supports the analysis banks actually run on it: age
 * bands, suitability checks, cohort reporting.
 *
 * <p>The declared type is preserved: a {@code LocalDate} comes back as the first of January, so
 * the surrounding record still type-checks.
 */
public final class DateGeneralizeMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        // A card expiry is a never-partially-revealed category, and its year is most of the value.
        if (context.category().neverPartiallyReveal()) {
            return Masks.placeholder(context);
        }
        return switch (value) {
            case LocalDate date -> LocalDate.of(date.getYear(), 1, 1);
            case LocalDateTime dateTime -> LocalDateTime.of(dateTime.getYear(), 1, 1, 0, 0);
            case YearMonth yearMonth -> YearMonth.of(yearMonth.getYear(), 1);
            case OffsetDateTime offset -> OffsetDateTime.of(offset.getYear(), 1, 1, 0, 0, 0, 0, offset.getOffset());
            case ZonedDateTime zoned -> ZonedDateTime.of(zoned.getYear(), 1, 1, 0, 0, 0, 0, zoned.getZone());
            case Instant instant -> startOfYear(instant);
            // The three java.sql types come before java.util.Date, which two of them extend. It is
            // not a refinement: java.sql.Date and java.sql.Time throw from toInstant() by contract,
            // and java.sql.Date is the single most common type a legacy schema gives a birth date.
            case java.sql.Date sqlDate ->
                java.sql.Date.valueOf(sqlDate.toLocalDate().withDayOfYear(1));
            case java.sql.Time sqlTime -> java.sql.Time.valueOf(java.time.LocalTime.MIDNIGHT);
            case java.sql.Timestamp timestamp -> java.sql.Timestamp.from(startOfYear(timestamp.toInstant()));
            case java.util.Date legacy -> java.util.Date.from(startOfYear(legacy.toInstant()));
            case CharSequence text -> generalizeText(text.toString(), context);
            case Temporal ignored -> Masks.placeholder(context);
            default -> Masks.placeholder(context);
        };
    }

    private static Instant startOfYear(Instant instant) {
        return instant.atZone(ZoneOffset.UTC)
                .withDayOfYear(1)
                .truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .toInstant();
    }

    private Object generalizeText(String text, MaskContext context) {
        try {
            return String.valueOf(LocalDate.parse(text).getYear());
        } catch (DateTimeParseException notADate) {
            // Fall through: an unparseable value might be a date in a format we do not know, and
            // guessing its year from the first four digits would be a way to leak a full date.
            return Masks.placeholder(context);
        }
    }

    @Override
    public boolean supports(Class<?> type) {
        return Temporal.class.isAssignableFrom(type)
                || java.util.Date.class.isAssignableFrom(type)
                || CharSequence.class.isAssignableFrom(type)
                || Object.class.equals(type);
    }
}
