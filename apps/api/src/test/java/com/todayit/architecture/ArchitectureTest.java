package com.todayit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Controller;

@AnalyzeClasses(packages = "com.todayit", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule AGGREGATE_PACKAGES_SHOULD_BE_FREE_OF_CYCLES =
      slices().matching("com.todayit.(*)..").should().beFreeOfCycles().allowEmptyShould(true);

  @ArchTest
  static final ArchRule CONTROLLERS_SHOULD_NOT_ACCESS_REPOSITORIES =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..repository..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule SERVICES_SHOULD_NOT_DEPEND_ON_CONTROLLER_DTOS =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..dto..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule ENTITIES_SHOULD_NOT_DEPEND_ON_OUTER_LAYERS =
      noClasses()
          .that()
          .resideInAPackage("..entity..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..controller..", "..service..", "..repository..", "..dto..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule CONTROLLERS_SHOULD_USE_CONTROLLER_SUFFIX =
      classes()
          .that()
          .areAnnotatedWith(Controller.class)
          .or()
          .areMetaAnnotatedWith(Controller.class)
          .should()
          .haveSimpleNameEndingWith("Controller")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule SERVICES_SHOULD_USE_SERVICE_SUFFIX =
      classes()
          .that()
          .resideInAPackage("..service..")
          .and()
          .resideOutsideOfPackages("..service.command..", "..service.model..")
          .should()
          .haveSimpleNameEndingWith("Service")
          .orShould()
          .haveSimpleNameEndingWith("Facade")
          .orShould()
          .haveSimpleNameEndingWith("Coordinator")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule REPOSITORIES_SHOULD_USE_REPOSITORY_SUFFIX =
      classes()
          .that()
          .resideInAPackage("..repository..")
          .should()
          .haveSimpleNameEndingWith("Repository")
          .allowEmptyShould(true);
}
