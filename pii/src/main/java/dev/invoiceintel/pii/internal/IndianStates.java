package dev.invoiceintel.pii.internal;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Indian states and union territories, with older names still printed on invoices. The buyer state
 * is kept (specs.md §6.1); detectors use the names as context, e.g. for PIN codes.
 */
public final class IndianStates {

  public static final List<String> NAMES =
      List.of(
          "Andaman and Nicobar Islands",
          "Andhra Pradesh",
          "Arunachal Pradesh",
          "Assam",
          "Bihar",
          "Chandigarh",
          "Chhattisgarh",
          "Dadra and Nagar Haveli and Daman and Diu",
          "Dadra and Nagar Haveli",
          "Daman and Diu",
          "Delhi",
          "Goa",
          "Gujarat",
          "Haryana",
          "Himachal Pradesh",
          "Jammu and Kashmir",
          "Jharkhand",
          "Karnataka",
          "Kerala",
          "Ladakh",
          "Lakshadweep",
          "Madhya Pradesh",
          "Maharashtra",
          "Manipur",
          "Meghalaya",
          "Mizoram",
          "Nagaland",
          "Odisha",
          "Orissa",
          "Pondicherry",
          "Puducherry",
          "Punjab",
          "Rajasthan",
          "Sikkim",
          "Tamil Nadu",
          "Telangana",
          "Tripura",
          "Uttar Pradesh",
          "Uttarakhand",
          "Uttaranchal",
          "West Bengal");

  private IndianStates() {}

  /**
   * Regex alternation of all names, longest first so "Dadra and Nagar Haveli and Daman and Diu"
   * wins over its prefix; words may be separated by any whitespace. Use case-insensitively.
   */
  public static String alternation() {
    return NAMES.stream()
        .sorted((a, b) -> Integer.compare(b.length(), a.length()))
        .map(name -> String.join("\\s+", name.split(" ")))
        .collect(Collectors.joining("|"));
  }
}
