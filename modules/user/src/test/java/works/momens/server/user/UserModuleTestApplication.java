package works.momens.server.user;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** 통합 테스트의 서비스 탐색을 위해 {@code @ComponentScan}을 사용하며 JPA 슬라이스는 {@code TypeExcludeFilter}로 제한합니다. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
class UserModuleTestApplication {}
