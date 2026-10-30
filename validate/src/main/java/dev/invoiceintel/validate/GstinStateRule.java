package dev.invoiceintel.validate;

import dev.invoiceintel.common.checksum.GstinChecksum;
import dev.invoiceintel.common.extraction.Field;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * VAL-4: the seller GSTIN has the GSTIN layout and mod-36 check character ({@link
 * GstinChecksum#isValid}, the same check the PII detector uses), and its state code matches the
 * seller state named in {@code sellerAddress}.
 *
 * <p>Only the seller GSTIN is checked: the buyer GSTIN is PII and is not part of the extraction
 * (specs.md §5.1, §6.1). A pseudonym token where the GSTIN should be (over-redaction) can't be
 * checked, so it gives a WARN, never a FAIL. A missing GSTIN is fine before GST (2017-07-01; VAL-6
 * checks the regime) and a WARN after.
 */
public final class GstinStateRule implements ValidationRule {

  static final String ID = "VAL-4";
  static final LocalDate GST_START = LocalDate.of(2017, 7, 1);

  private static final Pattern SEPARATORS = Pattern.compile("[\\s-]+");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public RuleResult check(InvoiceExtraction extraction, ValidationContext context) {
    Field<String> gstin = extraction.sellerGstin();
    if (gstin == null) {
      Field<LocalDate> date = extraction.invoiceDate();
      if (date != null && date.value().isBefore(GST_START)) {
        return RuleResult.pass();
      }
      return RuleResult.warn("sellerGstin is missing on a GST-era invoice");
    }
    String value = normalize(gstin.value());
    if (isPseudonym(value)) {
      return RuleResult.warn("sellerGstin is a pseudonym token; checksum and state not checked");
    }
    if (!GstinChecksum.isValid(value)) {
      return RuleResult.fail("sellerGstin fails the GSTIN format or check character");
    }
    String code = value.substring(0, 2);
    Optional<GstState> gstinState = GstState.byCode(code);
    if (gstinState.isEmpty()) {
      return RuleResult.fail("sellerGstin state code " + code + " is not a GST state code");
    }
    Optional<GstState> addressState =
        Optional.ofNullable(extraction.sellerAddress()).flatMap(a -> GstState.findIn(a.value()));
    if (addressState.isEmpty()) {
      return RuleResult.warn(
          "seller state not found in sellerAddress; sellerGstin state code "
              + code
              + " not cross-checked");
    }
    if (addressState.get() != gstinState.get()) {
      return RuleResult.fail(
          "sellerGstin state code "
              + code
              + " does not match seller state code "
              + addressState.get().code());
    }
    return RuleResult.pass();
  }

  /**
   * The seller's state from a valid seller GSTIN, else from {@code sellerAddress}; shared with
   * VAL-5 so both rules read the seller state the same way.
   */
  static Optional<GstState> sellerState(InvoiceExtraction extraction) {
    Field<String> gstin = extraction.sellerGstin();
    if (gstin != null) {
      String value = normalize(gstin.value());
      if (GstinChecksum.isValid(value)) {
        Optional<GstState> state = GstState.byCode(value.substring(0, 2));
        if (state.isPresent()) {
          return state;
        }
      }
    }
    return Optional.ofNullable(extraction.sellerAddress()).flatMap(a -> GstState.findIn(a.value()));
  }

  /** LLMs sometimes keep the spaces or hyphens a GSTIN is printed with, or lower-case it. */
  private static String normalize(String gstin) {
    return SEPARATORS.matcher(gstin).replaceAll("").toUpperCase(Locale.ROOT);
  }

  /** Tokens are {@code TYPE_XXXXXXXX} (architecture.md §6.3); a GSTIN never has an underscore. */
  private static boolean isPseudonym(String value) {
    return value.indexOf('_') >= 0;
  }
}
