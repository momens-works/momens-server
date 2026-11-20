package works.momens.server.web;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/** 컨트롤러 탐색을 위해 component scan을 사용하며, {@code TypeExcludeFilter}로 관련 없는 빈이 MVC 슬라이스에 등록되는 것을 막습니다. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
class WebModuleTestApplication {}
