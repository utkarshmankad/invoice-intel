package dev.invoiceintel.pii;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.internal.PiiVaultRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The post-extraction leak check (PII-6, architecture.md §6.3): decrypts the vault values of a
 * document in memory and searches the model's response for them. Any hit means the redaction let a
 * real value through to the remote model, so the caller fails the document with {@link
 * #FAILURE_CODE} and discards the response.
 *
 * <p>Both sides are compared in a canonical form (ASCII lower case, every run of characters that
 * are not letters or digits as one space), so case, spacing and punctuation don't hide a value. A
 * value matches on whole words; a multi-word value also matches with its spaces removed
 * ("RaviKumar"), and a value with at least {@value #MIN_DIGITS} digits also matches inside a run of
 * digits that only spaces or punctuation separate ("+91 98765-43210" for "9876543210").
 *
 * <p>Decrypted values stay in local variables of {@link #check}; neither they nor the response are
 * logged, returned or put in an exception (PII-8).
 */
public final class LeakChecker {

  /** The document's {@code failure_code} after a leak (architecture.md §5.1, §6.3). */
  public static final String FAILURE_CODE = "PII_LEAK";

  // Shorter digit strings (amounts, quantities, HSN codes) would match by chance.
  static final int MIN_DIGITS = 6;

  /** Told about every leak, e.g. to increment the {@code invoice.pii.leak} metric (§12). */
  @FunctionalInterface
  public interface Listener {
    /**
     * @param types types of the values found; never the values themselves
     */
    void leakDetected(DocumentId documentId, Set<PiiType> types);
  }

  private final PiiVault vault;
  private final Function<DocumentId, List<VaultEntry>> entries;
  private final Listener listener;

  /** Reads the document's entries from {@code pii_vault} (only pii may, architecture.md §4.1). */
  public LeakChecker(PiiVault vault, JdbcClient jdbc, Listener listener) {
    this(
        vault,
        vaultEntries(new PiiVaultRepository(Objects.requireNonNull(jdbc, "jdbc"))),
        listener);
  }

  // JdbcClient is a functional interface too, so tests use the factory below, not an overload.
  private LeakChecker(
      PiiVault vault, Function<DocumentId, List<VaultEntry>> entries, Listener listener) {
    this.vault = Objects.requireNonNull(vault, "vault");
    this.entries = Objects.requireNonNull(entries, "entries");
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /** A checker over entries from {@code entries} instead of the database, for tests. */
  static LeakChecker withEntries(
      PiiVault vault, Function<DocumentId, List<VaultEntry>> entries, Listener listener) {
    return new LeakChecker(vault, entries, listener);
  }

  /**
   * Checks one model response of a document against the document's vault values.
   *
   * @param response the model's raw response text
   * @throws IllegalStateException if a vault entry can't be decrypted (fail closed: no result)
   */
  public LeakCheckResult check(DocumentId documentId, String response) {
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(response, "response");
    List<VaultEntry> known = entries.apply(documentId);
    if (known.isEmpty()) {
      return LeakCheckResult.clean();
    }
    Haystack haystack = Haystack.of(response);
    Set<PiiType> found = EnumSet.noneOf(PiiType.class);
    for (VaultEntry entry : known) {
      if (!found.contains(entry.type()) && haystack.contains(vault.open(entry))) {
        found.add(entry.type());
      }
    }
    if (found.isEmpty()) {
      return LeakCheckResult.clean();
    }
    LeakCheckResult result = new LeakCheckResult(found);
    listener.leakDetected(documentId, result.leakedTypes());
    return result;
  }

  private static Function<DocumentId, List<VaultEntry>> vaultEntries(
      PiiVaultRepository repository) {
    return documentId -> {
      var list = new ArrayList<VaultEntry>();
      for (PiiVaultRepository.Row row : repository.findByDocument(documentId)) {
        list.add(new VaultEntry(row.token(), PiiType.valueOf(row.type()), row.ciphertext()));
      }
      return list;
    };
  }

  /**
   * Canonical form: ASCII lower case, each run of characters that are not letters or digits as one
   * space, trimmed. Case folding is ASCII-only and there is no NFKC step, as in {@link
   * TokenGenerator}: FindSecBugs reports Unicode case mapping and normalization (IMPROPER_UNICODE)
   * and whether to fold full-width text is an open owner decision (plan.md Follow-ups).
   */
  static String canonical(String text) {
    var out = new StringBuilder(text.length());
    boolean gap = false;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isLetterOrDigit(cp)) {
        if (gap && !out.isEmpty()) {
          out.append(' ');
        }
        gap = false;
        out.appendCodePoint(cp >= 'A' && cp <= 'Z' ? cp + ('a' - 'A') : cp);
      } else {
        gap = true;
      }
    }
    return out.toString();
  }

  /** The response in the forms the values are searched in. */
  record Haystack(String words, String compact, List<String> digitRuns) {

    static Haystack of(String response) {
      String words = canonical(response);
      var runs = new ArrayList<String>();
      var run = new StringBuilder();
      for (String word : words.split(" ")) {
        if (!word.isEmpty() && word.chars().allMatch(Character::isDigit)) {
          run.append(word);
        } else if (!run.isEmpty()) {
          runs.add(run.toString());
          run.setLength(0);
        }
      }
      if (!run.isEmpty()) {
        runs.add(run.toString());
      }
      return new Haystack(" " + words + " ", words.replace(" ", ""), List.copyOf(runs));
    }

    boolean contains(String value) {
      String needle = canonical(value);
      if (needle.isEmpty()) {
        return false;
      }
      if (words.contains(" " + needle + " ")) {
        return true;
      }
      if (needle.indexOf(' ') >= 0 && compact.contains(needle.replace(" ", ""))) {
        return true;
      }
      String digits = digitsOf(needle);
      if (digits.length() >= MIN_DIGITS) {
        for (String run : digitRuns) {
          if (run.contains(digits)) {
            return true;
          }
        }
      }
      return false;
    }

    // Never printed: it holds the response (PII-8).
    @Override
    public String toString() {
      return "Haystack";
    }

    private static String digitsOf(String text) {
      var out = new StringBuilder();
      text.codePoints().filter(Character::isDigit).forEach(out::appendCodePoint);
      return out.toString();
    }
  }
}
