package dev.invoiceintel.validate;

import static dev.invoiceintel.validate.GstExtractions.builder;
import static dev.invoiceintel.validate.GstExtractions.gstin;
import static org.assertj.core.api.Assertions.assertThat;

import dev.invoiceintel.common.DocumentClass;
import dev.invoiceintel.common.DocumentId;
import dev.invoiceintel.common.extraction.InvoiceExtraction;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class GstinStateRuleTest {

  private static final ValidationContext CONTEXT =
      new ValidationContext(DocumentId.random(), DocumentClass.TAX_INVOICE);
  private static final String ADDRESS = "Unit 4, Sample Estate, Sample City, %s 123456";

  private final GstinStateRule rule = new GstinStateRule();

  private RuleResult check(InvoiceExtraction extraction) {
    return rule.check(extraction, CONTEXT);
  }

  @Test
  void id_isVal4() {
    assertThat(rule.id()).isEqualTo("VAL-4");
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void check_validGstinMatchingSellerState_passes(GstState state) {
    var extraction =
        builder()
            .sellerGstin(gstin(state.code()))
            .sellerAddress(ADDRESS.formatted(state.displayName()))
            .build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void check_gstinStateDiffersFromSellerState_fails(GstState state) {
    GstState other = state == GstState.KARNATAKA ? GstState.KERALA : GstState.KARNATAKA;
    var extraction =
        builder()
            .sellerGstin(gstin(state.code()))
            .sellerAddress(ADDRESS.formatted(other.displayName()))
            .build();

    RuleResult result = check(extraction);

    assertThat(result.outcome()).isEqualTo(RuleOutcome.FAIL);
    assertThat(result.message())
        .isEqualTo(
            "sellerGstin state code "
                + state.code()
                + " does not match seller state code "
                + other.code());
  }

  @ParameterizedTest
  @CsvSource({
    "25, Daman and Diu",
    "25, Dadra and Nagar Haveli and Daman and Diu",
    "28, Andhra Pradesh"
  })
  void check_legacyStateCode_matchesCurrentState(String code, String state) {
    var extraction =
        builder().sellerGstin(gstin(code)).sellerAddress(ADDRESS.formatted(state)).build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_gstinWithSpacesAndLowerCase_isNormalized() {
    String spaced = gstin("29").toLowerCase().replaceAll("(.{5})", "$1 ");
    var extraction =
        builder().sellerGstin(spaced).sellerAddress(ADDRESS.formatted("Karnataka")).build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_badCheckCharacter_fails() {
    String valid = gstin("27");
    char wrong = valid.charAt(14) == '0' ? '1' : '0';
    var extraction =
        builder()
            .sellerGstin(valid.substring(0, 14) + wrong)
            .sellerAddress(ADDRESS.formatted("Maharashtra"))
            .build();

    assertThat(check(extraction))
        .isEqualTo(RuleResult.fail("sellerGstin fails the GSTIN format or check character"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"27ABCDE1234F1Z", "NOT A GSTIN", "2712345678901Z5"})
  void check_badLayout_fails(String value) {
    var extraction =
        builder().sellerGstin(value).sellerAddress(ADDRESS.formatted("Maharashtra")).build();

    assertThat(check(extraction).outcome()).isEqualTo(RuleOutcome.FAIL);
  }

  @ParameterizedTest
  @ValueSource(strings = {"00", "39", "99"})
  void check_unknownStateCodeWithValidChecksum_fails(String code) {
    var extraction =
        builder().sellerGstin(gstin(code)).sellerAddress(ADDRESS.formatted("Maharashtra")).build();

    assertThat(check(extraction))
        .isEqualTo(RuleResult.fail("sellerGstin state code " + code + " is not a GST state code"));
  }

  @Test
  void check_pseudonymToken_warnsWithoutQuotingIt() {
    var extraction =
        builder()
            .sellerGstin("GSTIN_ABCDEFGH")
            .sellerAddress(ADDRESS.formatted("Maharashtra"))
            .build();

    RuleResult result = check(extraction);

    assertThat(result.outcome()).isEqualTo(RuleOutcome.WARN);
    assertThat(result.message()).doesNotContain("GSTIN_ABCDEFGH");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADDRESS_ABCDEFGH", "Unit 4, Sample Estate, Sample City"})
  void check_noStateInSellerAddress_warns(String address) {
    var extraction = builder().sellerGstin(gstin("27")).sellerAddress(address).build();

    assertThat(check(extraction))
        .isEqualTo(
            RuleResult.warn(
                "seller state not found in sellerAddress; sellerGstin state code 27 not"
                    + " cross-checked"));
  }

  @Test
  void check_noSellerAddress_warns() {
    var extraction = builder().sellerGstin(gstin("27")).build();

    assertThat(check(extraction).outcome()).isEqualTo(RuleOutcome.WARN);
  }

  @Test
  void check_noGstinBeforeGst_passes() {
    var extraction = builder().invoiceDate(LocalDate.of(2017, 6, 30)).build();

    assertThat(check(extraction)).isEqualTo(RuleResult.pass());
  }

  @Test
  void check_noGstinOnGstStartDate_warns() {
    var extraction = builder().invoiceDate(LocalDate.of(2017, 7, 1)).build();

    assertThat(check(extraction))
        .isEqualTo(RuleResult.warn("sellerGstin is missing on a GST-era invoice"));
  }
}
