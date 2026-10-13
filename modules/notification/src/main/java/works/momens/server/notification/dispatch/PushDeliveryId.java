package works.momens.server.notification.dispatch;

import java.io.Serializable;
import java.util.UUID;

record PushDeliveryId(long outboxEventId, UUID installationId) implements Serializable {}
