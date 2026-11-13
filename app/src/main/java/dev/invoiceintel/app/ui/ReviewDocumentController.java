package dev.invoiceintel.app.ui;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.RedactedPageReader;
import dev.invoiceintel.pipeline.DocumentDetails;
import dev.invoiceintel.pipeline.DocumentDetailsReader;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * The document page (REV-2, architecture.md §6.8, ADR-29): each masked page image with the
 * extraction's evidence boxes as an SVG overlay, next to the field form. Shows only masked page
 * images ({@code REDACTED_PAGE}) and pseudonymized values, never originals, page renders or
 * document text.
 */
@Controller
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; the UI requires login (architecture.md §11)")
class ReviewDocumentController {

  private final DocumentDetailsReader details;
  private final RedactedPageReader pages;

  ReviewDocumentController(DocumentDetailsReader details, RedactedPageReader pages) {
    this.details = details;
    this.pages = pages;
  }

  /** 404 for an unknown document. */
  @GetMapping("/review/{id}")
  String document(@PathVariable UUID id, Model model) {
    var documentId = new DocumentId(id);
    DocumentDetails document =
        details
            .find(documentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    List<ReviewDocumentView.FieldRow> rows = ReviewDocumentView.rows(document.extraction());
    List<PageView> pageViews =
        pages.pages(documentId).stream()
            .map(
                p ->
                    new PageView(
                        p.pageNo(),
                        ReviewDocumentView.svgNumber(p.width()),
                        ReviewDocumentView.svgNumber(p.height()),
                        ReviewDocumentView.boxes(rows, p.pageNo())))
            .toList();
    model.addAttribute("document", document);
    model.addAttribute("rows", rows);
    model.addAttribute("pages", pageViews);
    return "review-document";
  }

  /** The masked PNG of one page; 404 if the document or page has none. */
  @GetMapping("/review/{id}/pages/{pageNo}")
  ResponseEntity<byte[]> page(@PathVariable UUID id, @PathVariable int pageNo) {
    return pages
        .image(new DocumentId(id), pageNo)
        .map(
            png ->
                ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.noStore())
                    .body(png))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /**
   * One page of the overlay: the SVG's view box is the text layer page, so evidence boxes (in the
   * same unit) land on the image stretched over it.
   */
  record PageView(int pageNo, String width, String height, List<ReviewDocumentView.Box> boxes) {

    PageView {
      boxes = List.copyOf(boxes);
    }
  }
}
