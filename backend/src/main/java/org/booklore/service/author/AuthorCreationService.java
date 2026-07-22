package org.booklore.service.author;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.AuthorReconcileStateEntity;
import org.booklore.model.enums.AuthorReconcileState;
import org.booklore.repository.AuthorReconcileStateRepository;
import org.booklore.repository.AuthorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthorCreationService {

    private final AuthorRepository authorRepository;
    private final AuthorReconcileStateRepository reconcileStateRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthorEntity createInNewTransaction(String cleanedName, String normalizedName) {
        AuthorEntity saved = authorRepository.saveAndFlush(
                AuthorEntity.builder().name(cleanedName).normalizedName(normalizedName).build());
        reconcileStateRepository.saveAndFlush(AuthorReconcileStateEntity.builder()
                .authorId(saved.getId())
                .state(AuthorReconcileState.PENDING)
                .attemptCount(0)
                .build());
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AuthorEntity> findActiveByNameInNewTransaction(String name) {
        return authorRepository.findByName(name);
    }
}
