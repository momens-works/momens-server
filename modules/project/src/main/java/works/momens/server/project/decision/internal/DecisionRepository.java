package works.momens.server.project.decision.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DecisionRepository extends JpaRepository<Decision, UUID> {}
