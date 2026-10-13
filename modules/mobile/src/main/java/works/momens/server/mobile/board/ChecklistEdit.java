package works.momens.server.mobile.board;

import java.util.UUID;

/** id가 있으면 기존 완료기준, 없으면 새 항목입니다. 조합 서비스가 모바일 입력을 도메인 command로 변환합니다. */
record ChecklistEdit(UUID id, String title, boolean completed) {}
