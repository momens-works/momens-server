package works.momens.server.project;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/** {@code TypeExcludeFilter}를 명시해 다른 모듈에 의존하는 서비스가 JPA 슬라이스에 자동 등록되는 것을 막습니다. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
class ProjectModuleTestApplication {}
