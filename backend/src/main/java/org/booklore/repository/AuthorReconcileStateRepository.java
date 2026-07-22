package org.booklore.repository;

import org.booklore.model.entity.AuthorReconcileStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Set;

@Repository
public interface AuthorReconcileStateRepository extends JpaRepository<AuthorReconcileStateEntity, Long> {

    @Query("SELECT s.authorId FROM AuthorReconcileStateEntity s WHERE s.authorId IN :ids")
    Set<Long> findExistingAuthorIds(@Param("ids") Collection<Long> ids);
}
