package dev.invoiceintel.app.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module dependency rules from architecture.md §4.1 (NFR-9). Each module is a layer; a layer lists
 * the modules allowed to depend on it. Modules that are still empty are optional layers and rules
 * on them allow an empty match, so the rules already apply while the skeleton fills in. The
 * PII-gate rules of §6.4 are in {@link PiiGateRulesTest}; {@code extract}'s use of {@code
 * text.Word} through {@code RedactedContent.Page.words()} is the one exception to the layers here.
 */
@AnalyzeClasses(packages = "dev.invoiceintel", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleDependencyRulesTest {

  private static final String BASE = "dev.invoiceintel.";

  @ArchTest
  static final ArchRule modulesOnlyUseAllowedModules =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .ignoreDependency(resideInAPackage(BASE + "extract.."), name(BASE + "text.Word"))
          .withOptionalLayers(true)
          .layer("common")
          .definedBy(BASE + "common..")
          .layer("storage")
          .definedBy(BASE + "storage..")
          .layer("ingest")
          .definedBy(BASE + "ingest..")
          .layer("text")
          .definedBy(BASE + "text..")
          .layer("pii")
          .definedBy(BASE + "pii..")
          .layer("extract")
          .definedBy(BASE + "extract..")
          .layer("validate")
          .definedBy(BASE + "validate..")
          .layer("reconcile")
          .definedBy(BASE + "reconcile..")
          .layer("pipeline")
          .definedBy(BASE + "pipeline..")
          .layer("app")
          .definedBy(BASE + "app..")
          .layer("eval")
          .definedBy(BASE + "eval..")
          .whereLayer("storage")
          .mayOnlyBeAccessedByLayers("ingest", "text", "pii", "pipeline", "app")
          .whereLayer("ingest")
          .mayOnlyBeAccessedByLayers("app")
          .whereLayer("text")
          .mayOnlyBeAccessedByLayers("pii", "pipeline")
          .whereLayer("pii")
          .mayOnlyBeAccessedByLayers("extract", "pipeline", "app")
          .whereLayer("extract")
          .mayOnlyBeAccessedByLayers("pipeline", "eval")
          .whereLayer("validate")
          .mayOnlyBeAccessedByLayers("pipeline", "app", "eval")
          .whereLayer("reconcile")
          .mayOnlyBeAccessedByLayers("pipeline", "app", "eval")
          .whereLayer("pipeline")
          .mayOnlyBeAccessedByLayers("app", "eval")
          .whereLayer("app")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("eval")
          .mayNotBeAccessedByAnyLayer();

  @ArchTest
  static final ArchRule extractUsesOnlyRedactedContentFromPii =
      noClasses()
          .that()
          .resideInAPackage(BASE + "extract..")
          .should()
          .dependOnClassesThat(
              resideInAPackage(BASE + "pii..")
                  .and(not(name(BASE + "pii.RedactedContent")))
                  .and(not(name(BASE + "pii.RedactedContent$Page"))))
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule onlyExtractUsesSpringAi =
      noClasses()
          .that()
          .resideOutsideOfPackage(BASE + "extract..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.ai..");

  @ArchTest
  static final ArchRule onlyAppAndEvalHaveMain =
      noMethods()
          .that()
          .haveName("main")
          .should()
          .beDeclaredInClassesThat()
          .resideOutsideOfPackages(BASE + "app..", BASE + "eval..");
}
