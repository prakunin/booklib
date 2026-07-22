package org.booklore.repository;

import org.booklore.model.entity.AuthorAliasEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuthorAliasRepository extends JpaRepository<AuthorAliasEntity, Long> {

    List<AuthorAliasEntity> findByNormalizedAliasAndResolvableTrue(String normalizedAlias);
}
