package works.momens.server.source.connection;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceCredentialRepository extends JpaRepository<SourceCredential, UUID> {}
