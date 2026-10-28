package dev.invoiceintel.common.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.invoiceintel.common.checksum.Verhoeff;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class FakeIdsTest {

  private static final String PAN_FORMAT = "[A-Z]{3}[PCF][A-Z][0-9]{4}[A-Z]";

  @ParameterizedTest
  @CsvSource({
    // Widely published GSTIN format example; its check character is V.
    "27AAPFU0939F1Z, V",
    "29ABCDE1234F1Z, W",
    "07ZZZZZ9999Z9Z, 2"
  })
  void gstinCheckChar_knownBodies_matchesReference(String body, char expected) {
    assertThat(FakeIds.gstinCheckChar(body)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"27AAPFU0939F1", "27aapfu0939f1z", "27AAPFU0939F1-"})
  void gstinCheckChar_malformedBody_isRejected(String body) {
    assertThatIllegalArgumentException().isThrownBy(() -> FakeIds.gstinCheckChar(body));
  }

  @ParameterizedTest
  @EnumSource(GstState.class)
  void gstin_anyState_hasValidFormatAndCheckChar(GstState state) {
    String pan = FakeIds.pan(new Random(state.ordinal()), 'C', 'S');

    String gstin = FakeIds.gstin(state, pan, '1');

    assertThat(gstin).hasSize(15).startsWith(state.code() + pan).contains("1Z");
    assertThat(gstin.charAt(14)).isEqualTo(FakeIds.gstinCheckChar(gstin.substring(0, 14)));
  }

  @Test
  void gstin_changedCharacter_failsCheck() {
    String gstin = FakeIds.gstin(GstState.KARNATAKA, FakeIds.pan(new Random(1), 'C', 'D'), '1');
    String tampered = gstin.substring(0, 2) + (gstin.charAt(2) == 'A' ? 'B' : 'A');

    assertThat(FakeIds.gstinCheckChar(tampered + gstin.substring(3, 14)))
        .isNotEqualTo(gstin.charAt(14));
  }

  @Test
  void aadhaar_generated_hasTwelveDigitsAndValidVerhoeffCheck() {
    var random = new Random(5);
    for (int i = 0; i < 50; i++) {
      String aadhaar = FakeIds.aadhaar(random);

      assertThat(aadhaar).matches("[2-9][0-9]{11}");
      assertThat(Verhoeff.isValid(aadhaar)).isTrue();
    }
  }

  @Test
  void pan_entityAndInitial_areInPositionsFourAndFive() {
    String pan = FakeIds.pan(new Random(7), 'P', 'm');

    assertThat(pan).matches(PAN_FORMAT);
    assertThat(pan.charAt(3)).isEqualTo('P');
    assertThat(pan.charAt(4)).isEqualTo('M');
  }

  @Test
  void otherIds_sameSeed_haveExpectedFormats() {
    Random random = new Random(42);

    assertThat(FakeIds.tin(random, GstState.GUJARAT)).matches("24[0-9]{9}");
    assertThat(FakeIds.mobile(random)).matches("\\+91 [6-9][0-9]{9}");
    assertThat(FakeIds.pinCode(random, GstState.MAHARASHTRA)).matches("4[0-9]{5}");
    assertThat(FakeIds.orderNumber(random)).matches("[0-9]{3}-[0-9]{7}-[0-9]{7}");
  }
}
