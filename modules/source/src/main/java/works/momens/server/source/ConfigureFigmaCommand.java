package works.momens.server.source;

import java.util.List;

public record ConfigureFigmaCommand(String teamId, List<String> fileKeys) {}
