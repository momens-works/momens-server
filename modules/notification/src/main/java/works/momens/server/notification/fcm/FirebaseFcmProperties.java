package works.momens.server.notification.fcm;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "momens.notification.push.firebase")
record FirebaseFcmProperties(@NotBlank String projectId) {}
