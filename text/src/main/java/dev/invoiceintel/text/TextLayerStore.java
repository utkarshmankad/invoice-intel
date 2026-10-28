package dev.invoiceintel.text;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.internal.TextLayerRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores {@link TextLayer}s as {@code text_layer.layer} JSONB, one row per Text run
 * (architecture.md §6.2, §7). The layer contains PII; it is read only by the pipeline stages, never
 * by the API.
 */
public final class TextLayerStore {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TextLayerRepository layers;

  public TextLayerStore(JdbcClient jdbc) {
    this.layers = new TextLayerRepository(jdbc);
  }

  /** Inserts the layer of Text run {@code runNo}; each run writes its own row. */
  public void save(DocumentId documentId, int runNo, TextLayer layer) {
    Objects.requireNonNull(layer, "layer");
    layers.insert(
        Objects.requireNonNull(documentId, "documentId"), runNo, JSON.writeValueAsString(layer));
  }

  /** The layer of the latest Text run, if the document has one. */
  public Optional<TextLayer> latest(DocumentId documentId) {
    return layers
        .latest(Objects.requireNonNull(documentId, "documentId"))
        .map(json -> JSON.readValue(json, TextLayer.class));
  }
}
