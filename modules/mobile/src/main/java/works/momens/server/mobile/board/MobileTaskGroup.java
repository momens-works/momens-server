package works.momens.server.mobile.board;

import java.util.List;

/** 보드 그룹 하나. {@code status}가 그룹 키와 라벨, 표시 순서를 정한다. */
public record MobileTaskGroup(MobileTaskStatus status, List<MobileTaskCard> tasks) {}
