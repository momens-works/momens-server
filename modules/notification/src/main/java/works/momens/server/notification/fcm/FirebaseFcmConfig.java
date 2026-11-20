package works.momens.server.notification.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Firebase Admin SDK 초기화.
 *
 * <p>인증 정보는 Application Default Credentials로 읽는다. dev 배포는 GKE Workload Identity로 전용 서비스 계정을 연결하며
 * 서비스 계정 JSON이나 credential Secret을 사용하지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "momens.notification.push.enabled", havingValue = "true")
@EnableConfigurationProperties(FirebaseFcmProperties.class)
class FirebaseFcmConfig {

  @Bean
  FirebaseMessaging firebaseMessaging(FirebaseFcmProperties properties) throws IOException {
    FirebaseApp app =
        FirebaseApp.getApps().isEmpty()
            ? FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId(properties.projectId())
                    .build())
            : FirebaseApp.getInstance();
    return FirebaseMessaging.getInstance(app);
  }
}
