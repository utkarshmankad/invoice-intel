package dev.invoiceintel.common.fixtures;

import dev.invoiceintel.common.fixtures.SyntheticInvoice.TaxType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/**
 * Generates synthetic marketplace invoices from a seed: same type and seed, same document. The
 * layout follows the general shape of a GST invoice (seller block, billing address, line table with
 * HSN and tax columns, amount in words) without copying any real document.
 */
public final class SyntheticInvoiceGenerator {

  /** GST replaced VAT/CST on this date; pre-GST invoices are dated before it. */
  public static final LocalDate GST_START = LocalDate.of(2017, 7, 1);

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private record Product(String description, String hsnCode, int gstRate, String vatRate) {}

  private record SellerName(String name, char entityType) {}

  private static final List<Product> PRODUCTS =
      List.of(
          new Product("Stainless Steel Water Bottle 1L", "7323", 12, "5"),
          new Product("USB-C Charging Cable 1m", "8544", 18, "14.5"),
          new Product("Cotton Bath Towel Set of 2", "6302", 12, "5"),
          new Product("A5 Ruled Notebook Pack of 3", "4820", 18, "5"),
          new Product("Wireless Optical Mouse", "8471", 18, "14.5"),
          new Product("Ceramic Coffee Mug 350ml", "6912", 12, "12.5"));

  private static final List<SellerName> SELLERS =
      List.of(
          new SellerName("Samplekart Retail Private Limited", 'C'),
          new SellerName("Fixture Traders LLP", 'F'),
          new SellerName("Demotronics Private Limited", 'C'));

  private static final List<String> FIRST_NAMES = List.of("Aarav", "Meera", "Rohan", "Kavya");
  private static final List<String> LAST_NAMES =
      List.of("Testkumar", "Samplewala", "Fixturekar", "Demoraj");

  private SyntheticInvoiceGenerator() {}

  /** Ground truth for a document of {@code type}; deterministic for a given seed. */
  public static SyntheticInvoice generate(InvoiceType type, long seed) {
    Random random = new Random(seed);
    GstState[] states = GstState.values();
    GstState sellerState = states[random.nextInt(states.length)];
    // Every second document crosses a state border, so both tax splits get covered.
    GstState buyerState =
        random.nextBoolean() ? sellerState : states[(sellerState.ordinal() + 1) % states.length];
    boolean preGst = type == InvoiceType.PRE_GST_INVOICE;

    Seller seller = seller(random, sellerState, preGst);
    Buyer buyer = buyer(random, buyerState);
    boolean intraState = sellerState == buyerState;
    TaxType taxType =
        preGst
            ? (intraState ? TaxType.VAT : TaxType.CST)
            : (intraState ? TaxType.CGST_SGST : TaxType.IGST);

    LocalDate orderDate =
        preGst
            ? GST_START.minusDays(30 + random.nextInt(900))
            : LocalDate.of(2018, 1, 1).plusDays(random.nextInt(2900));
    LocalDate invoiceDate = orderDate.plusDays(random.nextInt(3));

    String originalInvoiceNumber = null;
    String invoiceNumber = documentNumber(random, "INV", sellerState);
    if (type == InvoiceType.CREDIT_NOTE) {
      originalInvoiceNumber = invoiceNumber;
      invoiceNumber = documentNumber(random, "CN", sellerState);
      invoiceDate = invoiceDate.plusDays(7 + random.nextInt(20));
    }

    // A credit note returns a single item; invoices carry one to three.
    int lineCount = type == InvoiceType.CREDIT_NOTE ? 1 : 1 + random.nextInt(3);
    List<LineItem> lines = lineItems(random, lineCount, preGst, taxType);
    BigDecimal net = sum(lines.stream().map(LineItem::netAmount).toList());
    BigDecimal tax = sum(lines.stream().map(LineItem::taxAmount).toList());
    BigDecimal grand = net.add(tax);

    return new SyntheticInvoice(
        type,
        invoiceNumber,
        invoiceDate,
        FakeIds.orderNumber(random),
        orderDate,
        originalInvoiceNumber,
        seller,
        buyer,
        taxType,
        lines,
        net,
        tax,
        grand,
        AmountInWords.of(grand));
  }

  /** Renders {@code invoice} as a one-page digital PDF with a text layer. */
  public static byte[] toPdf(SyntheticInvoice invoice) {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      fixMetadata(document, invoice);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        new Layout(content).draw(invoice);
      }
      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Generates and writes a PDF to {@code target}, creating parent folders. */
  public static SyntheticInvoice writePdf(InvoiceType type, long seed, Path target) {
    SyntheticInvoice invoice = generate(type, seed);
    try {
      Path parent = target.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(target, toPdf(invoice));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return invoice;
  }

  private static Seller seller(Random random, GstState state, boolean preGst) {
    SellerName name = SELLERS.get(random.nextInt(SELLERS.size()));
    String pan = FakeIds.pan(random, name.entityType(), name.name().charAt(0));
    String taxId = preGst ? FakeIds.tin(random, state) : FakeIds.gstin(state, pan, '1');
    String street = "Unit " + (1 + random.nextInt(40)) + ", Sample Logistics Park, Ring Road";
    return new Seller(name.name(), street, FakeIds.pinCode(random, state), state, pan, taxId);
  }

  private static Buyer buyer(Random random, GstState state) {
    String first = FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size()));
    String last = LAST_NAMES.get(random.nextInt(LAST_NAMES.size()));
    String street =
        "Flat " + (101 + random.nextInt(800)) + ", Example Residency, Test Nagar Main Road";
    String email = (first + "." + last).toLowerCase(Locale.ROOT) + "@example.com";
    return new Buyer(
        first + " " + last,
        street,
        FakeIds.pinCode(random, state),
        state,
        FakeIds.mobile(random),
        email);
  }

  private static String documentNumber(Random random, String prefix, GstState state) {
    return prefix + "-" + state.code() + "-" + FakeIds.digits(random, 7);
  }

  private static List<LineItem> lineItems(
      Random random, int count, boolean preGst, TaxType taxType) {
    List<Product> pool = new ArrayList<>(PRODUCTS);
    List<LineItem> lines = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Product product = pool.remove(random.nextInt(pool.size()));
      int quantity = 1 + random.nextInt(3);
      BigDecimal unitPrice = BigDecimal.valueOf(9_900 + random.nextInt(490_000), 2);
      BigDecimal net = unitPrice.multiply(BigDecimal.valueOf(quantity));
      BigDecimal rate =
          preGst ? new BigDecimal(product.vatRate()) : BigDecimal.valueOf(product.gstRate());
      BigDecimal tax = taxAmount(net, rate, taxType);
      lines.add(
          new LineItem(
              product.description(),
              product.hsnCode(),
              quantity,
              unitPrice,
              net,
              rate,
              tax,
              net.add(tax)));
    }
    return lines;
  }

  // CGST and SGST are each rounded on half the rate and printed separately, so the line tax is
  // twice the rounded half; that keeps the printed components summing to the printed total.
  private static BigDecimal taxAmount(BigDecimal net, BigDecimal rate, TaxType taxType) {
    if (taxType == TaxType.CGST_SGST) {
      return half(net, rate).multiply(BigDecimal.TWO);
    }
    return net.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal half(BigDecimal net, BigDecimal rate) {
    return net.multiply(rate).divide(BigDecimal.valueOf(200), 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    return values.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
  }

  // Fixed document ID and dates keep the PDF bytes stable for a given seed, which dedupe tests
  // (ING-2) rely on.
  private static void fixMetadata(PDDocument document, SyntheticInvoice invoice) {
    document.setDocumentId((long) invoice.invoiceNumber().hashCode());
    GregorianCalendar created = new GregorianCalendar(TimeZone.getTimeZone(ZoneOffset.UTC));
    created.clear();
    LocalDate date = invoice.invoiceDate();
    created.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
    PDDocumentInformation info = document.getDocumentInformation();
    info.setTitle(title(invoice.type()));
    info.setProducer("invoice-intel synthetic fixture");
    info.setCreationDate(created);
    info.setModificationDate(created);
  }

  static String title(InvoiceType type) {
    return switch (type) {
      case GST_TAX_INVOICE -> "Tax Invoice";
      case CREDIT_NOTE -> "Credit Note";
      case PRE_GST_INVOICE -> "Retail Invoice";
    };
  }

  /** Draws the invoice top to bottom on one A4 page. */
  private static final class Layout {

    private static final PDFont REGULAR = new PDType1Font(FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(FontName.HELVETICA_BOLD);
    private static final float LEFT = 40;
    private static final float RIGHT_COLUMN = 320;
    private static final float[] TABLE_X = {40, 58, 212, 248, 300, 322, 374, 416, 470, 522};

    private final PDPageContentStream content;
    private float y = 800;

    Layout(PDPageContentStream content) {
      this.content = content;
    }

    void draw(SyntheticInvoice invoice) throws IOException {
      boolean preGst = invoice.type() == InvoiceType.PRE_GST_INVOICE;
      text(LEFT, y, BOLD, 16, title(invoice.type()));
      text(RIGHT_COLUMN, y, REGULAR, 9, "(Original for Recipient)");
      y -= 30;

      Seller seller = invoice.seller();
      float top = y;
      line(BOLD, "Sold By:");
      line(REGULAR, seller.name());
      line(REGULAR, seller.street());
      line(REGULAR, seller.state().city() + ", " + seller.state().displayName());
      line(REGULAR, seller.pinCode());
      line(REGULAR, "PAN No: " + seller.pan());
      line(REGULAR, (preGst ? "VAT/TIN No: " : "GST Registration No: ") + seller.taxId());
      float sellerBottom = y;

      Buyer buyer = invoice.buyer();
      y = top;
      lineAt(RIGHT_COLUMN, BOLD, "Billing Address:");
      lineAt(RIGHT_COLUMN, REGULAR, buyer.name());
      lineAt(RIGHT_COLUMN, REGULAR, buyer.street());
      lineAt(RIGHT_COLUMN, REGULAR, buyer.state().city() + ", " + buyer.state().displayName());
      lineAt(RIGHT_COLUMN, REGULAR, buyer.pinCode());
      lineAt(RIGHT_COLUMN, REGULAR, "Phone: " + buyer.phone());
      lineAt(RIGHT_COLUMN, REGULAR, "Email: " + buyer.email());
      lineAt(RIGHT_COLUMN, REGULAR, "State/UT Code: " + buyer.state().code());
      y = Math.min(y, sellerBottom) - 16;

      line(REGULAR, "Order Number: " + invoice.orderNumber());
      line(REGULAR, "Order Date: " + invoice.orderDate());
      String numberLabel =
          invoice.type() == InvoiceType.CREDIT_NOTE ? "Credit Note Number: " : "Invoice Number: ";
      line(REGULAR, numberLabel + invoice.invoiceNumber());
      line(REGULAR, "Invoice Date: " + invoice.invoiceDate());
      invoice
          .creditedInvoiceNumber()
          .ifPresent(number -> line(REGULAR, "Original Invoice Number: " + number));
      line(REGULAR, "Place of Supply: " + invoice.buyer().state().displayName());
      y -= 16;

      lineItems(invoice);
      y -= 8;
      text(TABLE_X[6], y, BOLD, 9, "TOTAL:");
      text(TABLE_X[8], y, BOLD, 9, invoice.taxTotal().toPlainString());
      text(TABLE_X[9], y, BOLD, 9, invoice.grandTotal().toPlainString());
      y -= 20;
      line(BOLD, "Amount in Words:");
      line(REGULAR, invoice.amountInWords());
      y -= 20;
      lineAt(RIGHT_COLUMN, REGULAR, "For " + seller.name() + ":");
      lineAt(RIGHT_COLUMN, REGULAR, "Authorized Signatory");
    }

    private void lineItems(SyntheticInvoice invoice) throws IOException {
      row(
          BOLD,
          "Sl.",
          "Description",
          "HSN",
          "Unit Price",
          "Qty",
          "Net Amt",
          "Tax Rate",
          "Tax Type",
          "Tax Amt",
          "Total");
      int sl = 1;
      for (LineItem item : invoice.lineItems()) {
        String rate = item.taxRate().stripTrailingZeros().toPlainString() + "%";
        if (invoice.taxType() == TaxType.CGST_SGST) {
          BigDecimal halfRate = item.taxRate().divide(BigDecimal.TWO);
          String halfRateText = halfRate.stripTrailingZeros().toPlainString() + "%";
          String halfTax = half(item.netAmount(), item.taxRate()).toPlainString();
          row(
              REGULAR,
              String.valueOf(sl),
              item.description(),
              item.hsnCode(),
              item.unitPrice().toPlainString(),
              String.valueOf(item.quantity()),
              item.netAmount().toPlainString(),
              halfRateText,
              "CGST",
              halfTax,
              item.totalAmount().toPlainString());
          row(REGULAR, "", "", "", "", "", "", halfRateText, "SGST", halfTax, "");
        } else {
          row(
              REGULAR,
              String.valueOf(sl),
              item.description(),
              item.hsnCode(),
              item.unitPrice().toPlainString(),
              String.valueOf(item.quantity()),
              item.netAmount().toPlainString(),
              rate,
              invoice.taxType().name(),
              item.taxAmount().toPlainString(),
              item.totalAmount().toPlainString());
        }
        sl++;
      }
    }

    private void row(PDFont font, String... cells) throws IOException {
      for (int i = 0; i < cells.length; i++) {
        if (!cells[i].isEmpty()) {
          text(TABLE_X[i], y, font, 7.5f, cells[i]);
        }
      }
      y -= 13;
    }

    private void line(PDFont font, String value) {
      lineAt(LEFT, font, value);
    }

    private void lineAt(float x, PDFont font, String value) {
      try {
        text(x, y, font, 9, value);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      y -= 12;
    }

    private void text(float x, float atY, PDFont font, float size, String value)
        throws IOException {
      content.beginText();
      content.setFont(font, size);
      content.newLineAtOffset(x, atY);
      content.showText(value);
      content.endText();
    }
  }
}
