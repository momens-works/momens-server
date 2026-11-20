package works.momens.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Keeps MCP capability and OAuth layer dependencies explicit within the Gradle module. */
class McpSubDomainBoundaryTests {
  private static final String BASE = "works.momens.server.mcp";
  private final JavaClasses classes = new ClassFileImporter().importPackages(BASE);

  @Test
  void capabilitiesHaveNoCycles() {
    SlicesRuleDefinition.slices()
        .matching(BASE + ".(*)..")
        .should()
        .beFreeOfCycles()
        .check(classes);
  }

  @Test
  void capabilitiesDependOnlyInAllowedDirections() {
    deny("grant", "oauth", "transport", "configuration", "tools");
    deny("configuration", "oauth", "transport", "grant");
    // OAuth implements the transport's public bearer verifier port.
    deny("oauth", "transport.internal", "grant.internal", "tools");
    deny("tools", "oauth", "grant.internal", "transport.internal");
    deny("transport", "oauth", "grant.internal", "tools");
  }

  @Test
  void oauthApplicationDoesNotDependOnAdaptersOrHttpDtos() {
    deny("oauth.application", "oauth.presentation", "oauth.infrastructure");
    deny("oauth.presentation", "oauth.infrastructure");
    deny("oauth.infrastructure", "oauth.presentation");
    noClasses()
        .that()
        .resideInAPackage(BASE + ".oauth.application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("io.swagger..", "jakarta.servlet..")
        .check(classes);
  }

  private void deny(String source, String... targets) {
    noClasses()
        .that()
        .resideInAPackage(BASE + "." + source + "..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            Arrays.stream(targets).map(target -> BASE + "." + target + "..").toArray(String[]::new))
        .check(classes);
  }
}
