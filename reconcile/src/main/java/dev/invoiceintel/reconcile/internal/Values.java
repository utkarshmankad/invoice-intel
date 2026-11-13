package dev.invoiceintel.reconcile.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses order-history cell values. Blank cells and the export's "not available" placeholders are
 * absent ({@link Optional#empty()}); a value that is present but can't be read throws {@link
 * IllegalArgumentException} without the value in its message (PII-8).
 */
public final class Values {

  /** Amazon.in orders are placed in India; a date-time with an offset is read in IST. */
  static final ZoneId ORDER_ZONE = ZoneId.of("Asia/Kolkata");

  private static final Set<String> ABSENT = Set.of("not available", "n/a", "na", "-");
  private static final Pattern AMOUNT_NOISE = Pattern.compile("[,\\s ₹]|^INR|^Rs\\.?");
  private static final Pattern AMOUNT = Pattern.compile("-?\\d+(\\.\\d+)?");

  private Values() {}

  /** The cell, stripped, unless blank or a placeholder. */
  public static Optional<String> text(String cell) {
    if (cell == null) {
      return Optional.empty();
    }
    var value = cell.strip();
    return value.isEmpty() || ABSENT.contains(value.toLowerCase(Locale.ROOT))
        ? Optional.empty()
        : Optional.of(value);
  }

  /**
   * An ISO-8601 date ({@code 2024-03-05}), date-time with offset ({@code 2024-03-05T18:40:00Z},
   * read in IST) or local date-time; the date part counts.
   */
  public static Optional<LocalDate> date(String cell) {
    return text(cell).map(Values::parseDate);
  }

  /** A whole number. */
  public static Optional<Integer> quantity(String cell) {
    return text(cell)
        .map(
            v -> {
              try {
                return Integer.valueOf(v);
              } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a quantity", e);
              }
            });
  }

  /**
   * A rupee amount, scale 2 rounded half-up; thousands separators, spaces and a leading ₹, INR or
   * Rs. are ignored.
   */
  public static Optional<BigDecimal> amount(String cell) {
    return text(cell)
        .map(
            v -> {
              var digits = AMOUNT_NOISE.matcher(v).replaceAll("");
              if (!AMOUNT.matcher(digits).matches()) {
                throw new IllegalArgumentException("not an amount");
              }
              return new BigDecimal(digits).setScale(2, RoundingMode.HALF_UP);
            });
  }

  private static LocalDate parseDate(String value) {
    try {
      return OffsetDateTime.parse(value).atZoneSameInstant(ORDER_ZONE).toLocalDate();
    } catch (DateTimeParseException e) {
      // not a date-time with offset; try the next form
    }
    try {
      return LocalDateTime.parse(value).toLocalDate();
    } catch (DateTimeParseException e) {
      // not a local date-time; try the next form
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("not a date", e);
    }
  }
}
