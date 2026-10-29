package dev.invoiceintel.pii;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Detection layers and redaction mode of the PII stage (PII-2, PII-3, architecture.md §6.3). Each
 * layer can be switched off on its own, so it can be evaluated alone (PII-2), but at least one of
 * the rule, address-block and NER layers must stay on: with none of them the stage would pass every
 * document through unredacted. The local LLM layer doesn't count, because it is a stub that finds
 * nothing yet.
 *
 * @param mode pseudonymize (tokens + vault) or redact (irreversible)
 * @param rules layer (a) rules: {@link RegexDetector} and {@link ChecksumDetector}
 * @param addressBlock layer (a) layout: {@link AddressBlockDetector}
 * @param ner layer (b): {@link NerDetector}
 * @param localLlm layer (c): {@link LocalLlmDetector}, off by default
 */
public record PiiSettings(
    RedactionMode mode, boolean rules, boolean addressBlock, Ner ner, LocalLlm localLlm) {

  public PiiSettings {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(ner, "ner");
    Objects.requireNonNull(localLlm, "localLlm");
    if (!rules && !addressBlock && !ner.enabled()) {
      throw new IllegalArgumentException(
          "at least one of the rules, address-block and NER layers must be enabled");
    }
  }

  /**
   * NER layer settings. {@code modelDir} is the pinned model snapshot (ADR-15) and is required only
   * when the layer is enabled; {@code threshold} is the entity probability a token needs.
   */
  public record Ner(boolean enabled, Path modelDir, double threshold) {

    public Ner {
      if (enabled && modelDir == null) {
        throw new IllegalArgumentException("NER is enabled but no model directory is set");
      }
      if (!(threshold > 0 && threshold <= 1)) {
        throw new IllegalArgumentException("NER threshold must be in (0, 1]");
      }
    }

    /** NER switched off. */
    public static Ner off() {
      return new Ner(false, null, NerDetector.DEFAULT_THRESHOLD);
    }
  }

  /**
   * Local LLM layer settings. When enabled, {@code endpoint} must be a loopback address (PII-2(c)):
   * document text may only reach a model running on this machine.
   */
  public record LocalLlm(boolean enabled, URI endpoint) {

    public LocalLlm {
      if (enabled) {
        LocalLlmDetector.requireLoopback(endpoint);
      }
    }

    /** Local LLM switched off. */
    public static LocalLlm off() {
      return new LocalLlm(false, null);
    }
  }
}
