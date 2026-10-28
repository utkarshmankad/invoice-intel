import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Owner-run data preparation for plan tasks D2–D4. Runs on the owner's machine only: it reads the
 * Amazon.in export, picks a varied invoice set and copies it into the git-ignored data folders.
 *
 * <p>PII rule (.claude/rules/pii.md): the console output is aggregate counts, years and CSV column
 * names only. It never prints file names, paths, text or values, so it is safe to share. The only
 * file that maps copies back to sources is {@code data/raw/_selection.csv}, which stays private.
 *
 * <p>Usage: {@code scripts/data/select-invoices.sh <export-dir> [--count 20] [--scans 5] [--apply]
 * [--force]}. Without {@code --apply} it only reports what it would do.
 */
public final class SelectInvoices {

  private static final LocalDate GST_START = LocalDate.of(2017, 7, 1);
  private static final int FIRST_YEAR = 2013;
  private static final int LAST_YEAR = 2026;

  // D3 quotas (plan.md): ≥2 credit notes, ≥3 pre-GST, Amazon Retail and third-party sellers.
  private static final int MIN_CREDIT_NOTES = 2;
  private static final int MIN_PRE_GST = 3;
  private static final int MIN_PER_SELLER = 3;
  private static final int MIN_PER_TAX = 3;
  private static final int MIN_MULTI_PAGE = 2;

  // Amazon's own retail entities on Amazon.in invoices; every other seller is third-party.
  private static final Pattern RETAIL_SELLER =
      Pattern.compile(
          "cloudtail|appario|amazon retail|clicktech|amazon wholesale|amazon seller services",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CREDIT_NOTE =
      Pattern.compile("credit\\s+note", Pattern.CASE_INSENSITIVE);
  private static final Pattern IGST = Pattern.compile("\\bIGST\\b");
  private static final Pattern CGST = Pattern.compile("\\bCGST\\b");
  private static final Pattern GST_WORD = Pattern.compile("\\bGST(IN)?\\b");
  private static final Pattern VAT_OR_CST = Pattern.compile("\\b(VAT|CST|TIN)\\b");
  private static final String DATE = "(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{4})";
  private static final Pattern INVOICE_DATE =
      Pattern.compile("invoice\\s*date\\s*[:\\-]?\\s*" + DATE, Pattern.CASE_INSENSITIVE);
  private static final Pattern ORDER_DATE =
      Pattern.compile("order\\s*date\\s*[:\\-]?\\s*" + DATE, Pattern.CASE_INSENSITIVE);
  private static final Pattern ANY_DATE = Pattern.compile("\\b" + DATE + "\\b");
  // ISO yyyy-mm-dd, as in some exports and the synthetic fixtures.
  private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

  // Column → order_history_item field (architecture.md §7); unknown columns map to null.
  private static final Map<String, String> KNOWN_COLUMNS =
      Map.of(
          "order id", "order_token",
          "order date", "order_date",
          "product name", "product_name",
          "asin", "asin",
          "quantity", "quantity",
          "unit price", "unit_price",
          "total owed", "item_total");

  enum Kind {
    GST_INVOICE,
    CREDIT_NOTE,
    PRE_GST
  }

  enum Seller {
    AMAZON_RETAIL,
    THIRD_PARTY,
    UNKNOWN
  }

  enum Tax {
    IGST,
    CGST_SGST,
    NONE
  }

  record Invoice(
      Path source,
      String extension,
      int pages,
      boolean hasText,
      Integer year,
      Kind kind,
      Seller seller,
      Tax tax) {

    boolean multiPage() {
      return pages > 1;
    }
  }

  public static void main(String[] args) throws IOException {
    var options = Options.parse(args);
    Path repo = Path.of("").toAbsolutePath();
    if (!Files.isDirectory(repo.resolve("data/raw"))) {
      fail("run from the repository root (data/raw not found)");
    }
    if (!Files.isDirectory(options.export)) {
      fail("export directory not found");
    }

    List<Path> files;
    try (Stream<Path> walk = Files.walk(options.export)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }

    var errors = new TreeMap<String, Integer>();
    var csvs = new ArrayList<Path>();
    var invoices = new ArrayList<Invoice>();
    var seen = new HashSet<String>();
    int duplicates = 0;
    for (Path file : files) {
      String ext = extension(file);
      try {
        if (ext.equals("csv")) {
          csvs.add(file);
          continue;
        }
        if (!Set.of("pdf", "jpg", "jpeg", "png").contains(ext)) {
          continue;
        }
        if (!seen.add(sha256(file))) {
          duplicates++;
          continue;
        }
        invoices.add(ext.equals("pdf") ? analysePdf(file) : image(file, ext));
      } catch (IOException | RuntimeException e) {
        // Class name only: messages of PDF and IO errors can contain paths.
        errors.merge(e.getClass().getSimpleName(), 1, Integer::sum);
      }
    }

    System.out.println("== Export scan (counts only)");
    System.out.printf("files: %d, CSV: %d, invoice candidates: %d, duplicate bytes skipped: %d%n",
        files.size(), csvs.size(), invoices.size(), duplicates);
    if (!errors.isEmpty()) {
      System.out.println("unreadable files by error type: " + errors);
    }
    describe("corpus", invoices);

    orderHistory(repo, csvs, options);

    var digital = invoices.stream().filter(i -> i.extension.equals("pdf") && i.hasText).toList();
    var selected = selectRich(digital, options.count);
    describe("D3 selection", selected);
    reportD3Gaps(selected, options.count);

    var scans =
        invoices.stream().filter(i -> !i.hasText).sorted(byYear()).limit(options.scans).toList();
    System.out.printf("%n== D4: scans/photos in the export: %d, selected: %d%n",
        invoices.stream().filter(i -> !i.hasText).count(), scans.size());
    if (scans.size() < 3) {
      System.out.printf(
          "D4 needs 3–5: photograph or print-and-scan %d more invoices yourself and drop them in"
              + " data/raw/ as scan-NNN.jpg/png.%n",
          3 - scans.size());
    }

    if (!options.apply) {
      System.out.println("\nDry run: nothing copied. Re-run with --apply to copy.");
      return;
    }
    copy(repo, selected, scans, options.force);
  }

  // ---------------------------------------------------------------------------------------------
  // D2: order history

  private static void orderHistory(Path repo, List<Path> csvs, Options options)
      throws IOException {
    var orderFiles = new ArrayList<Path>();
    var columns = new LinkedHashSet<String>();
    for (Path csv : csvs) {
      List<String> header = header(csv);
      var lower = header.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
      if (lower.contains("order id") && lower.contains("order date")) {
        orderFiles.add(csv);
        columns.addAll(header);
      }
    }
    System.out.printf("%n== D2: order-history CSV files found: %d, distinct columns: %d%n",
        orderFiles.size(), columns.size());
    if (orderFiles.isEmpty()) {
      System.out.println(
          "No CSV with 'Order ID' and 'Order Date' columns. The order history comes from the"
              + " 'Request Your Data' export (Retail.OrderHistory.*.csv), not from invoice downloads.");
      return;
    }
    // Column names are not PII (they are Amazon's export schema), so they may be printed.
    columns.forEach(c -> System.out.println("  column: " + c));
    if (!options.apply) {
      return;
    }
    Path target = repo.resolve("data/order-history");
    for (int i = 0; i < orderFiles.size(); i++) {
      Files.copy(
          orderFiles.get(i),
          target.resolve("order-history-%d.csv".formatted(i + 1)),
          StandardCopyOption.REPLACE_EXISTING);
    }
    writeMapping(repo.resolve("config/order-history-mapping.yml"), columns, orderFiles.size());
    System.out.println("Copied to data/order-history/ and wrote config/order-history-mapping.yml");
  }

  private static void writeMapping(Path file, Set<String> columns, int fileCount)
      throws IOException {
    Files.createDirectories(file.getParent());
    var yml = new StringBuilder();
    yml.append("# Columns of the Amazon.in order-history export (plan D2, REC-1). Column names\n")
        .append("# only, never values. `target` is the order_history_item field\n")
        .append("# (architecture.md §7), or null when the column isn't imported.\n")
        .append("# Generated by scripts/data/select-invoices.sh from ")
        .append(fileCount)
        .append(" file(s).\n")
        .append("columns:\n");
    for (String column : columns) {
      String target = KNOWN_COLUMNS.get(column.toLowerCase(Locale.ROOT));
      yml.append("  - name: \"")
          .append(column.replace("\"", "\\\""))
          .append("\"\n    target: ")
          .append(target == null ? "null" : target)
          .append('\n');
    }
    Files.writeString(file, yml, StandardCharsets.UTF_8);
  }

  private static List<String> header(Path csv) throws IOException {
    try (var reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
      String line = reader.readLine();
      if (line == null) {
        return List.of();
      }
      line = line.replace("﻿", "");
      var cells = new ArrayList<String>();
      var cell = new StringBuilder();
      boolean quoted = false;
      for (char c : line.toCharArray()) {
        if (c == '"') {
          quoted = !quoted;
        } else if (c == ',' && !quoted) {
          cells.add(cell.toString().trim());
          cell.setLength(0);
        } else {
          cell.append(c);
        }
      }
      cells.add(cell.toString().trim());
      return cells;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Classification

  private static Invoice analysePdf(Path file) throws IOException {
    try (var pdf = Loader.loadPDF(file.toFile())) {
      int pages = pdf.getNumberOfPages();
      var stripper = new PDFTextStripper();
      stripper.setEndPage(Math.min(pages, 3));
      String text = stripper.getText(pdf);
      boolean hasText = text.strip().length() > 50;
      if (!hasText) {
        return new Invoice(file, "pdf", pages, false, null, null, Seller.UNKNOWN, Tax.NONE);
      }
      Integer year = date(text).map(LocalDate::getYear).orElse(null);
      boolean preGstDate = date(text).map(d -> d.isBefore(GST_START)).orElse(false);
      boolean preGstText = !GST_WORD.matcher(text).find() && VAT_OR_CST.matcher(text).find();
      Kind kind =
          CREDIT_NOTE.matcher(text).find()
              ? Kind.CREDIT_NOTE
              : preGstDate || preGstText ? Kind.PRE_GST : Kind.GST_INVOICE;
      Seller seller =
          RETAIL_SELLER.matcher(text).find() ? Seller.AMAZON_RETAIL : Seller.THIRD_PARTY;
      Tax tax =
          IGST.matcher(text).find()
              ? Tax.IGST
              : CGST.matcher(text).find() ? Tax.CGST_SGST : Tax.NONE;
      return new Invoice(file, "pdf", pages, true, year, kind, seller, tax);
    }
  }

  private static Invoice image(Path file, String ext) {
    return new Invoice(file, ext.equals("jpeg") ? "jpg" : ext, 1, false, null, null,
        Seller.UNKNOWN, Tax.NONE);
  }

  private static Optional<LocalDate> date(String text) {
    for (Pattern p : List.of(INVOICE_DATE, ORDER_DATE, ANY_DATE, ISO_DATE)) {
      boolean iso = p == ISO_DATE;
      Matcher m = p.matcher(text);
      while (m.find()) {
        try {
          var d =
              LocalDate.of(
                  Integer.parseInt(m.group(iso ? 1 : 3)),
                  Integer.parseInt(m.group(2)),
                  Integer.parseInt(m.group(iso ? 3 : 1)));
          if (d.getYear() >= 2010 && d.getYear() <= LAST_YEAR) {
            return Optional.of(d);
          }
        } catch (DateTimeException ignored) {
          // Not a dd.mm.yyyy date; try the next match.
        }
      }
    }
    return Optional.empty();
  }

  // ---------------------------------------------------------------------------------------------
  // D3: pick a varied set

  /**
   * Greedy cover: each pick is the candidate that fills the most unmet quotas (credit notes,
   * pre-GST, both seller types, both tax types, multi-page, one per year 2013–2026); ties go to the
   * least-covered year. Deterministic for the same export.
   */
  static List<Invoice> selectRich(List<Invoice> candidates, int count) {
    var pool = new ArrayList<>(candidates);
    pool.sort(Comparator.comparing((Invoice i) -> i.source.toString()));
    var chosen = new ArrayList<Invoice>();
    while (chosen.size() < count && !pool.isEmpty()) {
      Invoice best = null;
      int bestGain = -1;
      int bestYearCount = Integer.MAX_VALUE;
      for (Invoice candidate : pool) {
        int gain = gain(chosen, candidate);
        int yearCount = yearCount(chosen, candidate.year);
        if (gain > bestGain || (gain == bestGain && yearCount < bestYearCount)) {
          best = candidate;
          bestGain = gain;
          bestYearCount = yearCount;
        }
      }
      chosen.add(best);
      pool.remove(best);
    }
    chosen.sort(byYear());
    return chosen;
  }

  private static int gain(List<Invoice> chosen, Invoice c) {
    int gain = 0;
    if (c.kind == Kind.CREDIT_NOTE && count(chosen, i -> i.kind == Kind.CREDIT_NOTE) < MIN_CREDIT_NOTES) {
      gain += 10; // hard D3 requirement: beats any mix of soft gains
    }
    if (c.kind == Kind.PRE_GST && count(chosen, i -> i.kind == Kind.PRE_GST) < MIN_PRE_GST) {
      gain += 10;
    }
    if (c.seller != Seller.UNKNOWN && count(chosen, i -> i.seller == c.seller) < MIN_PER_SELLER) {
      gain += 2;
    }
    if (c.tax != Tax.NONE && count(chosen, i -> i.tax == c.tax) < MIN_PER_TAX) {
      gain += 2;
    }
    if (c.multiPage() && count(chosen, Invoice::multiPage) < MIN_MULTI_PAGE) {
      gain += 1;
    }
    if (c.year != null && c.year >= FIRST_YEAR && yearCount(chosen, c.year) == 0) {
      gain += 2;
    }
    return gain;
  }

  private static int yearCount(List<Invoice> chosen, Integer year) {
    return year == null ? Integer.MAX_VALUE / 2 : count(chosen, i -> year.equals(i.year));
  }

  private static int count(List<Invoice> invoices, java.util.function.Predicate<Invoice> test) {
    return (int) invoices.stream().filter(test).count();
  }

  private static void reportD3Gaps(List<Invoice> selected, int count) {
    var gaps = new ArrayList<String>();
    if (selected.size() < count) {
      gaps.add("only %d of %d digital invoices available".formatted(selected.size(), count));
    }
    int credit = count(selected, i -> i.kind == Kind.CREDIT_NOTE);
    if (credit < MIN_CREDIT_NOTES) {
      gaps.add("credit notes %d/%d: download credit notes for returned orders".formatted(credit, MIN_CREDIT_NOTES));
    }
    int preGst = count(selected, i -> i.kind == Kind.PRE_GST);
    if (preGst < MIN_PRE_GST) {
      gaps.add("pre-GST %d/%d: download invoices for orders before July 2017".formatted(preGst, MIN_PRE_GST));
    }
    for (Seller s : List.of(Seller.AMAZON_RETAIL, Seller.THIRD_PARTY)) {
      if (count(selected, i -> i.seller == s) == 0) {
        gaps.add("no " + s + " seller");
      }
    }
    var missingYears = new ArrayList<Integer>();
    for (int y = FIRST_YEAR; y <= LAST_YEAR; y++) {
      int year = y;
      if (count(selected, i -> Integer.valueOf(year).equals(i.year)) == 0) {
        missingYears.add(y);
      }
    }
    if (!missingYears.isEmpty()) {
      gaps.add("years without an invoice: " + missingYears);
    }
    System.out.println(gaps.isEmpty() ? "D3 requirements met." : "D3 gaps:");
    gaps.forEach(g -> System.out.println("  - " + g));
  }

  // ---------------------------------------------------------------------------------------------
  // Output

  private static void describe(String label, List<Invoice> invoices) {
    System.out.printf("%n== %s: %d%n", label, invoices.size());
    System.out.println("  by type:   " + histogram(invoices, i -> i.hasText ? i.extension + "-text" : i.extension + "-scan"));
    var digital = invoices.stream().filter(i -> i.hasText).toList();
    System.out.println("  by kind:   " + histogram(digital, i -> String.valueOf(i.kind)));
    System.out.println("  by seller: " + histogram(digital, i -> String.valueOf(i.seller)));
    System.out.println("  by tax:    " + histogram(digital, i -> String.valueOf(i.tax)));
    System.out.println("  by year:   " + histogram(digital, i -> i.year == null ? "unknown" : i.year.toString()));
    System.out.println("  multi-page: " + count(invoices, Invoice::multiPage));
  }

  private static Map<String, Integer> histogram(
      List<Invoice> invoices, java.util.function.Function<Invoice, String> key) {
    var map = new TreeMap<String, Integer>();
    invoices.forEach(i -> map.merge(key.apply(i), 1, Integer::sum));
    return map;
  }

  private static void copy(Path repo, List<Invoice> selected, List<Invoice> scans, boolean force)
      throws IOException {
    Path raw = repo.resolve("data/raw");
    try (Stream<Path> existing = Files.list(raw)) {
      boolean occupied =
          existing.map(p -> p.getFileName().toString()).anyMatch(n -> n.matches("(inv|scan)-\\d+\\..*"));
      if (occupied && !force) {
        fail("data/raw already has inv-/scan- files; re-run with --force to overwrite");
      }
    }
    var manifest = new StringBuilder("file,source,kind,seller,tax,year,pages\n");
    var names = new LinkedHashMap<Invoice, String>();
    for (int i = 0; i < selected.size(); i++) {
      names.put(selected.get(i), "inv-%03d.%s".formatted(i + 1, selected.get(i).extension));
    }
    for (int i = 0; i < scans.size(); i++) {
      names.put(scans.get(i), "scan-%03d.%s".formatted(i + 1, scans.get(i).extension));
    }
    for (var entry : names.entrySet()) {
      Invoice inv = entry.getKey();
      Files.copy(inv.source, raw.resolve(entry.getValue()), StandardCopyOption.REPLACE_EXISTING);
      manifest.append(String.join(",", entry.getValue(), "\"" + inv.source + "\"",
          String.valueOf(inv.kind), String.valueOf(inv.seller), String.valueOf(inv.tax),
          String.valueOf(inv.year), String.valueOf(inv.pages))).append('\n');
    }
    // Private: maps the neutral names back to source paths. Never share or commit it.
    Files.writeString(raw.resolve("_selection.csv"), manifest, StandardCharsets.UTF_8);
    System.out.printf("%nCopied %d invoices and %d scans to data/raw/ (manifest: data/raw/_selection.csv, private).%n",
        selected.size(), scans.size());
  }

  // ---------------------------------------------------------------------------------------------

  private static Comparator<Invoice> byYear() {
    return Comparator.comparing((Invoice i) -> i.year == null ? 0 : i.year)
        .thenComparing(i -> i.source.toString());
  }

  private static String extension(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      for (int n; (n = in.read(buffer)) > 0; ) {
        digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void fail(String message) {
    System.err.println("select-invoices: " + message);
    System.exit(2);
  }

  record Options(Path export, int count, int scans, boolean apply, boolean force) {

    static Options parse(String[] args) {
      Path export = null;
      int count = 20;
      int scans = 5;
      boolean apply = false;
      boolean force = false;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--count" -> count = Integer.parseInt(args[++i]);
          case "--scans" -> scans = Integer.parseInt(args[++i]);
          case "--apply" -> apply = true;
          case "--force" -> force = true;
          default -> {
            if (export != null) {
              throw new IllegalArgumentException("unexpected argument");
            }
            export = Path.of(args[i]);
          }
        }
      }
      if (export == null) {
        throw new IllegalArgumentException(
            "usage: select-invoices.sh <export-dir> [--count 20] [--scans 5] [--apply] [--force]");
      }
      return new Options(export, count, scans, apply, force);
    }
  }

  private SelectInvoices() {}

  static {
    // Silence PDFBox font warnings: they can name embedded fonts but add nothing here.
    java.util.logging.Logger.getLogger("org.apache.pdfbox").setLevel(java.util.logging.Level.OFF);
    java.util.logging.Logger.getLogger("org.apache.fontbox").setLevel(java.util.logging.Level.OFF);
  }
}
