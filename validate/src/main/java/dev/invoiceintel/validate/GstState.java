package dev.invoiceintel.validate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The GST state-code table (VAL-4, VAL-5): the 28 states and 8 union territories with their GST
 * state codes, plus code 97 "Other Territory". Older names still printed on invoices (Orissa,
 * Pondicherry, Uttaranchal…) and codes that were folded into another (25 Daman and Diu into 26
 * since the 2020 merger, 28 undivided Andhra Pradesh into 37) map to the current entry. Name
 * matching ignores case, punctuation and spacing, and treats {@code &} as "and".
 */
public enum GstState {
  JAMMU_AND_KASHMIR("01", List.of(), "Jammu and Kashmir"),
  HIMACHAL_PRADESH("02", List.of(), "Himachal Pradesh"),
  PUNJAB("03", List.of(), "Punjab"),
  CHANDIGARH("04", List.of(), "Chandigarh"),
  UTTARAKHAND("05", List.of(), "Uttarakhand", "Uttaranchal"),
  HARYANA("06", List.of(), "Haryana"),
  DELHI("07", List.of(), "Delhi", "National Capital Territory of Delhi"),
  RAJASTHAN("08", List.of(), "Rajasthan"),
  UTTAR_PRADESH("09", List.of(), "Uttar Pradesh"),
  BIHAR("10", List.of(), "Bihar"),
  SIKKIM("11", List.of(), "Sikkim"),
  ARUNACHAL_PRADESH("12", List.of(), "Arunachal Pradesh"),
  NAGALAND("13", List.of(), "Nagaland"),
  MANIPUR("14", List.of(), "Manipur"),
  MIZORAM("15", List.of(), "Mizoram"),
  TRIPURA("16", List.of(), "Tripura"),
  MEGHALAYA("17", List.of(), "Meghalaya"),
  ASSAM("18", List.of(), "Assam"),
  WEST_BENGAL("19", List.of(), "West Bengal"),
  JHARKHAND("20", List.of(), "Jharkhand"),
  ODISHA("21", List.of(), "Odisha", "Orissa"),
  CHHATTISGARH("22", List.of(), "Chhattisgarh", "Chattisgarh"),
  MADHYA_PRADESH("23", List.of(), "Madhya Pradesh"),
  GUJARAT("24", List.of(), "Gujarat"),
  DADRA_NAGAR_HAVELI_DAMAN_DIU(
      "26",
      List.of("25"),
      "Dadra and Nagar Haveli and Daman and Diu",
      "Dadra and Nagar Haveli",
      "Daman and Diu"),
  MAHARASHTRA("27", List.of(), "Maharashtra"),
  KARNATAKA("29", List.of(), "Karnataka"),
  GOA("30", List.of(), "Goa"),
  LAKSHADWEEP("31", List.of(), "Lakshadweep"),
  KERALA("32", List.of(), "Kerala"),
  TAMIL_NADU("33", List.of(), "Tamil Nadu", "Tamilnadu"),
  PUDUCHERRY("34", List.of(), "Puducherry", "Pondicherry"),
  ANDAMAN_AND_NICOBAR_ISLANDS(
      "35", List.of(), "Andaman and Nicobar Islands", "Andaman and Nicobar"),
  TELANGANA("36", List.of(), "Telangana"),
  ANDHRA_PRADESH("37", List.of("28"), "Andhra Pradesh"),
  LADAKH("38", List.of(), "Ladakh"),
  OTHER_TERRITORY("97", List.of(), "Other Territory");

  private static final Pattern TWO_DIGITS = Pattern.compile("\\d{2}");

  private final String code;
  private final List<String> legacyCodes;
  private final String displayName;
  private final List<List<String>> names;

  GstState(String code, List<String> legacyCodes, String displayName, String... aliases) {
    this.code = code;
    this.legacyCodes = legacyCodes;
    this.displayName = displayName;
    var all = new ArrayList<List<String>>();
    all.add(words(displayName));
    Arrays.stream(aliases).map(GstState::words).forEach(all::add);
    this.names = List.copyOf(all);
  }

  /** The current two-digit GST state code, e.g. {@code "27"}. */
  public String code() {
    return code;
  }

  public String displayName() {
    return displayName;
  }

  /** The state with this current or legacy two-digit code. */
  public static Optional<GstState> byCode(String code) {
    if (code == null) {
      return Optional.empty();
    }
    String trimmed = code.strip();
    return Arrays.stream(values())
        .filter(s -> s.code.equals(trimmed) || s.legacyCodes.contains(trimmed))
        .findFirst();
  }

  /** The state whose name or alias is exactly {@code name}, ignoring case and punctuation. */
  public static Optional<GstState> byName(String name) {
    if (name == null) {
      return Optional.empty();
    }
    List<String> words = words(name);
    return Arrays.stream(values()).filter(s -> s.names.contains(words)).findFirst();
  }

  /**
   * The state named last in free text such as an address ("…, Bengaluru, Karnataka, 560001"), where
   * the state usually follows the street and city. Names match whole words only, longest first, so
   * "Delhi Road, Meerut, Uttar Pradesh" gives Uttar Pradesh.
   */
  public static Optional<GstState> findIn(String text) {
    if (text == null) {
      return Optional.empty();
    }
    List<String> words = words(text);
    GstState last = null;
    int i = 0;
    while (i < words.size()) {
      Match match = longestMatchAt(words, i);
      if (match == null) {
        i++;
      } else {
        last = match.state();
        i += match.length();
      }
    }
    return Optional.ofNullable(last);
  }

  /**
   * A state field as printed: a name ("Maharashtra"), a code ("27") or both ("27-Maharashtra",
   * "Maharashtra (27)"). Empty when nothing matches or the name and code disagree.
   */
  public static Optional<GstState> resolve(String value) {
    if (value == null) {
      return Optional.empty();
    }
    Optional<GstState> byName = findIn(value);
    Optional<GstState> byCode =
        words(value).stream()
            .filter(w -> TWO_DIGITS.matcher(w).matches())
            .map(GstState::byCode)
            .flatMap(Optional::stream)
            .findFirst();
    if (byName.isPresent() && byCode.isPresent()) {
      return byName.equals(byCode) ? byName : Optional.empty();
    }
    return byName.isPresent() ? byName : byCode;
  }

  /**
   * Enum constants are built before the enum's own static fields, so the constructor needs a
   * holder.
   */
  private static final class Patterns {
    static final Pattern NOT_ALNUM = Pattern.compile("[^A-Z0-9]+");
  }

  private record Match(GstState state, int length) {}

  private static Match longestMatchAt(List<String> words, int start) {
    Match best = null;
    for (GstState state : values()) {
      for (List<String> name : state.names) {
        int end = start + name.size();
        boolean longer = best == null || name.size() > best.length();
        if (longer && end <= words.size() && words.subList(start, end).equals(name)) {
          best = new Match(state, name.size());
        }
      }
    }
    return best;
  }

  private static List<String> words(String text) {
    String normalized =
        Patterns.NOT_ALNUM
            .matcher(text.toUpperCase(Locale.ROOT).replace("&", " AND "))
            .replaceAll(" ");
    String stripped = normalized.strip();
    return stripped.isEmpty() ? List.of() : List.of(stripped.split(" "));
  }
}
