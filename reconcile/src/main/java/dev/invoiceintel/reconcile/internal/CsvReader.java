package dev.invoiceintel.reconcile.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Splits RFC 4180 CSV text into records: comma-separated fields, double-quoted fields may hold
 * commas, line breaks and doubled quotes. Accepts CRLF and LF line ends, a leading byte-order mark
 * and a missing final line end; skips blank lines. Fields are not trimmed.
 */
public final class CsvReader {

  private CsvReader() {}

  /**
   * The records of {@code text}, or empty when a quoted field is never closed. Error results name
   * no content (PII-8).
   */
  public static Optional<List<List<String>>> read(String text) {
    var records = new ArrayList<List<String>>();
    var record = new ArrayList<String>();
    var field = new StringBuilder();
    boolean quoted = false;
    boolean fieldStarted = false;
    int i = text.startsWith("﻿") ? 1 : 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          field.append(c);
        }
      } else if (c == '"' && field.isEmpty()) {
        quoted = true;
        fieldStarted = true;
      } else if (c == ',') {
        record.add(field.toString());
        field.setLength(0);
        fieldStarted = true;
      } else if (c == '\r' || c == '\n') {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        endRecord(records, record, field, fieldStarted);
        record = new ArrayList<>();
        fieldStarted = false;
      } else {
        field.append(c);
        fieldStarted = true;
      }
      i++;
    }
    if (quoted) {
      return Optional.empty();
    }
    endRecord(records, record, field, fieldStarted);
    return Optional.of(records);
  }

  private static void endRecord(
      List<List<String>> records, List<String> record, StringBuilder field, boolean fieldStarted) {
    if (fieldStarted || !record.isEmpty() || !field.isEmpty()) {
      record.add(field.toString());
      records.add(List.copyOf(record));
    }
    field.setLength(0);
  }
}
