package com.atsreviewer.backend;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
		packages = "com.atsreviewer.backend",
		importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {
	@ArchTest
	static final ArchRule engineNeverImportsSpringOrWebPackages = noClasses()
			.that().resideInAPackage("..engine..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"org.springframework..",
					"jakarta..",
					"..analysis..",
					"..auth..",
					"..consent..",
					"..guest..",
					"..jobdescription..",
					"..ratelimit..",
					"..resume..",
					"..security..",
					"..storage..");
}
