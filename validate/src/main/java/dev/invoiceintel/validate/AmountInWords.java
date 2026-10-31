package dev.invoiceintel.validate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses an amount written in words with Indian numbering (VAL-7): {@code "One Lakh Twenty Thousand
 * Five Hundred Rupees and Fifty Paise Only"} is 120500.50.
 *
 * <p>Scales are hundred, thousand, lakh and crore; a crore may multiply a number that has lakhs and
 * thousands of its own ({@code "One Lakh Crore"}), and appears at most once. Currency words ({@code
 * Rupees}, {@code Rs}, {@code INR}), {@code and}, {@code only} and punctuation are ignored, and a
 * leading {@code Minus} or {@code Negative} makes the amount negative (credit notes). Paise follow
 * the rupees after {@code Rupees} or {@code and} and end with {@code Paise}. Anything else,
 * including digits, is not parsed: the caller gets an empty result rather than a guess.
 */
final class AmountInWords {

  private static final Map<String, Integer> SMALL =
      Map.ofEntries(
          Map.entry("zero", 0),
          Map.entry("one", 1),
          Map.entry("two", 2),
          Map.entry("three", 3),
          Map.entry("four", 4),
          Map.entry("five", 5),
          Map.entry("six", 6),
          Map.entry("seven", 7),
          Map.entry("eight", 8),
          Map.entry("nine", 9),
          Map.entry("ten", 10),
          Map.entry("eleven", 11),
          Map.entry("twelve", 12),
          Map.entry("thirteen", 13),
          Map.entry("fourteen", 14),
          Map.entry("fifteen", 15),
          Map.entry("sixteen", 16),
          Map.entry("seventeen", 17),
          Map.entry("eighteen", 18),
          Map.entry("nineteen", 19));

  private static final Map<String, Integer> TENS =
      Map.of(
          "twenty", 20,
          "thirty", 30,
          "forty", 40,
          "fourty", 40,
          "fifty", 50,
          "sixty", 60,
          "seventy", 70,
          "eighty", 80,
          "ninety", 90);

  private static final Map<String, Long> SCALES =
      Map.of(
          "thousand", 1_000L,
          "lakh", 100_000L,
          "lakhs", 100_000L,
          "lac", 100_000L,
          "lacs", 100_000L);

  private static final long CRORE = 10_000_000L;

  private AmountInWords() {}

  /** The amount the words state, at 2 decimals; empty when they can't be read as an amount. */
  static Optional<BigDecimal> parse(String words) {
    if (words == null) {
      return Optional.empty();
    }
    List<String> tokens = tokens(words);
    boolean negative = false;
    if (!tokens.isEmpty() && (tokens.get(0).equals("minus") || tokens.get(0).equals("negative"))) {
      negative = true;
      tokens = tokens.subList(1, tokens.size());
    }
    Optional<BigDecimal> amount = parseUnsigned(tokens);
    return negative ? amount.map(BigDecimal::negate) : amount;
  }

  private static List<String> tokens(String words) {
    var tokens = new ArrayList<String>();
    for (String token : asciiLower(words).replace("₹", " rupees ").split("[\\s\\-,.:;/()]+")) {
      switch (token) {
        case "", "only" -> {}
        case "rupee", "rupees", "rs", "inr" -> tokens.add("rupees");
        case "paisa", "paise", "paisas" -> tokens.add("paise");
        default -> tokens.add(token);
      }
    }
    return tokens;
  }

  /**
   * Lower-cases ASCII letters only: number words are ASCII, and Unicode case mapping is what
   * FindSecBugs reports (IMPROPER_UNICODE), as in the {@code pii} module.
   */
  private static String asciiLower(String text) {
    var lower = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return lower.toString();
  }

  private static Optional<BigDecimal> parseUnsigned(List<String> tokens) {
    int paiseAt = tokens.indexOf("paise");
    if (paiseAt < 0) {
      return number(strip(tokens)).map(AmountInWords::rupees);
    }
    if (paiseAt != tokens.size() - 1) {
      return Optional.empty();
    }
    int split = splitBeforePaise(tokens, paiseAt);
    List<String> paiseWords = strip(tokens.subList(split + 1, paiseAt));
    if (paiseWords.contains("rupees")) {
      return Optional.empty();
    }
    Optional<Long> paise = number(paiseWords).filter(p -> p < 100);
    if (paise.isEmpty()) {
      return Optional.empty();
    }
    List<String> rupeeWords = split < 0 ? List.of() : strip(tokens.subList(0, split));
    Optional<Long> rupees = rupeeWords.isEmpty() ? Optional.of(0L) : number(rupeeWords);
    return rupees.map(r -> rupees(r).add(BigDecimal.valueOf(paise.get(), 2)));
  }

  /**
   * Where the rupees end: the last {@code rupees} before the paise if it follows some number word,
   * else the last {@code and}; {@code -1} when the whole amount is paise.
   */
  private static int splitBeforePaise(List<String> tokens, int paiseAt) {
    int lastAnd = -1;
    for (int i = paiseAt - 1; i >= 0; i--) {
      String token = tokens.get(i);
      if (token.equals("rupees") && i > 0) {
        return i;
      }
      if (token.equals("and") && lastAnd < 0) {
        lastAnd = i;
      }
    }
    return lastAnd;
  }

  /** Drops {@code and} and a leading or trailing {@code rupees}. */
  private static List<String> strip(List<String> tokens) {
    var words = new ArrayList<String>();
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      boolean edge = i == 0 || i == tokens.size() - 1;
      if (!token.equals("and") && !(edge && token.equals("rupees"))) {
        words.add(token);
      }
    }
    return words;
  }

  private static BigDecimal rupees(long rupees) {
    return BigDecimal.valueOf(rupees).setScale(2);
  }

  private enum Kind {
    NONE,
    ZERO,
    UNIT,
    TEEN,
    TENS,
    HUNDRED,
    SCALE,
    CRORE
  }

  /**
   * A whole number in words. Within each group below a thousand the words must read in order (tens
   * before units, one hundred per group); thousand and lakh must fall within a crore. These rules
   * keep the largest readable number below 10^14, far inside a {@code long}.
   */
  private static Optional<Long> number(List<String> words) {
    if (words.isEmpty()) {
      return Optional.empty();
    }
    long total = 0;
    long current = 0;
    long lastScale = Long.MAX_VALUE;
    Kind previous = Kind.NONE;
    for (String word : words) {
      Integer small = SMALL.get(word);
      Integer tens = TENS.get(word);
      Long scale = SCALES.get(word);
      if (small != null && small == 0) {
        if (previous != Kind.NONE || words.size() != 1) {
          return Optional.empty();
        }
        previous = Kind.ZERO;
      } else if (small != null) {
        boolean unit = small < 10;
        boolean allowed =
            previous == Kind.NONE
                || previous == Kind.HUNDRED
                || previous == Kind.SCALE
                || previous == Kind.CRORE
                || (unit && previous == Kind.TENS);
        if (!allowed) {
          return Optional.empty();
        }
        current += small;
        previous = unit ? Kind.UNIT : Kind.TEEN;
      } else if (tens != null) {
        if (previous != Kind.NONE
            && previous != Kind.HUNDRED
            && previous != Kind.SCALE
            && previous != Kind.CRORE) {
          return Optional.empty();
        }
        current += tens;
        previous = Kind.TENS;
      } else if (word.equals("hundred")) {
        if ((previous != Kind.UNIT && previous != Kind.TEEN) || current >= 100) {
          return Optional.empty();
        }
        current *= 100;
        previous = Kind.HUNDRED;
      } else if (scale != null) {
        if (!isNumber(previous) || scale >= lastScale) {
          return Optional.empty();
        }
        total = total + current * scale;
        current = 0;
        lastScale = scale;
        previous = Kind.SCALE;
      } else if (word.equals("crore") || word.equals("crores")) {
        // One crore word per amount: "Two Crore Three Crore" is not a number.
        if (total >= CRORE || (!isNumber(previous) && previous != Kind.SCALE)) {
          return Optional.empty();
        }
        total = (total + current) * CRORE;
        current = 0;
        lastScale = Long.MAX_VALUE;
        previous = Kind.CRORE;
      } else {
        return Optional.empty();
      }
    }
    return Optional.of(total + current);
  }

  private static boolean isNumber(Kind kind) {
    return kind == Kind.UNIT || kind == Kind.TEEN || kind == Kind.TENS || kind == Kind.HUNDRED;
  }
}
