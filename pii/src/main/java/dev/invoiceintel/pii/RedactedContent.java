package dev.invoiceintel.pii;

import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.text.Word;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A document with its PII replaced by tokens or redaction markers: the only input a remote model
 * may receive (PII-1, architecture.md §6.4). The constructors are package-private, so only the
 * {@code pii} module can create one, and {@link TextRedactor} only does so from merged PII spans.
 * Nothing in here holds an original PII value.
 *
 * <p>Per page it keeps the redacted words with their boxes, so extraction evidence still maps to
 * page positions (architecture.md §6.3, §6.5), and, once {@link ImageRedactor} ran, the page image
 * with the same PII masked (PII-5) for the vision strategies.
 */
public final class RedactedContent {

  private final DocumentId documentId;
  private final RedactionMode mode;
  private final List<Page> pages;

  RedactedContent(DocumentId documentId, RedactionMode mode, List<Page> pages) {
    this.documentId = Objects.requireNonNull(documentId, "documentId");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
  }

  public DocumentId documentId() {
    return documentId;
  }

  public RedactionMode mode() {
    return mode;
  }

  /** Pages in text-layer order. */
  public List<Page> pages() {
    return pages;
  }

  /** The same document with {@code pages} in place of its pages (e.g. with masked images). */
  RedactedContent withPages(List<Page> pages) {
    return new RedactedContent(documentId, mode, pages);
  }

  /** The redacted text of all pages, one page per line, in page order. */
  public String text() {
    return pages.stream().map(Page::text).collect(Collectors.joining("\n"));
  }

  /** IDs and counts only: the text may be logged by mistake, so it is never printed (PII-8). */
  @Override
  public String toString() {
    return "RedactedContent[documentId="
        + documentId
        + ", mode="
        + mode
        + ", pages="
        + pages.size()
        + "]";
  }

  /**
   * One redacted page. {@link #text()} is {@link #words()} joined by single spaces, the same
   * convention as {@link PageText}, so a character offset maps back to a word and its box.
   *
   * @see TextRedactor for how PII words become token words
   */
  public static final class Page {

    private final int pageNo;
    private final double width;
    private final double height;
    private final List<Word> words;
    private final String text;
    private final byte[] image;

    Page(int pageNo, double width, double height, List<Word> words) {
      this(pageNo, width, height, words, null);
    }

    private Page(int pageNo, double width, double height, List<Word> words, byte[] image) {
      if (pageNo < 1) {
        throw new IllegalArgumentException("pageNo must be >= 1");
      }
      this.pageNo = pageNo;
      this.width = width;
      this.height = height;
      this.words = List.copyOf(Objects.requireNonNull(words, "words"));
      this.text = this.words.stream().map(Word::text).collect(Collectors.joining(" "));
      this.image = image == null ? null : image.clone();
    }

    /** This page with {@code png} as its masked image ({@link ImageRedactor}). */
    Page withImage(byte[] png) {
      return new Page(pageNo, width, height, words, Objects.requireNonNull(png, "png"));
    }

    /** 1-based page number. */
    public int pageNo() {
      return pageNo;
    }

    /** Page width in the unit of the word boxes (see {@code TextPage}). */
    public double width() {
      return width;
    }

    /** Page height in the unit of the word boxes. */
    public double height() {
      return height;
    }

    /** Words in text-layer order; PII words are replaced by token or marker words. */
    public List<Word> words() {
      return words;
    }

    public String text() {
      return text;
    }

    /**
     * The page image as PNG with every PII word masked (PII-5), or empty before {@link
     * ImageRedactor} ran. Each call returns a copy.
     */
    public Optional<byte[]> image() {
      return Optional.ofNullable(image).map(byte[]::clone);
    }

    @Override
    public String toString() {
      return "RedactedContent.Page[pageNo="
          + pageNo
          + ", words="
          + words.size()
          + ", image="
          + (image != null)
          + "]";
    }
  }
}
