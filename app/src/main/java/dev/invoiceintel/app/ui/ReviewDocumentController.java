package dev.invoiceintel.app.ui;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.pii.RedactedPageReader;
import dev.invoiceintel.pipeline.DocumentDetails;
import dev.invoiceintel.pipeline.DocumentDetailsReader;
import dev.invoiceintel.pipeline.DocumentState;
import dev.invoiceintel.pipeline.InvalidCorrectionException;
import dev.invoiceintel.pipeline.ReviewNotAllowedException;
import dev.invoiceintel.pipeline.ReviewService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The document page (REV-2, architecture.md §6.8, ADR-29): each masked page image with the
 * extraction's evidence boxes as an SVG overlay, next to the field form, which saves corrections
 * and approves the document (REV-3, ADR-30). Shows only masked page images ({@code REDACTED_PAGE})
 * and pseudonymized values, never originals, page renders or document text.
 */
@Controller
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; the UI requires login (architecture.md §11)")
class ReviewDocumentController {

  private final DocumentDetailsReader details;
  private final RedactedPageReader pages;
  private final ReviewService review;

  ReviewDocumentController(
      DocumentDetailsReader details, RedactedPageReader pages, ReviewService review) {
    this.details = details;
    this.pages = pages;
    this.review = review;
  }

  /** Prefix of the form's field inputs, so the CSRF field and others are never taken as paths. */
  static final String FIELD_PREFIX = "field:";

  /** 404 for an unknown document. {@code error} shows why the last correction was rejected. */
  @GetMapping("/review/{id}")
  String document(
      @PathVariable UUID id, @RequestParam(required = false) String error, Model model) {
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
    model.addAttribute("editable", document.state() == DocumentState.NEEDS_REVIEW);
    model.addAttribute("error", "invalid".equals(error));
    return "review-document";
  }

  /**
   * Saves the form's changed fields as corrections (REV-3, ADR-30) and shows the page again; a
   * rejected correction stores nothing and comes back as {@code ?error=invalid}. 404 for an unknown
   * document, 409 if it isn't in review.
   */
  @PostMapping("/review/{id}/fields")
  String correct(
      @PathVariable UUID id, @RequestParam Map<String, String> form, Principal principal) {
    var values = new LinkedHashMap<String, String>();
    form.forEach(
        (name, value) -> {
          if (name.startsWith(FIELD_PREFIX)) {
            values.put(name.substring(FIELD_PREFIX.length()), value);
          }
        });
    try {
      if (review.correct(new DocumentId(id), values, principal.getName()) < 0) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
    } catch (InvalidCorrectionException e) {
      return "redirect:/review/" + id + "?error=invalid";
    } catch (ReviewNotAllowedException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
    return "redirect:/review/" + id;
  }

  /** Approves the document (→ REVIEWED) and goes back to the queue; 404/409 as for corrections. */
  @PostMapping("/review/{id}/approve")
  String approve(@PathVariable UUID id) {
    try {
      if (!review.approve(new DocumentId(id))) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
    } catch (ReviewNotAllowedException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
    return "redirect:/review";
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
