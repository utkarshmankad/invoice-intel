package dev.invoiceintel.text;

import dev.invoiceintel.common.DocumentClass;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rule-based document classifier (TXT-4, ADR-09, architecture.md §6.2). It runs before PII removal,
 * so no LLM is involved: each class scores keyword and pattern evidence from the text layer, and
 * the best score at or above {@link #MIN_SCORE} wins, otherwise {@link DocumentClass#OTHER}.
 * Evidence is matched case-insensitively and tolerates the missing or extra spaces OCR produces.
 */
public final class DocumentClassifier {

  /** GST replaced VAT/CST on this date. */
  public static final LocalDate GST_START = LocalDate.of(2017, 7, 1);

  /** Lowest score that still counts as a classification. */
  static final int MIN_SCORE = 3;

  // Ties go to the more specific class: a credit note or bill of supply also says "invoice".
  private static final List<DocumentClass> TIE_ORDER =
      List.of(
          DocumentClass.CREDIT_NOTE,
          DocumentClass.BILL_OF_SUPPLY,
          DocumentClass.PRE_GST_INVOICE,
          DocumentClass.TAX_INVOICE);

  private static final Pattern INVOICE = phrase("invoice");
  private static final Pattern TAX_INVOICE = phrase("tax invoice");
  private static final Pattern RETAIL_INVOICE = phrase("retail invoice");
  private static final Pattern CREDIT_NOTE = phrase("credit note");
  private static final Pattern ORIGINAL_INVOICE = phrase("original invoice");
  private static final Pattern BILL_OF_SUPPLY = phrase("bill of supply");
  private static final Pattern GST_TAX = Pattern.compile("\\b(?:CGST|SGST|UTGST|IGST)\\b");
  private static final Pattern PRE_GST_TAX = Pattern.compile("\\b(?:VAT|CST|TIN)\\b");

  /** GSTIN shape: state code, PAN, entity number, "Z", check character. */
  private static final Pattern GSTIN =
      Pattern.compile("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\\b");

  private static final String DATE =
      "(\\d{4})-(\\d{2})-(\\d{2})"
          + "|(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})"
          + "|(\\d{1,2})[ -]?([A-Z]{3})[A-Z]*[ ,-]*(\\d{4})";
  private static final Pattern ANY_DATE = Pattern.compile("\\b(?:" + DATE + ")\\b");
  private static final Pattern INVOICE_DATE =
      Pattern.compile("INVOICE\\s*DATE\\s*:?\\s*(?:" + DATE + ")\\b");

  /** Class of the document in {@code layer}; {@link DocumentClass#OTHER} without evidence. */
  public DocumentClass classify(TextLayer layer) {
    Map<DocumentClass, Integer> scores = scores(layer);
    DocumentClass best = DocumentClass.OTHER;
    int bestScore = MIN_SCORE - 1;
    for (DocumentClass candidate : TIE_ORDER) {
      int score = scores.get(candidate);
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    return best;
  }

  /** Evidence score per class (all classes but {@link DocumentClass#OTHER}). */
  Map<DocumentClass, Integer> scores(TextLayer layer) {
    String text = normalise(layer);
    boolean invoice = INVOICE.matcher(text).find();
    boolean gstin = GSTIN.matcher(text).find();
    boolean gstTax = GST_TAX.matcher(text).find();
    boolean preGstTax = PRE_GST_TAX.matcher(text).find();
    Optional<LocalDate> date = documentDate(text);
    boolean beforeGst = date.map(GST_START::isAfter).orElse(false);
    boolean fromGst = date.map(d -> !d.isBefore(GST_START)).orElse(false);

    Map<DocumentClass, Integer> scores = new EnumMap<>(DocumentClass.class);
    scores.put(
        DocumentClass.TAX_INVOICE,
        points(TAX_INVOICE.matcher(text).find(), 3)
            + points(gstin, 2)
            + points(gstTax, 1)
            + points(fromGst, 1)
            - points(beforeGst, 4));
    scores.put(
        DocumentClass.CREDIT_NOTE,
        points(CREDIT_NOTE.matcher(text).find(), 4)
            + points(ORIGINAL_INVOICE.matcher(text).find(), 1));
    scores.put(DocumentClass.BILL_OF_SUPPLY, points(BILL_OF_SUPPLY.matcher(text).find(), 4));
    scores.put(
        DocumentClass.PRE_GST_INVOICE,
        invoice
            ? points(beforeGst, 3)
                + points(preGstTax && !gstTax && !gstin, 2)
                + points(RETAIL_INVOICE.matcher(text).find(), 1)
            : 0);
    return scores;
  }

  private static int points(boolean evidence, int points) {
    return evidence ? points : 0;
  }

  private static String normalise(TextLayer layer) {
    return layer.pages().stream()
        .flatMap(page -> page.words().stream())
        .map(Word::text)
        .collect(Collectors.joining(" "))
        .toUpperCase(Locale.ROOT);
  }

  /** The labelled invoice date, else the latest date in the text. */
  private static Optional<LocalDate> documentDate(String text) {
    Matcher labelled = INVOICE_DATE.matcher(text);
    while (labelled.find()) {
      Optional<LocalDate> date = date(labelled);
      if (date.isPresent()) {
        return date;
      }
    }
    List<LocalDate> dates = new ArrayList<>();
    Matcher any = ANY_DATE.matcher(text);
    while (any.find()) {
      date(any).ifPresent(dates::add);
    }
    return dates.stream().max(LocalDate::compareTo);
  }

  private static Optional<LocalDate> date(Matcher m) {
    try {
      if (m.group(1) != null) {
        return Optional.of(LocalDate.of(num(m, 1), num(m, 2), num(m, 3)));
      }
      if (m.group(4) != null) {
        return Optional.of(LocalDate.of(num(m, 6), num(m, 5), num(m, 4)));
      }
      return month(m.group(8)).map(month -> LocalDate.of(num(m, 9), month, num(m, 7)));
    } catch (DateTimeException e) {
      return Optional.empty();
    }
  }

  private static int num(Matcher m, int group) {
    return Integer.parseInt(m.group(group));
  }

  private static Optional<Month> month(String abbreviation) {
    for (Month month : Month.values()) {
      if (month.name().startsWith(abbreviation)) {
        return Optional.of(month);
      }
    }
    return Optional.empty();
  }

  /** Words of {@code phrase} with any spacing between them, as a whole-word match. */
  private static Pattern phrase(String phrase) {
    String spaced = String.join("\\s*", phrase.toUpperCase(Locale.ROOT).split(" "));
    return Pattern.compile("\\b" + spaced + "\\b");
  }
}
