package works.momens.server;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

  static final ApplicationModules modules = ApplicationModules.of(MomensServerApplication.class);

  @Test
  void verifiesModuleStructure() {
    modules.verify();
  }
}
