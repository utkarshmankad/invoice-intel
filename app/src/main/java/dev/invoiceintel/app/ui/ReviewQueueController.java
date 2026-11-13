package dev.invoiceintel.app.ui;

import dev.invoiceintel.app.ui.ReviewQueueView.Direction;
import dev.invoiceintel.app.ui.ReviewQueueView.QueueRow;
import dev.invoiceintel.pipeline.ReviewQueueReader;
import dev.invoiceintel.pipeline.ReviewQueueReader.Sort;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The review queue page (REV-1, architecture.md §6.8): documents in {@code NEEDS_REVIEW} with their
 * score and reason codes, sortable by upload time or score. Shows IDs, codes and reason messages
 * only, never extraction values or document text (PII-8).
 */
@Controller
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "Inventory marker, not a defect; the UI requires login (architecture.md §11)")
class ReviewQueueController {

  private final ReviewQueueReader queue;

  ReviewQueueController(ReviewQueueReader queue) {
    this.queue = queue;
  }

  /**
   * Oldest upload first by default. {@code sort} is {@code RECEIVED} or {@code SCORE} and {@code
   * dir} is {@code ASC} or {@code DESC}; other values are a 400.
   */
  @GetMapping("/review")
  String queue(
      @RequestParam(defaultValue = "RECEIVED") Sort sort,
      @RequestParam(defaultValue = "ASC") Direction dir,
      Model model) {
    var rows = queue.list(sort, dir == Direction.DESC).stream().map(QueueRow::of).toList();
    model.addAttribute("rows", rows);
    model.addAttribute("limit", ReviewQueueReader.LIMIT);
    model.addAttribute("receivedLink", ReviewQueueView.link(Sort.RECEIVED, sort, dir));
    model.addAttribute("scoreLink", ReviewQueueView.link(Sort.SCORE, sort, dir));
    model.addAttribute("receivedAria", ReviewQueueView.aria(Sort.RECEIVED, sort, dir));
    model.addAttribute("scoreAria", ReviewQueueView.aria(Sort.SCORE, sort, dir));
    return "review-queue";
  }
}
