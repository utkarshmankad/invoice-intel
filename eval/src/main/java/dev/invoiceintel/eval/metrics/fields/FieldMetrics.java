package dev.invoiceintel.eval.metrics.fields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-field exact-match accuracy (normalized) and line-item F1 over a labelled set (EVAL-2,
 * architecture §6.9). Pure: no I/O, no logging; results hold counts and field names only.
 */
public final class FieldMetrics {

  private FieldMetrics() {}

  /** A label and the extraction of the same document. */
  public record DocumentPair(String documentId, DocumentValues expected, DocumentValues actual) {
    public DocumentPair {
      Objects.requireNonNull(documentId, "documentId");
      Objects.requireNonNull(expected, "expected");
      Objects.requireNonNull(actual, "actual");
    }
  }

  /** One document's result: the header fields that didn't match and its line-item counts. */
  public record DocumentScore(
      String documentId, Set<HeaderField> mismatchedFields, LineItemCounts lineItems) {
    public DocumentScore {
      mismatchedFields =
          mismatchedFields.isEmpty()
              ? Collections.unmodifiableSet(EnumSet.noneOf(HeaderField.class))
              : Collections.unmodifiableSet(EnumSet.copyOf(mismatchedFields));
    }

    /** Every header field matched. */
    public boolean headerCorrect() {
      return mismatchedFields.isEmpty();
    }
  }

  /**
   * Dataset result. {@code perField} counts every field once per document; {@code header} sums
   * them; {@code lineItems} is micro-averaged (counts summed over all documents before computing
   * precision, recall and F1).
   */
  public record Result(
      Map<HeaderField, FieldCounts> perField,
      FieldCounts header,
      LineItemCounts lineItems,
      List<DocumentScore> documents) {
    public Result {
      perField = Collections.unmodifiableMap(new EnumMap<>(perField));
      documents = List.copyOf(documents);
    }
  }

  public static Result score(List<DocumentPair> pairs) {
    Map<HeaderField, FieldCounts> perField = new EnumMap<>(HeaderField.class);
    for (HeaderField field : HeaderField.values()) {
      perField.put(field, FieldCounts.ZERO);
    }
    LineItemCounts lineItems = LineItemCounts.ZERO;
    List<DocumentScore> documents = new ArrayList<>(pairs.size());

    for (DocumentPair pair : pairs) {
      DocumentScore score = scoreDocument(pair);
      for (HeaderField field : HeaderField.values()) {
        perField.put(field, perField.get(field).plus(!score.mismatchedFields().contains(field)));
      }
      lineItems = lineItems.plus(score.lineItems());
      documents.add(score);
    }

    FieldCounts header = perField.values().stream().reduce(FieldCounts.ZERO, FieldCounts::plus);
    return new Result(perField, header, lineItems, documents);
  }

  public static DocumentScore scoreDocument(DocumentPair pair) {
    Set<HeaderField> mismatched = EnumSet.noneOf(HeaderField.class);
    for (HeaderField field : HeaderField.values()) {
      if (!ValueComparison.matches(
          field.kind(), pair.expected().value(field), pair.actual().value(field))) {
        mismatched.add(field);
      }
    }
    LineItemCounts lineItems =
        LineItemMatcher.match(pair.expected().lineItems(), pair.actual().lineItems());
    return new DocumentScore(pair.documentId(), mismatched, lineItems);
  }
}
