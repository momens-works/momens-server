# syntax=docker/dockerfile:1

# momens-proto 서브모듈(:app→:signal→:momens-proto)은 Java 17 toolchain 을 요구합니다.
# CI 러너에는 JDK 17 이 기본 설치돼 Gradle 이 자동탐지하지만, 아래 빌드 이미지에는
# JDK 21 만 있어 여기서 JDK 17 을 별도로 제공합니다.
FROM eclipse-temurin:17-jdk-jammy AS jdk17

# 빌드 스테이지: Temurin 21 JDK (CI 의 distribution/version 과 일치).
FROM eclipse-temurin:21-jdk-jammy AS build
# momens-proto(Java 17) 컴파일용 toolchain. installations.paths 로 Gradle 에 이 경로를 알려줍니다.
COPY --from=jdk17 /opt/java/openjdk /opt/java/jdk17
WORKDIR /workspace

# 1) 의존성 워밍 레이어: 빌드 스크립트·wrapper·proto 서브모듈만 먼저 복사해 Gradle 배포본과
#    의존성 메타데이터를 미리 받아 둡니다. 소스만 바뀌면 아래 COPY . . 이후 레이어만
#    무효화되고 이 레이어는 캐시 히트되어, 매 빌드마다 Gradle 배포본/메타데이터를 다시
#    받지 않습니다. 새 모듈을 추가하면 해당 모듈의 build.gradle COPY 행도 여기에 추가합니다.
#    `verifyDockerModuleBuildScripts`가 `COPY <project-dir>/build.gradle ./<project-dir>/` 형식과
#    Gradle 서브프로젝트 목록의 양방향 정합성을 검사합니다(공백과 destination의 `./`은 선택).
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY third_party ./third_party
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
# gradlew 의 줄바꿈은 .gitattributes 의 `gradlew text eol=lf` 로 보장합니다. 예전에는 여기서
# sed 로 CR 을 지웠지만, 아래 COPY . . 가 원본으로 다시 덮어써 정작 bootJar 단계에는 적용되지
# 않았습니다(방어가 무력화된 상태). 체크아웃 시점에 고치는 편이 로컬 빌드까지 함께 보호합니다.
# :app 기준으로 configuration 그래프를 resolve 해 Gradle 배포본·의존성 메타데이터를 이
# 레이어에 캐시합니다(하위 모듈 자체의 annotationProcessor 등은 각 모듈 컴파일 시 해석되지만,
# Lombok 처럼 좌표가 같은 의존성은 이 캐시가 그대로 적용됩니다).
# 이 단계는 캐시 워밍 전용이고 의존성 검증 수단이 아닙니다. dependencies 리포트는 해석에
# 실패한 항목을 FAILED 로 표시할 뿐 태스크는 성공으로 끝나고, 트리 출력도 로그가 커져 버립니다.
# 실제 의존성 검증은 뒤의 bootJar 가 수행합니다.
RUN ./gradlew --no-daemon \
      -Dorg.gradle.java.installations.paths=/opt/java/jdk17 \
      :app:dependencies > /dev/null

# 2) 소스 복사 후 빌드. 테스트는 PostgreSQL Testcontainers(Docker)가 필요해 이미지 빌드
#    중 실행할 수 없습니다. CI 가 spotlessCheck/test 를 별도로 수행하므로 여기서는 bootJar
#    만 만듭니다.
COPY . .
RUN ./gradlew --no-daemon \
      -Dorg.gradle.java.installations.paths=/opt/java/jdk17 \
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
