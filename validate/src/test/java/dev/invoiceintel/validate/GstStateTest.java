package dev.invoiceintel.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GstStateTest {

  /** The GST state-code list, written out independently of the enum. */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          01 | Jammu and Kashmir
          02 | Himachal Pradesh
          03 | Punjab
          04 | Chandigarh
          05 | Uttarakhand
          06 | Haryana
          07 | Delhi
          08 | Rajasthan
          09 | Uttar Pradesh
          10 | Bihar
          11 | Sikkim
          12 | Arunachal Pradesh
          13 | Nagaland
          14 | Manipur
          15 | Mizoram
          16 | Tripura
          17 | Meghalaya
          18 | Assam
          19 | West Bengal
          20 | Jharkhand
          21 | Odisha
          22 | Chhattisgarh
          23 | Madhya Pradesh
          24 | Gujarat
          26 | Dadra and Nagar Haveli and Daman and Diu
          27 | Maharashtra
          29 | Karnataka
          30 | Goa
          31 | Lakshadweep
          32 | Kerala
          33 | Tamil Nadu
          34 | Puducherry
          35 | Andaman and Nicobar Islands
          36 | Telangana
          37 | Andhra Pradesh
          38 | Ladakh
          97 | Other Territory
          """)
  void byCode_everyStateAndUt_matchesItsName(String code, String name) {
    GstState state = GstState.byCode(code).orElseThrow();

    assertThat(state.code()).isEqualTo(code);
    assertThat(state.displayName()).isEqualTo(name);
    assertThat(GstState.byName(name)).contains(state);
  }

  @Test
  void values_cover36StatesAndUtsPlusOtherTerritory() {
    assertThat(GstState.values()).hasSize(37);
    assertThat(Arrays.stream(GstState.values()).map(GstState::code)).doesNotHaveDuplicates();
  }

  @ParameterizedTest
  @CsvSource({"25, DADRA_NAGAR_HAVELI_DAMAN_DIU", "28, ANDHRA_PRADESH", "' 27 ', MAHARASHTRA"})
  void byCode_legacyOrPaddedCode_mapsToCurrentState(String code, GstState expected) {
    assertThat(GstState.byCode(code)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"00", "39", "96", "99", "7", "abc"})
  void byCode_unknownCode_isEmpty(String code) {
    assertThat(GstState.byCode(code)).isEmpty();
  }

  @Test
  void byCode_null_isEmpty() {
    assertThat(GstState.byCode(null)).isEmpty();
    assertThat(GstState.byName(null)).isEmpty();
    assertThat(GstState.findIn(null)).isEmpty();
    assertThat(GstState.resolve(null)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Orissa                               | ODISHA
          Pondicherry                          | PUDUCHERRY
          Uttaranchal                          | UTTARAKHAND
          Chattisgarh                          | CHHATTISGARH
          TAMILNADU                            | TAMIL_NADU
          Dadra & Nagar Haveli                 | DADRA_NAGAR_HAVELI_DAMAN_DIU
          Daman and Diu                        | DADRA_NAGAR_HAVELI_DAMAN_DIU
          dadra-and-nagar-haveli-and-daman-and-diu | DADRA_NAGAR_HAVELI_DAMAN_DIU
          Jammu & Kashmir                      | JAMMU_AND_KASHMIR
          '  west   BENGAL. '                  | WEST_BENGAL
          Andaman & Nicobar                    | ANDAMAN_AND_NICOBAR_ISLANDS
          National Capital Territory of Delhi  | DELHI
          """)
  void byName_aliasesAndSpelling_areTolerated(String name, GstState expected) {
    assertThat(GstState.byName(name)).contains(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"Bengal", "Pradesh", "New Delhi"})
  void byName_partialName_isEmpty(String name) {
    assertThat(GstState.byName(name)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void findIn_addressNamingTheState_findsIt(GstState state) {
    String address =
        "Unit 4, Sample Industrial Estate, Sample City, " + state.displayName() + " 123456";

    assertThat(GstState.findIn(address)).contains(state);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Plot 1, Delhi Road, Meerut, Uttar Pradesh 250001       | UTTAR_PRADESH
          Okhla Phase 2, New Delhi 110020                        | DELHI
          Silvassa, Dadra and Nagar Haveli and Daman and Diu     | DADRA_NAGAR_HAVELI_DAMAN_DIU
          Goalpara Road, Guwahati, ASSAM                         | ASSAM
          """)
  void findIn_address_takesLastWholeWordState(String address, GstState expected) {
    assertThat(GstState.findIn(address)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Goalpara Road", "ADDRESS_ABCDEFGH"})
  void findIn_noStateName_isEmpty(String text) {
    assertThat(GstState.findIn(text)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Maharashtra         | MAHARASHTRA
          27                  | MAHARASHTRA
          27-Maharashtra      | MAHARASHTRA
          MAHARASHTRA (27)    | MAHARASHTRA
          25                  | DADRA_NAGAR_HAVELI_DAMAN_DIU
          Daman and Diu (25)  | DADRA_NAGAR_HAVELI_DAMAN_DIU
          """)
  void resolve_nameCodeOrBoth_findsState(String value, GstState expected) {
    assertThat(GstState.resolve(value)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Maharashtra (29)", "99", "Somewhere", ""})
  void resolve_conflictingOrUnknown_isEmpty(String value) {
    assertThat(GstState.resolve(value)).isEmpty();
  }
}
