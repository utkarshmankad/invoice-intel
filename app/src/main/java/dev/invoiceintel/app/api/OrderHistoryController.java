package dev.invoiceintel.app.api;

import dev.invoiceintel.app.reconcile.MappingFileReader;
import dev.invoiceintel.app.reconcile.OrderHistoryProperties;
import dev.invoiceintel.reconcile.OrderHistoryImporter;
import dev.invoiceintel.reconcile.SkipReason;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Order-history import (REC-1, architecture.md §6.7, §8). The response carries counts and codes
 * only; order numbers are stored as tokens and never returned.
 */
@RestController
@RequestMapping("/api/order-history")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; /api/** requires admin auth (ADR-12)")
public class OrderHistoryController {

  private final OrderHistoryImporter importer;
  private final OrderHistoryProperties properties;

  public OrderHistoryController(OrderHistoryImporter importer, OrderHistoryProperties properties) {
    this.importer = importer;
    this.properties = properties;
  }

  /**
   * Imports one UTF-8 CSV, sent as the multipart part {@code file}. The mapping is the entry of the
   * mapping file whose columns are exactly the CSV's header; the file name is ignored. 400 when no
   * mapping fits or the CSV is malformed.
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ImportView importCsv(@RequestPart("file") MultipartFile file) {
    var mappings = MappingFileReader.read(properties.mappingFile());
    var imported = importer.importCsv(text(file), mappings);
    return new ImportView(
        imported.importId().value(),
        imported.mapping(),
        imported.kind(),
        imported.imported(),
        imported.skipped());
  }

  private static String text(MultipartFile file) {
    try {
      return new String(file.getBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read upload", e);
    }
  }

  /**
   * One import's outcome.
   *
   * @param mapping the mapping file entry used, by its {@code file} name
   * @param skipped rows not imported, per reason
   */
  public record ImportView(
      UUID importId, String mapping, String kind, int imported, Map<SkipReason, Integer> skipped) {

    public ImportView {
      skipped = Map.copyOf(skipped);
    }
  }
}
