package works.momens.server.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import works.momens.server.common.test.AbstractPostgresIntegrationTest;

/**
 * 커밋된 OpenAPI 스냅샷과 서버가 실제로 내보내는 문서를 대조하는 테스트.
 *
 * <p>다른 OpenAPI 테스트가 문서 자체의 성질(스키마 naming, 인증 스킴, 실응답 일치)을 보는 것과 달리, 여기서는 문서가 <b>바뀌었는지</b>를 본다. 웹
 * 이관의 전환 단위는 클라이언트 배포이고 소비자(momens-fe)는 이 스냅샷에서 타입을 생성하므로, 계약 변경이 PR diff에 드러나야 한다(MOM-0878).
 *
 * <p>스냅샷 갱신은 의도적인 행위여야 하므로 이 테스트는 기본적으로 비교만 한다. 갱신은 {@code ./gradlew updateOpenApiSnapshot}이 같은
 * 테스트를 write 모드로 한 번 돌려서 수행한다. 생성과 비교가 {@link #normalize} 하나를 공유하므로 두 경로가 어긋날 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSnapshotTest extends AbstractPostgresIntegrationTest {

  private static final String WRITE_PROPERTY = "momens.openapi.snapshot.write";

  private static final String UPDATE_COMMAND = "./gradlew updateOpenApiSnapshot";

  /**
   * 정규화된 직렬화.
   *
   * <p>springdoc 출력의 path·schema 순서가 실행 간 안정적이라고 가정하지 않는다. 키를 정렬해 직렬화하면 순서가 흔들려도 스냅샷이 달라지지 않아 CI가
   * 플레이키해지지 않는다. 줄바꿈은 {@code System.lineSeparator()} 대신 {@code \n}으로 고정해 OS가 스냅샷을 바꾸지 못하게 한다.
   *
   * <p>배열은 일괄 정렬하지 않는다. {@code enum}, {@code required}, {@code parameters}와 예시 값 배열은 순서가 내용의 일부이거나
   * 선언 순서를 따라 안정적이라, 정렬하면 의미를 바꾸거나 얻는 것 없이 diff만 키운다. 유일한 예외를 {@link #sortRootTags}가 다룬다.
   */
  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .defaultPrettyPrinter(prettyPrinter())
          .build();

  private static final ObjectWriter WRITER = MAPPER.writerWithDefaultPrettyPrinter();

  @Autowired private MockMvc mockMvc;

  @Test
  void snapshotMatchesLiveSpec() throws Exception {
    String actual = normalize(fetchApiDocs());
    Path snapshot = OpenApiSnapshot.path();

    if (Boolean.getBoolean(WRITE_PROPERTY)) {
      Files.createDirectories(snapshot.getParent());
      Files.writeString(snapshot, actual);
      return;
    }

    assertThat(snapshot).as("OpenAPI 스냅샷이 없습니다. %s 로 생성하세요.", UPDATE_COMMAND).isRegularFile();
    // 드리프트가 나면 아래 diff가 길다. 실제 갱신 절차는 갱신 task를 돌리고 git diff를 읽는 것이므로,
    // 여기서 diff를 정성껏 요약하기보다 한 줄로 다음 행동을 지시한다.
    assertThat(Files.readString(snapshot))
        .as(
            "커밋된 OpenAPI 스냅샷이 현재 API 계약과 다릅니다. 계약 변경이 의도한 것이면 %s 로 갱신하고 diff를 리뷰에 포함하세요.",
            UPDATE_COMMAND)
        .isEqualTo(actual);
  }

  /**
   * {@code servers[0].url}은 {@code MOMENS_OPENAPI_SERVER_URL}로 주입되므로 그대로 두면 환경마다 스냅샷이 달라진다. 소비자가 쓰는
   * 것은 path·schema 계약이라 통째로 제외한다.
   */
  private String normalize(String spec) {
    Map<String, Object> document = MAPPER.readValue(spec, new TypeReference<>() {});
    document.remove("servers");
    sortRootTags(document);
    return WRITER.writeValueAsString(document) + "\n";
  }

  /**
   * 루트 {@code tags}는 springdoc이 set 순회 순서로 내보내 알파벳순도 path 순도 아니다. 같은 classpath에서는 해시 순서가 결정적이라 재현
   * 실행으로는 드러나지 않지만, tag를 하나 추가하면 rehash로 배열 전체가 재정렬되어 무관한 PR에 churn diff가 생기고, springdoc이나 JDK를 올리면
   * 계약이 그대로인데 게이트가 실패한다. 이름순으로 고정한다.
   */
  private void sortRootTags(Map<String, Object> document) {
    if (!(document.get("tags") instanceof List<?> tags)) {
      return;
    }
    List<?> sorted = new ArrayList<>(tags);
    sorted.sort(Comparator.comparing(tag -> String.valueOf(((Map<?, ?>) tag).get("name"))));
    document.put("tags", sorted);
  }

  private String fetchApiDocs() throws Exception {
    return mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static DefaultPrettyPrinter prettyPrinter() {
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    return new DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter);
  }
}
