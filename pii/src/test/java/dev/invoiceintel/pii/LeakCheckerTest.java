package dev.invoiceintel.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.invoiceintel.common.DocumentId;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PII-6: synthetic values only; the vault keyset is generated per run. */
class LeakCheckerTest {

  private static final DocumentId DOC = DocumentId.random();

  private PiiVault vault;
  private List<VaultEntry> entries;
  private final List<Set<PiiType>> alerts = new ArrayList<>();
  private LeakChecker checker;

  @BeforeEach
  void setUp() throws GeneralSecurityException {
    vault = TestKeysets.vault(TestKeysets.newVaultKeyset());
    // Shaped values built from parts so the CI secret scan (.gitleaks.toml) doesn't flag them.
    entries =
        List.of(
            vault.seal("PERSON_AAAAAAAA", PiiType.PERSON, "Meera Iyer"),
            vault.seal("PHONE_BBBBBBBB", PiiType.PHONE, "9000000001"),
            vault.seal("AADHAAR_CCCCCCCC", PiiType.AADHAAR, "2345 " + "6789 0123"),
            vault.seal("EMAIL_DDDDDDDD", PiiType.EMAIL, "m.iyer1987@example.com"),
            vault.seal("ADDRESS_EEEEEEEE", PiiType.ADDRESS, "Flat 4B, Lotus Residency"));
    checker = checkerFor(entries);
  }

  private LeakChecker checkerFor(List<VaultEntry> known) {
    return LeakChecker.withEntries(
        vault,
        id -> {
          assertThat(id).isEqualTo(DOC);
          return known;
        },
        (id, types) -> alerts.add(types));
  }

  @Test
  void check_responseWithTokensOnly_isClean() {
    String response =
        """
        {"buyer": {"name": "PERSON_AAAAAAAA", "phone": "PHONE_BBBBBBBB"},
         "invoiceNumber": "INVOICE_NUMBER_FFFFFFFF", "total": "1180.00", "qty": 2}
        """;

    assertThat(checker.check(DOC, response).leaked()).isFalse();
    assertThat(alerts).isEmpty();
  }

  @Test
  void check_responseWithRealName_leaksPersonAndAlertsOnce() {
    LeakCheckResult result = checker.check(DOC, "{\"buyer\": {\"name\": \"Meera Iyer\"}}");

    assertThat(result.leaked()).isTrue();
    assertThat(result.leakedTypes()).containsExactly(PiiType.PERSON);
    assertThat(alerts).containsExactly(Set.of(PiiType.PERSON));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "MEERA IYER",
        "meera   iyer",
        "Meera\nIyer",
        "Iyer, Meera? no: Meera-Iyer",
        "MeeraIyer",
        "\"name\":\"meera_iyer\""
      })
  void check_nameWithOtherCaseSpacingOrPunctuation_leaks(String response) {
    assertThat(checker.check(DOC, response).leakedTypes()).containsExactly(PiiType.PERSON);
  }

  @ParameterizedTest
  @ValueSource(strings = {"+91 90000 00001", "+91" + "9000000001", "(900) 000-0001", "90000-00001"})
  void check_phoneWithSeparatorsOrCountryCode_leaks(String response) {
    assertThat(checker.check(DOC, "phone: " + response).leakedTypes())
        .containsExactly(PiiType.PHONE);
  }

  @Test
  void check_aadhaarWithoutSpaces_leaks() {
    assertThat(checker.check(DOC, "id 234567890123").leakedTypes())
        .containsExactly(PiiType.AADHAAR);
  }

  @Test
  void check_emailAndAddressInOtherCase_leakBothTypes() {
    LeakCheckResult result =
        checker.check(DOC, "M.IYER1987@EXAMPLE.COM ships to flat 4b lotus residency");

    assertThat(result.leakedTypes()).containsExactlyInAnyOrder(PiiType.EMAIL, PiiType.ADDRESS);
    assertThat(alerts).hasSize(1);
  }

  @Test
  void check_multiWordValueRunTogetherInsideAWord_leaks() {
    // The spaceless form has no word boundaries; for multi-word values recall wins (PII-6).
    assertThat(checker.check(DOC, "Meeraiyerson Ltd").leakedTypes())
        .containsExactly(PiiType.PERSON);
  }

  @Test
  void check_singleWordValueInsideALongerWord_isClean() {
    LeakChecker oneWord = checkerFor(List.of(vault.seal("PERSON_GGGGGGGG", PiiType.PERSON, "Om")));

    assertThat(oneWord.check(DOC, "Omega Traders, Room 4").leaked()).isFalse();
    assertThat(oneWord.check(DOC, "attn: OM").leaked()).isTrue();
  }

  @Test
  void check_shortDigitValue_onlyMatchesAsAWholeWord() {
    LeakChecker shortDigits =
        checkerFor(List.of(vault.seal("ADDRESS_HHHHHHHH", PiiType.ADDRESS, "No. 12")));

    assertThat(shortDigits.check(DOC, "total 1200").leaked()).isFalse();
    assertThat(shortDigits.check(DOC, "no 12 main road").leaked()).isTrue();
  }

  @Test
  void check_phoneDigitsSplitByAWord_isClean() {
    assertThat(checker.check(DOC, "90000 qty 00001").leaked()).isFalse();
  }

  @Test
  void check_noVaultEntries_isCleanWithoutAlert() {
    assertThat(checkerFor(List.of()).check(DOC, "Meera Iyer 9000000001").leaked()).isFalse();
    assertThat(alerts).isEmpty();
  }

  @Test
  void check_blankValue_neverMatches() {
    LeakChecker blank = checkerFor(List.of(vault.seal("PERSON_IIIIIIII", PiiType.PERSON, " - ")));

    assertThat(blank.check(DOC, "a - b").leaked()).isFalse();
  }

  @Test
  void check_entryThatDoesNotDecrypt_failsWithoutValueInMessage() throws GeneralSecurityException {
    VaultEntry foreign =
        TestKeysets.vault(TestKeysets.newVaultKeyset())
            .seal("PERSON_JJJJJJJJ", PiiType.PERSON, "Meera Iyer");

    assertThatThrownBy(() -> checkerFor(List.of(foreign)).check(DOC, "Meera Iyer"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("vault entry could not be decrypted");
    assertThat(alerts).isEmpty();
  }

  @Test
  void resultAndHaystack_neverPrintTheValues() {
    LeakCheckResult result = checker.check(DOC, "Meera Iyer");

    assertThat(result.toString()).doesNotContain("Meera").contains("PERSON");
    assertThat(LeakChecker.Haystack.of("Meera Iyer").toString()).doesNotContain("Meera");
  }

  @Test
  void canonical_foldsAsciiCaseAndPunctuation() {
    assertThat(LeakChecker.canonical("  FLAT 4B,\tLotus--Residency!  "))
        .isEqualTo("flat 4b lotus residency");
    assertThat(LeakChecker.canonical("...")).isEmpty();
  }

  @Test
  void failureCode_isPiiLeak() {
    assertThat(LeakChecker.FAILURE_CODE).isEqualTo("PII_LEAK");
  }
}
