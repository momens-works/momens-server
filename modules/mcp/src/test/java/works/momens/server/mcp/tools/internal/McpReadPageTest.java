package works.momens.server.mcp.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import works.momens.server.mcp.transport.McpAuthenticationContext;

class McpReadPageTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final McpAuthenticationContext context =
      new McpAuthenticationContext(
          UUID.randomUUID(), UUID.randomUUID(), "client", UUID.randomUUID(), Set.of());
  private final Instant now = Instant.parse("2026-10-17T00:00:00.123456Z");

  record Item(UUID id, Instant createdAt, String text) {}

  @ParameterizedTest
  @ValueSource(strings = {"0", "101", "-1", "1.5", "2147483648", "null", "\"20\"", "true"})
  void rejectsInvalidLimits(String limit) {
    assertThatThrownBy(
            () ->
                McpReadPage.parse(
                    "list_tasks", mapper.readTree("{\"limit\":" + limit + "}"), context))
        .isInstanceOf(McpToolInputException.class);
  }

  @Test
  void cursorPreservesMicrosecondsAndBindsToolWorkspaceUserAndFilters() {
    var args = mapper.createObjectNode().put("status", "todo").put("limit", 1);
    McpReadPage page = McpReadPage.parse("list_tasks", args, context);
    Item first = new Item(UUID.randomUUID(), now, "first");
    String text =
        page.render(
            "task",
            "empty",
            List.of(first, new Item(UUID.randomUUID(), now, "second")),
            Item::text,
            Item::createdAt,
            Item::id,
            48000);
    String cursor = text.substring(text.lastIndexOf("cursor: ") + 8);
    args.put("cursor", cursor).put("limit", 100);
    McpReadPage next = McpReadPage.parse("list_tasks", args, context);
    assertThat(next.time()).isEqualTo(now);
    assertThat(next.id()).isEqualTo(first.id());
    assertThat(next.limit()).isEqualTo(100);
    assertThatThrownBy(() -> McpReadPage.parse("list_projects", args, context))
        .isInstanceOf(McpToolInputException.class);
    var other =
        new McpAuthenticationContext(
            context.grantId(), context.userId(), "client", UUID.randomUUID(), Set.of());
    assertThatThrownBy(() -> McpReadPage.parse("list_tasks", args, other))
        .isInstanceOf(McpToolInputException.class);
    args.put("status", "done");
    assertThatThrownBy(() -> McpReadPage.parse("list_tasks", args, context))
        .isInstanceOf(McpToolInputException.class);
  }

  @Test
  void byteLimitedPageContinuesFromLastEmittedRowAndNeverSplitsUnicode() {
    List<Item> rows =
        IntStream.range(0, 101)
            .mapToObj(i -> new Item(UUID.randomUUID(), now, "😀한글".repeat(2000)))
            .toList();
    var args = mapper.createObjectNode().put("limit", 100);
    McpReadPage page = McpReadPage.parse("list_tasks", args, context);
    String text = page.render("task", "empty", rows, Item::text, Item::createdAt, Item::id, 48000);
    assertThat(text.getBytes(StandardCharsets.UTF_8).length).isLessThan(49000);
    assertThat(text).contains("11 task(s)", "[truncated]").doesNotContain("�");
    args.put("cursor", text.substring(text.lastIndexOf("cursor: ") + 8));
    assertThat(McpReadPage.parse("list_tasks", args, context).id()).isEqualTo(rows.get(10).id());
    assertThat(
            page.render(
                "task",
                "empty",
                List.of(rows.getFirst()),
                Item::text,
                Item::createdAt,
                Item::id,
                48000))
        .doesNotContain("cursor:");
  }

  @Test
  void rejectsOutOfRangeCursorTimeBeforeDatabaseAccess() {
    var args = mapper.createObjectNode();
    var page = McpReadPage.parse("list_tasks", args, context);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("1|" + page.binding() + "|+999999999-12-31T23:59:59Z|" + UUID.randomUUID())
                    .getBytes(StandardCharsets.UTF_8));
    args.put("cursor", encoded);
    assertThatThrownBy(() -> McpReadPage.parse("list_tasks", args, context))
        .isInstanceOf(McpToolInputException.class);
  }

  @Test
  void rejectsMalformedAndOversizedCursors() {
    for (String cursor : List.of("", "%%%", "a".repeat(1025))) {
      assertThatThrownBy(
              () ->
                  McpReadPage.parse(
                      "list_tasks", mapper.createObjectNode().put("cursor", cursor), context))
          .isInstanceOf(McpToolInputException.class);
    }
  }
}
