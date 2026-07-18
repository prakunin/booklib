package org.booklore.service;

import jakarta.persistence.EntityManager;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.websocket.Topic;
import org.booklore.service.event.BookCatalogChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationServiceTest {

    private SimpMessagingTemplate messagingTemplate;
    private AuthenticationService authenticationService;
    private EntityManager entityManager;
    private ApplicationEventPublisher eventPublisher;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        authenticationService = mock(AuthenticationService.class);
        entityManager = mock(EntityManager.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        notificationService = new NotificationService(messagingTemplate, authenticationService, entityManager, eventPublisher);
    }

    @Test
    void sendMessagePublishesCatalogChangedEventForBookMutationTopic() {
        notificationService.sendMessage(Topic.BOOK_ADD, new Object());

        ArgumentCaptor<BookCatalogChangedEvent> event = ArgumentCaptor.forClass(BookCatalogChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().topic()).isEqualTo(Topic.BOOK_ADD);
    }

    @Test
    void sendMessageDoesNotPublishCatalogChangedEventForUnrelatedTopic() {
        notificationService.sendMessage(Topic.LOG, new Object());

        verifyNoInteractions(eventPublisher);
    }
}
