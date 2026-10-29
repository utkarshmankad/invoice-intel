package dev.invoiceintel.app.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/**
 * The PII gate (PII-1, NFR-1, architecture.md §6.4): only {@code RedactedContent} can reach a
 * remote model, only {@code pii} can build it, and only {@code pii} touches the vault. Module-level
 * dependencies are in {@link ModuleDependencyRulesTest}. Rules on code that doesn't exist yet (the
 * {@code LlmGateway}) allow an empty match, so they apply as soon as it is written.
 */
@AnalyzeClasses(packages = "dev.invoiceintel", importOptions = ImportOption.DoNotIncludeTests.class)
class PiiGateRulesTest {

  private static final String BASE = "dev.invoiceintel.";
  private static final String REDACTED_CONTENT = BASE + "pii.RedactedContent";

  /** Types an {@code LlmGateway} method must never accept: raw text, bytes or blob keys. */
  private static final Set<String> RAW_INPUTS =
      Set.of(
          String.class.getName(),
          byte[].class.getName(),
          BASE + "text.TextLayer",
          BASE + "text.TextPage",
          BASE + "storage.BlobKey");

  /** {@code RedactedContent.Page.words()} hands out {@code text.Word}; nothing else of text. */
  @ArchTest
  static final ArchRule extractUsesOnlyWordFromText =
      noClasses()
          .that()
          .resideInAPackage(BASE + "extract..")
          .should()
          .dependOnClassesThat(
              resideInAPackage(BASE + "text..").and(name(BASE + "text.Word").negate()))
          .because("extract sees documents only through RedactedContent (§6.4)")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule extractNeverUsesRawBlobs =
      noClasses()
          .that()
          .resideInAPackage(BASE + "extract..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(BASE + "storage..")
          .because("raw blob keys lead to unredacted originals and page images (§6.4)")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule llmGatewayTakesNoRawInput =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .haveSimpleName("LlmGateway")
          .should(takeNoParameterOf(RAW_INPUTS))
          .because("LlmGateway accepts RedactedContent only (§6.4)")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule redactedContentIsBuiltOnlyInPii =
      constructors()
          .that()
          .areDeclaredInClassesThat(name(REDACTED_CONTENT).or(name(REDACTED_CONTENT + "$Page")))
          .should()
          .bePackagePrivate()
          .orShould()
          .bePrivate()
          .because("only the PII stage may create RedactedContent (§6.4)");

  @ArchTest
  static final ArchRule onlyPiiUsesItsInternals =
      noClasses()
          .that()
          .resideOutsideOfPackage(BASE + "pii..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(BASE + "pii.internal..")
          .because("only pii reads the vault tables (§4.1)");

  @ArchTest
  static final ArchRule onlyPiiOpensTheVault =
      noClasses()
          .that()
          .resideOutsideOfPackage(BASE + "pii..")
          .should()
          .callMethod(BASE + "pii.PiiVault", "open", BASE + "pii.VaultEntry")
          .because("only pii may decrypt vault values (§4.1, PII-7)");

  private static ArchCondition<JavaMethod> takeNoParameterOf(Set<String> types) {
    return new ArchCondition<>("take no parameter of type " + types) {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        for (JavaClass type : method.getRawParameterTypes()) {
          if (types.contains(type.getName())) {
            events.add(
                SimpleConditionEvent.violated(
                    method, method.getFullName() + " takes " + type.getName()));
          }
        }
      }
    };
  }
}
