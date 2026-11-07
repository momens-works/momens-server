package works.momens.server.minsu.draft.generation;

import works.momens.server.project.task.TaskPolicy;

final class TaskTitleNormalizer {

  private TaskTitleNormalizer() {}

  static String normalize(String title) {
    String normalized = title == null ? "" : title.trim();
    if (normalized.length() <= TaskPolicy.TITLE_MAX_LENGTH) {
      return normalized;
    }
    int end = TaskPolicy.TITLE_MAX_LENGTH;
    if (Character.isHighSurrogate(normalized.charAt(end - 1))
        && Character.isLowSurrogate(normalized.charAt(end))) {
      end--;
    }
    return normalized.substring(0, end).stripTrailing();
  }
}
