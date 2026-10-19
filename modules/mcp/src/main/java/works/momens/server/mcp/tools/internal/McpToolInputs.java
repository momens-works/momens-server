package works.momens.server.mcp.tools.internal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

final class McpToolInputs {
  private McpToolInputs() {}

  static LocalDate date(String value, String field) {
    if (value.isEmpty()) {
      return null;
    }
    try {
      if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
        throw new DateTimeParseException("Invalid date format", value, 0);
      }
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new McpToolInputException("could not parse " + field + ", want YYYY-MM-DD");
    }
  }

  static Long version(JsonNode args) {
    JsonNode value = args.get("version");
    if (value == null) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new McpToolInputException("version must be a non-negative integer");
    }
    return value.longValue();
  }

  static String argument(JsonNode args, String name) {
    return args.path(name).asText("").strip();
  }
}
