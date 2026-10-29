package dev.invoiceintel.extract;

import dev.invoiceintel.pii.RedactedContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

/**
 * Builds the user message of a strategy (EXT-2, architecture.md §6.5) from {@link RedactedContent}
 * only (PII-1): the line-marked redacted text ({@code TEXT}), the masked page images ({@code
 * VISION}) or both ({@code HYBRID}). Images come only from {@link RedactedContent.Page#image()},
 * the copy {@code ImageRedactor} masked (PII-5); a page without one fails the request rather than
 * falling back to anything else.
 */
final class ModelInput {

  private ModelInput() {}

  /**
   * @throws IllegalArgumentException if {@code strategy} sends images and a page has no masked
   *     image
   */
  static UserMessage userMessage(RedactedContent content, ExtractionStrategy strategy) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(strategy, "strategy");
    var text = new StringBuilder();
    if (strategy.sendsText()) {
      text.append(TextLines.markedText(content));
    }
    if (!strategy.sendsImages()) {
      return new UserMessage(text.toString());
    }
    var media = new ArrayList<Media>(content.pages().size());
    for (RedactedContent.Page page : content.pages()) {
      byte[] png =
          page.image()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "page " + page.pageNo() + " has no redacted image"));
      media.add(
          Media.builder()
              .mimeType(MimeTypeUtils.IMAGE_PNG)
              .data(png)
              .name("page-" + page.pageNo())
              .build());
    }
    if (!text.isEmpty()) {
      text.append("\n\n");
    }
    text.append(imageNote(content.pages()));
    return UserMessage.builder().text(text.toString()).media(media).build();
  }

  /** Tells the model which image is which page, so it can cite {@code evidence.page}. */
  private static String imageNote(List<RedactedContent.Page> pages) {
    var note =
        new StringBuilder("Attached are ")
            .append(pages.size())
            .append(" page image(s) in page order:");
    for (int i = 0; i < pages.size(); i++) {
      note.append(i == 0 ? " " : ", ")
          .append("image ")
          .append(i + 1)
          .append(" is page ")
          .append(pages.get(i).pageNo());
    }
    return note.append('.').toString();
  }
}
