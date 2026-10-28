package dev.invoiceintel.text;

import java.util.Objects;

/**
 * One word and its box: {@code x}, {@code y} is the top-left corner, in the page's unit (see {@link
 * TextPage}). {@code confidence} is 1 for PDF text and the OCR word confidence (0–1) otherwise.
 */
public record Word(String text, double x, double y, double w, double h, double confidence) {

  public Word {
    Objects.requireNonNull(text, "text");
    if (text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
  }
}
