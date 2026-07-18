package org.booklore.service;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.enums.PermissionType;
import org.booklore.model.websocket.Topic;
import org.booklore.service.event.BookCatalogChangedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final AuthenticationService authenticationService;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public void sendMessage(Topic topic, Object message) {
        publishCatalogChangedEvent(topic);
        try {
            var user = authenticationService.getAuthenticatedUser();
            if (user == null) {
                log.warn("No authenticated user found. Message not sent: {}", topic);
                return;
            }
            String username = user.getUsername();
            messagingTemplate.convertAndSendToUser(username, topic.getPath(), message);
        } catch (Exception e) {
            log.error("Error sending message to topic {}: {}", topic, e.getMessage(), e);
        }
    }

    public void sendMessageToUser(String username, Topic topic, Object message) {
        publishCatalogChangedEvent(topic);
        try {
            messagingTemplate.convertAndSendToUser(username, topic.getPath(), message);
        } catch (Exception e) {
            log.error("Error sending message to user {} on topic {}: {}", username, topic, e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public void sendMessageToPermissions(Topic topic, Object message, Set<PermissionType> permissionTypes) {
        if (permissionTypes == null || permissionTypes.isEmpty()) return;
        publishCatalogChangedEvent(topic);

        try {
            List<String> usernames = findUsernamesWithPermissions(permissionTypes);
            for (String username : usernames) {
                messagingTemplate.convertAndSendToUser(username, topic.getPath(), message);
            }
        } catch (Exception e) {
            log.error("Error sending message to users with permissions {}: {}", permissionTypes, e.getMessage(), e);
        }
    }

    private List<String> findUsernamesWithPermissions(Set<PermissionType> permissionTypes) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<BookLoreUserEntity> user = query.from(BookLoreUserEntity.class);
        Join<?, ?> perms = user.join("permissions");

        Predicate[] predicates = permissionTypes.stream()
                .map(p -> cb.isTrue(perms.get(p.getEntityField())))
                .toArray(Predicate[]::new);

        query.select(user.get("username")).where(cb.or(predicates));
        return entityManager.createQuery(query).getResultList();
    }

    private void publishCatalogChangedEvent(Topic topic) {
        if (topic == Topic.BOOK_ADD
                || topic == Topic.BOOK_UPDATE
                || topic == Topic.BOOKS_REMOVE
                || topic == Topic.BOOK_METADATA_UPDATE
                || topic == Topic.BOOK_METADATA_BATCH_UPDATE) {
            eventPublisher.publishEvent(new BookCatalogChangedEvent(topic));
        }
    }
}
