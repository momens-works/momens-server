package works.momens.server.signal;

import java.util.UUID;

public interface SignalDetailService {

  SignalDetail getDetail(UUID signalId, UUID userId);
}
