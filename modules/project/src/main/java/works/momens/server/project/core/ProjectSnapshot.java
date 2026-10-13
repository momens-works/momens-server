package works.momens.server.project.core;

import java.time.LocalDate;
import java.util.UUID;

/** 모바일용 프로젝트 조회 모델입니다. 역할은 workspace 멤버십, 진행률은 태스크에서 별도로 조회합니다. */
public record ProjectSnapshot(
    UUID id, UUID workspaceId, String name, LocalDate targetDate, String summary) {}
