package works.momens.server.minsu;

import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

/** 검증과 title 정규화가 끝난 Signal task draft. */
public record TaskDraft(String title, TaskRole role, TaskPriority priority) {}
