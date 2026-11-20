package works.momens.server.mobile.brief;

import java.util.List;

public record MobileBriefSignalPage(List<MobileBrief.SignalItem> items, String nextCursor) {}
