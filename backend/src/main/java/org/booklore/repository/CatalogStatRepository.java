package org.booklore.repository;

import org.booklore.model.entity.CatalogStatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogStatRepository extends JpaRepository<CatalogStatEntity, String> {
}
