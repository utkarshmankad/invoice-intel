package dev.invoiceintel.validate;

import static dev.invoiceintel.common.extraction.TaxType.CESS;
import static dev.invoiceintel.common.extraction.TaxType.CGST;
import static dev.invoiceintel.common.extraction.TaxType.CST;
import static dev.invoiceintel.common.extraction.TaxType.IGST;
import static dev.invoiceintel.common.extraction.TaxType.SGST;
import static dev.invoiceintel.common.extraction.TaxType.UTGST;
import static dev.invoiceintel.common.extraction.TaxType.VAT;
import static dev.invoiceintel.validate.GstExtractions.builder;
import static dev.invoiceintel.validate.GstExtractions.gstin;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import dev.invoiceintel.common.extraction.TaxType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TaxSplitRuleTest {

  private static final ValidationContext CONTEXT =
      new ValidationContext(DocumentId.random(), DocumentClass.TAX_INVOICE);

  private final TaxSplitRule rule = new TaxSplitRule();

  private RuleResult check(InvoiceExtraction extraction) {
    return rule.check(extraction, CONTEXT);
  }

  private static GstState other(GstState state) {
    return state == GstState.KARNATAKA ? GstState.KERALA : GstState.KARNATAKA;
  }

  @Test
  void id_isVal5() {
    assertThat(rule.id()).isEqualTo("VAL-5");
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void check_intraStateWithCgstSgst_passes(GstState state) {
    var extraction =
        builder()
            .sellerGstin(gstin(state.code()))
            .placeOfSupply(state.displayName())
            .lines(List.of(CGST, SGST))
            .build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void check_intraStateWithUtgst_passes(GstState state) {
    var extraction =
        builder()
            .sellerGstin(gstin(state.code()))
            .placeOfSupply(state.code())
            .lines(List.of(CGST, UTGST, CESS))
            .build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void check_intraStateWithIgst_fails(GstState state) {
    var extraction =
        builder()
            .sellerGstin(gstin(state.code()))
            .placeOfSupply(state.displayName())
            .lines(List.of(IGST))
            .build();

    assertThat(check(extraction))
        .isEqualTo(
            RuleResult.fail(
                "line 1: intra-state supply needs CGST and SGST/UTGST (seller state "
                    + state.code()
                    + ", place of supply "
                    + state.code()
                    + "), found [IGST]"));
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void check_interStateWithIgst_passes(GstState state) {
    var extraction =
        builder()
            .sellerGstin(gstin(state.code()))
            .placeOfSupply(other(state).displayName())
            .lines(List.of(IGST), List.of(IGST, CESS))
            .build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void check_interStateWithCgstSgst_fails(GstState state) {
    var extraction =
        builder()
            .sellerGstin(gstin(state.code()))
            .placeOfSupply(other(state).displayName())
            .lines(List.of(CGST, SGST))
            .build();

    assertThat(check(extraction))
        .isEqualTo(
            RuleResult.fail(
                "line 1: inter-state supply needs IGST only (seller state "
                    + state.code()
                    + ", place of supply "
                    + other(state).code()
                    + "), found [CGST, SGST]"));
  }

  static Stream<Arguments> badIntraSplits() {
    return Stream.of(
        Arguments.of(List.of(CGST)),
        Arguments.of(List.of(SGST)),
        Arguments.of(List.of(UTGST)),
        Arguments.of(List.of(CGST, SGST, IGST)));
  }

  @ParameterizedTest
  @MethodSource("badIntraSplits")
  void check_intraStateIncompleteSplit_fails(List<TaxType> types) {
    var extraction =
        builder().sellerGstin(gstin("27")).placeOfSupply("Maharashtra").lines(types).build();

    assertThat(check(extraction).outcome()).isEqualTo(RuleOutcome.FAIL);
  }

  @Test
  void check_interStateMixedIgstAndCgst_fails() {
    var extraction =
        builder()
            .sellerGstin(gstin("27"))
            .placeOfSupply("Karnataka")
            .lines(List.of(IGST, CGST))
            .build();

    assertThat(check(extraction).outcome()).isEqualTo(RuleOutcome.FAIL);
  }

  @Test
  void check_secondLineWrong_namesThatLine() {
    var extraction =
        builder()
            .sellerGstin(gstin("27"))
            .placeOfSupply("Maharashtra")
            .lines(List.of(CGST, SGST), List.of(), List.of(IGST))
            .build();

    assertThat(check(extraction).message()).startsWith("line 3: ");
  }

  @Test
  void check_sellerStateFromAddressWhenNoGstin_isUsed() {
    var extraction =
        builder()
            .sellerAddress("Unit 4, Sample City, Tamil Nadu 600001")
            .placeOfSupply("Kerala")
            .lines(List.of(CGST, SGST))
            .build();

    assertThat(check(extraction).outcome()).isEqualTo(RuleOutcome.FAIL);
  }

  @Test
  void check_invalidGstinFallsBackToAddress() {
    var extraction =
        builder()
            .sellerGstin("GSTIN_ABCDEFGH")
            .sellerAddress("Unit 4, Sample City, Kerala 682001")
            .placeOfSupply("Kerala")
            .lines(List.of(CGST, SGST))
            .build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_validGstinWithUnknownCodeFallsBackToAddress() {
    var extraction =
        builder()
            .sellerGstin(gstin("99"))
            .sellerAddress("Unit 4, Sample City, Kerala 682001")
            .placeOfSupply("Kerala")
            .lines(List.of(CGST, SGST))
            .build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_legacyCodeAndCurrentName_areSameState() {
    var extraction =
        builder()
            .sellerGstin(gstin("25"))
            .placeOfSupply("Dadra and Nagar Haveli and Daman and Diu")
            .lines(List.of(CGST, UTGST))
            .build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_noGstTaxes_passes() {
    var extraction = builder().lines(List.of(), List.of(VAT), List.of(CST, CESS)).build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_noSellerState_warns() {
    var extraction = builder().placeOfSupply("Maharashtra").build();

    assertThat(check(extraction))
        .isEqualTo(
            RuleResult.warn(
                "seller state unknown (sellerGstin, sellerAddress); split not checked"));
  }

  @Test
  void check_noPlaceOfSupply_warns() {
    var extraction = builder().sellerGstin(gstin("27")).build();

    assertThat(check(extraction))
        .isEqualTo(RuleResult.warn("placeOfSupply is missing; split not checked"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Somewhere", "Maharashtra (29)", "99"})
  void check_unknownPlaceOfSupply_warns(String placeOfSupply) {
    var extraction = builder().sellerGstin(gstin("27")).placeOfSupply(placeOfSupply).build();

    assertThat(check(extraction))
        .isEqualTo(RuleResult.warn("placeOfSupply is not a known state; split not checked"));
  }
}
