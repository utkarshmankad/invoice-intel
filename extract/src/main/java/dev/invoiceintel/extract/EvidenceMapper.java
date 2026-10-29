package dev.invoiceintel.extract;

import dev.invoiceintel.pii.RedactedContent;
import dev.invoiceintel.text.Word;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the line markers the model cited ({@link Evidence#lineRefs()}) back to word boxes of the
 * redacted text layer, so the review UI can highlight where a value was read (specs.md §5.3, REV-2,
 * architecture.md §6.5). It repeats the {@link TextLines} grouping the model input was built from,
 * so {@code p{page}:l{line}} names the same words here as in the prompt.
 *
 * <p>Only {@link FieldSource#LLM} fields are mapped; {@code RULE} and {@code HUMAN} evidence is
 * kept as is. For an LLM field the text layer is the only source of positions: the box is the union
 * of the cited lines' word boxes and the page is the cited lines' page, whatever page or box the
 * model returned. When the cited lines span pages, the lines on the model's {@code page} win if
 * there are any, otherwise those on the first cited page. Markers that are malformed or name a line
 * the document doesn't have are ignored; if none resolves, the box is cleared and the model's page
 * kept. {@code lineRefs} always stay as cited. Nothing here copies text (PII-8).
 */
public final class EvidenceMapper {

  /** {@code p3:l12}, optionally in the square brackets of the prompt's markers. */
  private static final Pattern REF =
      Pattern.compile("\\[?\\s*p(\\d{1,6})\\s*:\\s*l(\\d{1,6})\\s*]?", Pattern.CASE_INSENSITIVE);

  private final Map<String, TextLines.Line> linesByRef = new HashMap<>();

  private EvidenceMapper(RedactedContent content) {
    for (RedactedContent.Page page : content.pages()) {
      for (TextLines.Line line : TextLines.of(page)) {
        linesByRef.put(line.ref(), line);
      }
    }
  }

  /** {@code extraction} with every LLM field's evidence mapped onto {@code content}'s words. */
  public static InvoiceExtraction map(InvoiceExtraction extraction, RedactedContent content) {
    Objects.requireNonNull(extraction, "extraction");
    Objects.requireNonNull(content, "content");
    return new EvidenceMapper(content).extraction(extraction);
  }

  private InvoiceExtraction extraction(InvoiceExtraction e) {
    return new InvoiceExtraction(
        field(e.documentClass()),
        field(e.invoiceNumber()),
        field(e.invoiceDate()),
        field(e.orderNumber()),
        field(e.orderDate()),
        field(e.sellerName()),
        field(e.sellerGstin()),
        field(e.sellerPan()),
        field(e.sellerAddress()),
        field(e.placeOfSupply()),
        field(e.placeOfDelivery()),
        field(e.buyerState()),
        field(e.reverseCharge()),
        field(e.currency()),
        field(e.subtotal()),
        field(e.totalTax()),
        field(e.grandTotal()),
        field(e.amountInWords()),
        field(e.originalInvoiceRef()),
        e.lineItems() == null ? null : e.lineItems().stream().map(this::lineItem).toList());
  }

  private LineItem lineItem(LineItem item) {
    return new LineItem(
        field(item.lineNo()),
        field(item.chargeType()),
        field(item.description()),
        field(item.hsnSac()),
        field(item.unitPrice()),
        field(item.quantity()),
        field(item.discount()),
        field(item.netAmount()),
        item.taxLines() == null ? null : item.taxLines().stream().map(this::taxLine).toList(),
        field(item.lineTotal()));
  }

  private TaxLine taxLine(TaxLine tax) {
    return new TaxLine(field(tax.type()), field(tax.rate()), field(tax.amount()));
  }

  private <T> Field<T> field(Field<T> field) {
    if (field == null || field.evidence() == null || field.source() != FieldSource.LLM) {
      return field;
    }
    return new Field<>(
        field.value(), field.confidence(), field.source(), evidence(field.evidence()));
  }

  /** The evidence with page and box taken from the cited lines (see the class comment). */
  private Evidence evidence(Evidence cited) {
    List<TextLines.Line> lines =
        cited.lineRefs().stream().map(this::line).flatMap(Optional::stream).distinct().toList();
    if (lines.isEmpty()) {
      return new Evidence(cited.page(), null, cited.lineRefs());
    }
    int page =
        lines.stream().anyMatch(l -> l.page() == cited.page())
            ? cited.page()
            : lines.getFirst().page();
    List<Word> words =
        lines.stream().filter(l -> l.page() == page).flatMap(l -> l.words().stream()).toList();
    return new Evidence(page, union(words), cited.lineRefs());
  }

  private Optional<TextLines.Line> line(String ref) {
    Matcher m = REF.matcher(ref.strip());
    if (!m.matches()) {
      return Optional.empty();
    }
    int page = Integer.parseInt(m.group(1));
    int line = Integer.parseInt(m.group(2));
    return Optional.ofNullable(linesByRef.get(TextLines.ref(page, line)));
  }

  /** The smallest box around {@code words}, clamped to the page's top-left corner. */
  private static BoundingBox union(List<Word> words) {
    double left = Double.POSITIVE_INFINITY;
    double top = Double.POSITIVE_INFINITY;
    double right = Double.NEGATIVE_INFINITY;
    double bottom = Double.NEGATIVE_INFINITY;
    for (Word w : words) {
      left = Math.min(left, w.x());
      top = Math.min(top, w.y());
      right = Math.max(right, w.x() + w.w());
      bottom = Math.max(bottom, w.y() + w.h());
    }
    left = Math.max(0, left);
    top = Math.max(0, top);
    return new BoundingBox(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
  }
}
