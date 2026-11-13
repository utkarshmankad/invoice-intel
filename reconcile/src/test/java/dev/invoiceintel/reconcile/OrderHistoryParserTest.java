package dev.invoiceintel.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.reconcile.OrderHistoryImportException.Code;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Synthetic order-history files only (testing.md); order numbers are made up. */
class OrderHistoryParserTest {

  private static final String HEADER =
      "Order ID,Order Date,Product Name,ASIN,Original Quantity,Unit Price,Total Amount,Website\n";

  private final OrderHistoryParser parser =
      new OrderHistoryParser(n -> "ORDER_NUMBER_" + n.hashCode());

  static OrderHistoryMappings mappings() {
    var columns = new LinkedHashMap<String, OrderHistoryField>();
    columns.put("Order ID", OrderHistoryField.ORDER_TOKEN);
    columns.put("Order Date", OrderHistoryField.ORDER_DATE);
    columns.put("Product Name", OrderHistoryField.PRODUCT_NAME);
    columns.put("ASIN", OrderHistoryField.ASIN);
    columns.put("Original Quantity", OrderHistoryField.QUANTITY);
    columns.put("Unit Price", OrderHistoryField.UNIT_PRICE);
    columns.put("Total Amount", OrderHistoryField.ITEM_TOTAL);
    columns.put("Website", null);
    return new OrderHistoryMappings(
        List.of(new OrderHistoryMapping("retail.csv", "RETAIL", columns)));
  }

  @Test
  void parse_validRows_mapsTokenizesAndConvertsEveryField() {
    var csv =
        HEADER
            + "999-0000001-0000001,2024-03-05T10:00:00Z,\"Notebook, A5\",B000TEST01,2,\"1,250.00\",2500,Amazon.in\n";
    var parsed = parser.parse(csv, mappings());

    assertThat(parsed.mapping().kind()).isEqualTo("RETAIL");
    assertThat(parsed.skipped()).isEmpty();
    assertThat(parsed.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.orderToken())
                  .isEqualTo("ORDER_NUMBER_" + "999-0000001-0000001".hashCode());
              assertThat(item.orderDate()).isEqualTo(LocalDate.of(2024, 3, 5));
              assertThat(item.productName()).isEqualTo("Notebook, A5");
              assertThat(item.asin()).isEqualTo("B000TEST01");
              assertThat(item.quantity()).isEqualTo(2);
              assertThat(item.unitPrice()).isEqualByComparingTo("1250.00");
              assertThat(item.itemTotal()).isEqualByComparingTo(new BigDecimal("2500.00"));
            });
  }

  @Test
  void parse_rowsWithBlankOptionalFields_keepNulls() {
    var parsed = parser.parse(HEADER + "999-1,Not Available,,,,,,\n", mappings());
    assertThat(parsed.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.orderDate()).isNull();
              assertThat(item.productName()).isNull();
              assertThat(item.quantity()).isNull();
              assertThat(item.itemTotal()).isNull();
            });
  }

  @Test
  void parse_badRows_areSkippedAndCountedByReason() {
    var csv =
        HEADER
            + ",2024-03-05,P,A,1,1,1,W\n"
            + "999-2,05/03/2024,P,A,1,1,1,W\n"
            + "999-3,2024-03-05,P,A,one,1,1,W\n"
            + "999-4,2024-03-05,P,A,1,abc,1,W\n"
            + "999-5,2024-03-05,P,A,1,1,1.2.3,W\n"
            + "999-6,2024-03-05,P\n"
            + "999-7,2024-03-05,P,A,1,1,1,W\n";
    var parsed = parser.parse(csv, mappings());

    assertThat(parsed.items()).hasSize(1);
    assertThat(parsed.skipped())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                SkipReason.MISSING_ORDER_ID, 1,
                SkipReason.BAD_DATE, 1,
                SkipReason.BAD_QUANTITY, 1,
                SkipReason.BAD_AMOUNT, 2,
                SkipReason.WRONG_FIELD_COUNT, 1));
    assertThat(parsed.skippedCount()).isEqualTo(6);
  }

  @Test
  void parse_headerWithSpacesAndOtherOrder_stillMatches() {
    var csv =
        " Website ,Total Amount,Unit Price,Original Quantity,ASIN,Product Name,Order Date,Order ID\nW,10,5,2,A,P,2024-01-01,999-8\n";
    assertThat(parser.parse(csv, mappings()).items())
        .singleElement()
        .satisfies(item -> assertThat(item.itemTotal()).isEqualByComparingTo("10"));
  }

  @Test
  void parse_unknownHeader_isRejected() {
    assertThatThrownBy(() -> parser.parse("Order ID,Other\n1,2\n", mappings()))
        .isInstanceOfSatisfying(
            OrderHistoryImportException.class,
            e -> assertThat(e.code()).isEqualTo(Code.UNKNOWN_COLUMNS));
  }

  @Test
  void parse_emptyFile_isRejected() {
    assertThatThrownBy(() -> parser.parse("", mappings()))
        .isInstanceOfSatisfying(
            OrderHistoryImportException.class,
            e -> assertThat(e.code()).isEqualTo(Code.EMPTY_FILE));
  }

  @Test
  void parse_unclosedQuote_isRejectedWithoutContentInTheMessage() {
    assertThatThrownBy(() -> parser.parse(HEADER + "\"999-9,secret", mappings()))
        .isInstanceOfSatisfying(
            OrderHistoryImportException.class,
            e -> {
              assertThat(e.code()).isEqualTo(Code.MALFORMED_CSV);
              assertThat(e.getMessage()).doesNotContain("999-9").doesNotContain("secret");
            });
  }

  @Test
  void item_toString_hidesProductName() {
    var item =
        new OrderHistoryItem("ORDER_NUMBER_X", null, "Secret product", null, null, null, null);
    assertThat(item.toString()).doesNotContain("Secret");
  }
}
