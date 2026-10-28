package dev.invoiceintel.text.internal;

import nu.pattern.OpenCV;

/** Loads the OpenCV native library bundled for this platform, once per JVM. */
public final class OpenCvLoader {

  private OpenCvLoader() {}

  public static void load() {
    Holder.touch();
  }

  /** Class initialization runs the load exactly once, thread-safely. */
  private static final class Holder {

    static {
      OpenCV.loadLocally();
    }

    static void touch() {}
  }
}
