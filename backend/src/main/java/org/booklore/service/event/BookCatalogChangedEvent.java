package org.booklore.service.event;

import org.booklore.model.websocket.Topic;

public record BookCatalogChangedEvent(Topic topic) {
}
