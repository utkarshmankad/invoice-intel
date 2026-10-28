package dev.invoiceintel.pii.internal;

import java.util.List;
import java.util.Locale;

/**
 * Matches field labels such as "Billing Address" at the start of a line, ignoring case, spaces and
 * punctuation between letters, so OCR splits like "Bill ing Address :" still match. A label must
 * end at a word boundary: "PAN" matches "PAN No:" but not a name that starts with "Pan".
 */
public final class Labels {

  /** Anchors of the buyer's address blocks (architecture.md §6.3, layout layer). */
  public static final List<String> BUYER_ANCHORS =
      List.of(
          "Billing Address",
          "Shipping Address",
          "Delivery Address",
          "Ship To",
          "Bill To",
          "Deliver To");

  /** Anchors of the seller block, whose values are not PII (specs.md §6.1). */
  public static final List<String> SELLER_ANCHORS = List.of("Sold By", "Seller", "Supplier");

  /** Labels of seller identifiers that may sit below the seller address after a gap. */
  public static final List<String> SELLER_ID_LABELS =
      List.of("PAN", "GST", "GSTIN", "VAT", "TIN", "CIN", "FSSAI");

  /** Labels that start a different part of the invoice and so end any block. */
  public static final List<String> SECTION_LABELS =
      List.of(
          "Order",
          "Invoice",
          "Credit Note",
          "Debit Note",
          "Place of Supply",
          "Place of Delivery",
          "State/UT Code",
          "Amount",
          "Total");

  public static final List<String> PHONE_LABELS =
      List.of("Phone", "Mobile", "Mob", "Ph", "Tel", "Telephone", "Contact");

  public static final List<String> EMAIL_LABELS = List.of("Email", "Mail");

  private Labels() {}

  /**
   * Offset in {@code text} just past the last letter of {@code label} when the text starts with it,
   * or -1.
   */
  public static int prefixEnd(String text, String label) {
    String want = alnum(label);
    int matched = 0;
    int i = 0;
    while (i < text.length() && matched < want.length()) {
      char c = text.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        if (Character.toLowerCase(c) != want.charAt(matched)) {
          return -1;
        }
        matched++;
      }
      i++;
    }
    if (matched < want.length()) {
      return -1;
    }
    if (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
      return -1;
    }
    return i;
  }

  /** The end offset of the first of {@code labels} that {@code text} starts with, or -1. */
  public static int prefixEnd(String text, List<String> labels) {
    for (String label : labels) {
      int end = prefixEnd(text, label);
      if (end >= 0) {
        return end;
      }
    }
    return -1;
  }

  /** Whether {@code text} has a letter or digit. */
  public static boolean hasAlnum(CharSequence text) {
    return text.chars().anyMatch(Character::isLetterOrDigit);
  }

  private static String alnum(String label) {
    var builder = new StringBuilder();
    for (char c : label.toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) {
        builder.append(c);
      }
    }
    return builder.toString();
  }
}
