package works.momens.server.mobile.board;

import java.util.List;

public record MobileTaskGroup(MobileTaskStatus status, List<MobileTaskCard> tasks) {}
