package dev.invoiceintel.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderHistoryMappingTest {

  static OrderHistoryMapping retail() {
    var columns = new LinkedHashMap<String, OrderHistoryField>();
    columns.put("Order ID", OrderHistoryField.ORDER_TOKEN);
    columns.put("Order Date", OrderHistoryField.ORDER_DATE);
    columns.put("Website", null);
    return new OrderHistoryMapping("retail.csv", "RETAIL", columns);
  }

  @Test
  void matches_sameNamesInAnyOrder_isTrue() {
    assertThat(retail().matches(List.of("Website", "Order ID", "Order Date"))).isTrue();
  }

  @Test
  void matches_missingExtraOrRepeatedName_isFalse() {
    var mapping = retail();
    assertThat(mapping.matches(List.of("Order ID", "Order Date"))).isFalse();
    assertThat(mapping.matches(List.of("Order ID", "Order Date", "Website", "Extra"))).isFalse();
    assertThat(mapping.matches(List.of("Order ID", "Order Date", "Website", "Website"))).isFalse();
  }

  @Test
  void fields_listsImportedColumnsOnly() {
    assertThat(retail().fields())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                OrderHistoryField.ORDER_TOKEN,
                "Order ID",
                OrderHistoryField.ORDER_DATE,
                "Order Date"));
  }

  @Test
  void new_withoutOrderIdColumn_isRejected() {
    var columns = new LinkedHashMap<String, OrderHistoryField>();
    columns.put("Order Date", OrderHistoryField.ORDER_DATE);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new OrderHistoryMapping("f.csv", "RETAIL", columns))
        .withMessageContaining("order_token");
  }

  @Test
  void new_withTwoColumnsForOneField_isRejected() {
    var columns = new LinkedHashMap<String, OrderHistoryField>();
    columns.put("Order ID", OrderHistoryField.ORDER_TOKEN);
    columns.put("Order Number", OrderHistoryField.ORDER_TOKEN);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new OrderHistoryMapping("f.csv", "RETAIL", columns));
  }

  @Test
  void forHeader_picksTheMatchingMapping() {
    var other = new LinkedHashMap<String, OrderHistoryField>();
    other.put("Order ID", OrderHistoryField.ORDER_TOKEN);
    var mappings =
        new OrderHistoryMappings(
            List.of(new OrderHistoryMapping("digital.csv", "DIGITAL", other), retail()));
    assertThat(mappings.forHeader(List.of("Order ID", "Order Date", "Website")))
        .hasValueSatisfying(m -> assertThat(m.kind()).isEqualTo("RETAIL"));
    assertThat(mappings.forHeader(List.of("Order ID")))
        .hasValueSatisfying(m -> assertThat(m.kind()).isEqualTo("DIGITAL"));
    assertThat(mappings.forHeader(List.of("Nope"))).isEmpty();
  }

  @Test
  void fieldFromTarget_knowsEveryTarget() {
    for (var field : OrderHistoryField.values()) {
      assertThat(OrderHistoryField.fromTarget(field.target())).contains(field);
    }
    assertThat(OrderHistoryField.fromTarget("shipping_address")).isEmpty();
  }
}
