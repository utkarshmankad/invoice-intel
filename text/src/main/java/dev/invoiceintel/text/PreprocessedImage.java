package dev.invoiceintel.text;

/**
 * A page image ready for OCR (TXT-3): binarized PNG and the skew in degrees that was corrected
 * (positive = content was turned clockwise).
 */
public record PreprocessedImage(byte[] png, double skewDegrees) {

  public PreprocessedImage {
    png = png.clone();
  }

  @Override
  public byte[] png() {
    return png.clone();
  }
}
