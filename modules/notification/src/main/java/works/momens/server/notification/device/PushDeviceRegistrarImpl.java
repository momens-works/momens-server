package works.momens.server.notification.device;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import works.momens.server.common.api.BusinessException;
import works.momens.server.common.api.CommonErrorCode;
import works.momens.server.notification.PushDeviceRegistrar;
import works.momens.server.notification.PushInstallationPlatform;

/**
 * push 설치 등록·해제 구현.
 *
 * <p>소유권 이전과 token 이동은 이전 연결 비활성화와 같은 트랜잭션에서 처리해 활성 token 부분 unique 제약 위반 없이 반영한다
 * (docs/design/signal-push-demo-design.md 8.4절). token 값은 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PushDeviceRegistrarImpl implements PushDeviceRegistrar {

  private static final String REGISTRATION_LOCK_NAME = "notification.push_installations";

  private final PushInstallationRepository pushInstallationRepository;
  private final JdbcTemplate jdbcTemplate;

  @Override
  @Transactional
  public void register(
      UUID userId,
      String firebaseInstallationId,
      String fcmRegistrationToken,
      PushInstallationPlatform platform) {
    requireNotBlank(firebaseInstallationId);
    requireNotBlank(fcmRegistrationToken);
    lockRegistrationLedger();
    try {
      registerLocked(userId, firebaseInstallationId, fcmRegistrationToken, platform);
      pushInstallationRepository.flush();
    } catch (DataIntegrityViolationException e) {
      // unique 위반 detail에 FCM token이 포함될 수 있으므로 원인 예외를 전역 500 로그로 넘기지 않는다.
      throw new BusinessException(CommonErrorCode.COMMON_CONFLICT);
    }
    log.info("push installation registered userId={} fid={}", userId, firebaseInstallationId);
  }

  private void registerLocked(
      UUID userId,
      String firebaseInstallationId,
      String fcmRegistrationToken,
      PushInstallationPlatform platform) {
    // 같은 token이 다른 FID에 활성으로 연결돼 있으면 token이 새 설치로 이동한 것이므로 이전 연결을 먼저 닫는다.
    // Hibernate는 flush 시 INSERT를 UPDATE보다 먼저 실행하므로, 비활성화를 명시적으로 flush해
    // 활성 token 부분 unique 제약이 새 행 INSERT 시점에 깨지지 않게 한다.
    List<PushInstallation> tokenHolders =
        pushInstallationRepository
            .findByFcmRegistrationTokenAndActiveTrue(fcmRegistrationToken)
            .stream()
            .filter(
                existing -> !existing.getFirebaseInstallationId().equals(firebaseInstallationId))
            .toList();
    if (!tokenHolders.isEmpty()) {
      tokenHolders.forEach(PushInstallation::deactivate);
      pushInstallationRepository.flush();
    }
    pushInstallationRepository
        .findByFirebaseInstallationId(firebaseInstallationId)
        .ifPresentOrElse(
            installation -> installation.reassign(userId, fcmRegistrationToken),
            () ->
                pushInstallationRepository.save(
                    PushInstallation.builder()
                        .firebaseInstallationId(firebaseInstallationId)
                        .userId(userId)
                        .fcmRegistrationToken(fcmRegistrationToken)
                        .platform(platform.value())
                        .build()));
  }

  @Override
  @Transactional
  public void unregister(UUID userId, String firebaseInstallationId) {
    requireNotBlank(firebaseInstallationId);
    lockRegistrationLedger();
    boolean unregistered =
        pushInstallationRepository
            .findByFirebaseInstallationId(firebaseInstallationId)
            .filter(installation -> installation.getUserId().equals(userId))
            .map(PushInstallation::deactivate)
            .orElse(false);
    if (unregistered) {
      log.info("push installation unregistered userId={} fid={}", userId, firebaseInstallationId);
    } else {
      log.debug(
          "push installation unregister ignored userId={} fid={}", userId, firebaseInstallationId);
    }
  }

  /**
   * 등록·해제는 로그인·로그아웃·token refresh 시점의 저빈도 쓰기다. 존재하지 않는 FID/token에는 row lock을 걸 수 없으므로 트랜잭션 advisory
   * lock 하나로 조회-비활성화-저장을 직렬화해 부분 unique 경합을 막는다.
   */
  private void lockRegistrationLedger() {
    jdbcTemplate.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0)) IS NULL",
        Boolean.class,
        REGISTRATION_LOCK_NAME);
  }

  private static void requireNotBlank(String value) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(CommonErrorCode.COMMON_VALIDATION_FAILED);
    }
  }
}
