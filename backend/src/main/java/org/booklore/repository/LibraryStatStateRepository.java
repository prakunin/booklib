package org.booklore.repository;

import org.booklore.model.entity.LibraryStatStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface LibraryStatStateRepository extends JpaRepository<LibraryStatStateEntity, Long> {

    long countByLibraryIdIn(Collection<Long> libraryIds);
}
