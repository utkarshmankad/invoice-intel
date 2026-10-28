package dev.invoiceintel.text;

import dev.invoiceintel.text.internal.OpenCvLoader;
import java.util.ArrayList;
import java.util.Collections;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Prepares a page image for OCR (TXT-3, architecture.md §6.2): grayscale, deskew, adaptive
 * threshold, then a light median denoise. The skew is the median angle of the minimum-area
 * rectangles around the text lines; one rectangle around all ink is thrown off by ragged layouts.
 */
public final class ImagePreprocessor {

  /** Skews beyond this are treated as layout, not scan tilt, and left alone. */
  static final double MAX_SKEW_DEGREES = 15;

  // Neighbourhood and offset of the adaptive threshold; sized for 300 DPI text.
  private static final int THRESHOLD_BLOCK_SIZE = 31;
  private static final double THRESHOLD_OFFSET = 15;
  private static final int DENOISE_KERNEL = 3;
  // Words are merged into lines with a kernel 1/40 of the page wide; blobs count as lines from
  // 1/10 of the page width and a 5:1 aspect.
  private static final int LINE_KERNEL_FRACTION = 40;
  private static final int MIN_LINE_FRACTION = 10;
  private static final double MIN_LINE_ASPECT = 5;

  public ImagePreprocessor() {
    OpenCvLoader.load();
  }

  /**
   * Preprocesses an encoded image (PNG or JPEG).
   *
   * @throws TextExtractionException if the bytes are not a decodable image
   */
  public PreprocessedImage preprocess(byte[] image) {
    Mat gray = decodeGray(image);
    Mat deskewed = new Mat();
    Mat binary = new Mat();
    Mat denoised = new Mat();
    try {
      double skew = skewOf(gray);
      rotate(gray, skew, deskewed);
      Imgproc.adaptiveThreshold(
          deskewed,
          binary,
          255,
          Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
          Imgproc.THRESH_BINARY,
          THRESHOLD_BLOCK_SIZE,
          THRESHOLD_OFFSET);
      Imgproc.medianBlur(binary, denoised, DENOISE_KERNEL);
      return new PreprocessedImage(encodePng(denoised), skew);
    } finally {
      gray.release();
      deskewed.release();
      binary.release();
      denoised.release();
    }
  }

  /**
   * Skew of the page content in degrees, positive when the content is turned clockwise; 0 for a
   * blank page or a skew beyond {@value #MAX_SKEW_DEGREES}°.
   *
   * @throws TextExtractionException if the bytes are not a decodable image
   */
  public double estimateSkew(byte[] image) {
    Mat gray = decodeGray(image);
    try {
      return skewOf(gray);
    } finally {
      gray.release();
    }
  }

  private static double skewOf(Mat gray) {
    Mat ink = new Mat();
    Mat lines = new Mat();
    Mat kernel =
        Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, new Size(Math.max(gray.cols() / LINE_KERNEL_FRACTION, 3), 1));
    var contours = new ArrayList<MatOfPoint>();
    Mat hierarchy = new Mat();
    try {
      Imgproc.threshold(gray, ink, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
      // Speckles would otherwise join the line blobs.
      Imgproc.medianBlur(ink, ink, DENOISE_KERNEL);
      // Merge the words of a line into one blob, so each blob's rectangle follows the baseline.
      Imgproc.dilate(ink, lines, kernel);
      Imgproc.findContours(
          lines, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
      var angles = new ArrayList<Double>();
      double minLength = gray.cols() / (double) MIN_LINE_FRACTION;
      for (MatOfPoint contour : contours) {
        var points = new MatOfPoint2f(contour.toArray());
        RotatedRect rect = Imgproc.minAreaRect(points);
        points.release();
        double longSide = Math.max(rect.size.width, rect.size.height);
        double shortSide = Math.min(rect.size.width, rect.size.height);
        if (longSide >= minLength && longSide >= MIN_LINE_ASPECT * shortSide) {
          // OpenCV reports the angle of the rectangle's width side in (0, 90]; fold the long side's
          // angle into (-45, 45].
          double angle = rect.size.width >= rect.size.height ? rect.angle : rect.angle - 90;
          angles.add(angle > 45 ? angle - 90 : angle);
        }
      }
      if (angles.isEmpty()) {
        return 0;
      }
      Collections.sort(angles);
      double skew = angles.get(angles.size() / 2);
      return Math.abs(skew) > MAX_SKEW_DEGREES ? 0 : skew;
    } finally {
      ink.release();
      lines.release();
      kernel.release();
      hierarchy.release();
      contours.forEach(Mat::release);
    }
  }

  private static void rotate(Mat source, double skew, Mat target) {
    if (skew == 0) {
      source.copyTo(target);
      return;
    }
    var center = new Point(source.cols() / 2.0, source.rows() / 2.0);
    Mat rotation = Imgproc.getRotationMatrix2D(center, skew, 1);
    try {
      Imgproc.warpAffine(
          source,
          target,
          rotation,
          source.size(),
          Imgproc.INTER_CUBIC,
          Core.BORDER_CONSTANT,
          new Scalar(255));
    } finally {
      rotation.release();
    }
  }

  private static Mat decodeGray(byte[] image) {
    var encoded = new MatOfByte(image);
    try {
      Mat gray = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
      if (gray.empty()) {
        gray.release();
        throw new TextExtractionException("could not decode page image", null);
      }
      return gray;
    } finally {
      encoded.release();
    }
  }

  private static byte[] encodePng(Mat image) {
    var out = new MatOfByte();
    try {
      if (!Imgcodecs.imencode(".png", image, out)) {
        throw new TextExtractionException("could not encode page image", null);
      }
      return out.toArray();
    } finally {
      out.release();
    }
  }
}
