package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextLayer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The detectors of the enabled layers in {@link PiiSettings} (PII-2, architecture.md §6.3). Runs
 * them all on a document and returns their spans unmerged; {@link SpanMerger} unions them. Closing
 * it releases the NER model.
 */
public final class PiiDetectors implements AutoCloseable {

  /** Short model name in the NER detector's rule name ({@code ner:<model>}, ADR-15). */
  static final String NER_MODEL_NAME = "distilbert-conll03";

  private final List<PiiDetector> detectors;
  private final NerDetector ner;

  private PiiDetectors(List<PiiDetector> detectors, NerDetector ner) {
    this.detectors = List.copyOf(detectors);
    this.ner = ner;
  }

  /**
   * Builds the enabled layers, in layer order: rules, address block, NER, local LLM.
   *
   * @throws IOException if the NER model can't be loaded
   */
  public static PiiDetectors create(PiiSettings settings) throws IOException {
    var detectors = new ArrayList<PiiDetector>();
    if (settings.rules()) {
      detectors.add(new RegexDetector());
      detectors.add(new ChecksumDetector());
    }
    if (settings.addressBlock()) {
      detectors.add(new AddressBlockDetector());
    }
    NerDetector ner = null;
    if (settings.ner().enabled()) {
      ner = NerDetector.load(NER_MODEL_NAME, settings.ner().modelDir(), settings.ner().threshold());
      detectors.add(ner);
    }
    if (settings.localLlm().enabled()) {
      detectors.add(new LocalLlmDetector(settings.localLlm().endpoint()));
    }
    return new PiiDetectors(detectors, ner);
  }

  /** The enabled detectors, in layer order. */
  public List<PiiDetector> detectors() {
    return detectors;
  }

  /** Spans of every enabled detector on {@code layer}, not yet merged. */
  public List<PiiSpan> detect(TextLayer layer, DocumentContext ctx) {
    var spans = new ArrayList<PiiSpan>();
    for (PiiDetector detector : detectors) {
      spans.addAll(detector.detect(layer, ctx));
    }
    return spans;
  }

  @Override
  public void close() {
    if (ner != null) {
      ner.close();
    }
  }
}
