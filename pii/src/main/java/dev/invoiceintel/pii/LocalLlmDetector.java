package dev.invoiceintel.pii;

import dev.invoiceintel.text.TextLayer;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Optional local LLM layer (c) of PII detection (PII-2(c), architecture.md §6.3). Off by default,
 * and only allowed with an endpoint on this machine, because it sees document text before
 * redaction.
 *
 * <p>This is a stub: it checks the endpoint and reports no spans, and it never opens a connection.
 * Since layers only add spans, enabling it can't lower recall.
 */
public final class LocalLlmDetector implements PiiDetector {

  // ASCII-only matching instead of Unicode case folding (FindSecBugs IMPROPER_UNICODE).
  private static final Pattern HTTP = Pattern.compile("https?", Pattern.CASE_INSENSITIVE);
  private static final Pattern LOOPBACK_HOST =
      Pattern.compile(
          "localhost|\\[::1]|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", Pattern.CASE_INSENSITIVE);

  private final URI endpoint;

  /**
   * @param endpoint the local model server, e.g. {@code http://127.0.0.1:11434}
   * @throws IllegalArgumentException if the endpoint isn't an http(s) URL on a loopback host
   */
  public LocalLlmDetector(URI endpoint) {
    this.endpoint = requireLoopback(endpoint);
  }

  /** The endpoint the detector would call. */
  public URI endpoint() {
    return endpoint;
  }

  @Override
  public List<PiiSpan> detect(TextLayer layer, DocumentContext ctx) {
    return List.of();
  }

  /**
   * Accepts only literal loopback hosts ({@code localhost}, {@code 127.x.x.x}, {@code [::1]}), with
   * no DNS lookup, so a host name can't be pointed elsewhere.
   */
  static URI requireLoopback(URI endpoint) {
    if (endpoint == null) {
      throw new IllegalArgumentException("local LLM endpoint is not set");
    }
    String scheme = endpoint.getScheme();
    String host = endpoint.getHost();
    if (scheme == null
        || host == null
        || !HTTP.matcher(scheme).matches()
        || !LOOPBACK_HOST.matcher(host).matches()) {
      throw new IllegalArgumentException(
          "local LLM endpoint must be an http(s) URL on a loopback host");
    }
    return endpoint;
  }
}
