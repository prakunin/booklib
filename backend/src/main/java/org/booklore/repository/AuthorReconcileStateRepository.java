package org.booklore.repository;

import org.booklore.model.entity.AuthorReconcileStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthorReconcileStateRepository extends JpaRepository<AuthorReconcileStateEntity, Long> {
}
