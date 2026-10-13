# syntax=docker/dockerfile:1

# 빌드 스테이지: Temurin 21 JDK (CI 의 distribution/version 과 일치).
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# 빌드 스크립트를 먼저 복사해 소스 변경 시 Gradle 배포본과 의존성 메타데이터 캐시를 재사용합니다.
# 모듈 추가 시 COPY도 추가합니다. verifyDockerModuleBuildScripts가 Gradle 모듈 목록과 대조합니다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY app/build.gradle ./app/
COPY common/build.gradle ./common/
COPY modules/auth/build.gradle ./modules/auth/
COPY modules/context/build.gradle ./modules/context/
COPY modules/memory/build.gradle ./modules/memory/
COPY modules/minsu/build.gradle ./modules/minsu/
COPY modules/mobile/build.gradle ./modules/mobile/
COPY modules/mcp/build.gradle ./modules/mcp/
COPY modules/notification/build.gradle ./modules/notification/
COPY modules/onboarding/build.gradle ./modules/onboarding/
COPY modules/outbox/build.gradle ./modules/outbox/
COPY modules/project/build.gradle ./modules/project/
COPY modules/signal/build.gradle ./modules/signal/
COPY modules/source/build.gradle ./modules/source/
COPY modules/user/build.gradle ./modules/user/
COPY modules/web/build.gradle ./modules/web/
COPY modules/workspace/build.gradle ./modules/workspace/
# 줄바꿈은 .gitattributes에서 LF로 통일합니다.
# :app의 의존성 메타데이터를 캐시합니다. dependencies는 해석 실패에도 성공할 수 있으므로
# 실제 의존성 검증은 뒤의 bootJar가 수행합니다.
RUN ./gradlew --no-daemon \
      :app:dependencies > /dev/null

# 2) 소스 복사 후 빌드. 테스트는 PostgreSQL Testcontainers(Docker)가 필요해 이미지 빌드
#    중 실행할 수 없습니다. CI 가 spotlessCheck/test 를 별도로 수행하므로 여기서는 bootJar
#    만 만듭니다.
COPY . .
RUN ./gradlew --no-daemon \
      :app:bootJar -x test \
 && cp app/build/libs/*.jar /workspace/app.jar

# 런타임 스테이지: Temurin 21 JRE, 일반 사용자 권한으로 실행.
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN useradd -r -u 1000 momens
COPY --from=build /workspace/app.jar /app/app.jar
USER momens
EXPOSE 8080
# exec form 으로 PID 1 에 SIGTERM 이 전달되도록 해 graceful shutdown 을 보존합니다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
