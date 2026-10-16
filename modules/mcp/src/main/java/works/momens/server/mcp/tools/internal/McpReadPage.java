package works.momens.server.mcp.tools.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import works.momens.server.mcp.transport.McpAuthenticationContext;

/** Cursor는 조회 위치만 전달합니다. 매 호출의 grant·멤버십·workspace 검증을 대체하지 않습니다. */
record McpReadPage(int limit, Instant time, UUID id, String binding, String tool) {
  static McpReadPage parse(String tool, JsonNode arguments, McpAuthenticationContext context) {
    int limit = 20;
    if (arguments.has("limit")) {
      JsonNode value = arguments.get("limit");
      if (!value.isIntegralNumber()
          || !value.canConvertToInt()
          || value.intValue() < 1
          || value.intValue() > 100) {
        throw new McpToolInputException("limit must be an integer between 1 and 100");
      }
      limit = value.intValue();
    }
    TreeMap<String, String> filters = new TreeMap<>();
    arguments
        .properties()
        .forEach(
            entry -> {
              if (!entry.getKey().equals("cursor") && !entry.getKey().equals("limit")) {
                filters.put(entry.getKey(), entry.getValue().asText().strip());
              }
            });
    StringBuilder filterKey = new StringBuilder();
    filters.forEach(
        (key, value) ->
            filterKey
                .append(key.length())
                .append(':')
                .append(key)
                .append(value.length())
                .append(':')
                .append(value));
    String binding =
        digest(tool + ":" + context.workspaceId() + ":" + context.userId() + ":" + filterKey);
    if (!arguments.has("cursor")) {
      return new McpReadPage(limit, null, null, binding, tool);
    }
    String cursor = arguments.path("cursor").asText();
    try {
      if (cursor.length() > 1024) {
        throw new IllegalArgumentException();
      }
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 4 || !parts[0].equals("1") || !parts[1].equals(binding)) {
        throw new IllegalArgumentException();
      }
      Instant time = Instant.parse(parts[2]);
      if (time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
          || time.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
        throw new IllegalArgumentException();
      }
      return new McpReadPage(limit, time, UUID.fromString(parts[3]), binding, tool);
    } catch (RuntimeException exception) {
      throw new McpToolInputException(
          "Invalid cursor. Keep the same tool and filters, or omit cursor to restart.");
    }
  }

  <T> String render(
      String kind,
      String empty,
      List<T> rows,
      Function<T, String> format,
      Function<T, Instant> timestamp,
      Function<T, UUID> identifier,
      int byteBudget) {
    StringBuilder body = new StringBuilder();
    int count = 0;
    int bodyBytes = 0;
    for (T row : rows) {
      if (count == limit) {
        break;
      }
      String line = "\n- " + clip(format.apply(row), 4000);
      int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
      if (bodyBytes + lineBytes > byteBudget) {
        break;
      }
      bodyBytes += lineBytes;
      body.append(line);
      count++;
    }
    String text = count == 0 ? empty : count + " " + kind + "(s):" + body;
    if (count < rows.size() && count > 0) {
      T last = rows.get(count - 1);
      String cursor =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  ("1|" + binding + "|" + timestamp.apply(last) + "|" + identifier.apply(last))
                      .getBytes(StandardCharsets.UTF_8));
      text += "\n\nMore results. Call " + tool + " with the same filters and cursor: " + cursor;
    }
    return text;
  }

  static String clip(String text, int maxBytes) {
    if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
      return text;
    }
    String suffix = "… [truncated]";
    int budget = maxBytes - suffix.getBytes(StandardCharsets.UTF_8).length;
    int end = 0;
    int bytes = 0;
    while (end < text.length()) {
      int cp = text.codePointAt(end);
      int size = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
      if (bytes + size > budget) {
        break;
      }
      bytes += size;
      end += Character.charCount(cp);
    }
    return text.substring(0, end) + suffix;
  }

  private static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
