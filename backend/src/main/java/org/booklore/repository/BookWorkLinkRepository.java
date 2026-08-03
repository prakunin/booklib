package org.booklore.repository;

import org.booklore.model.entity.BookWorkLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookWorkLinkRepository extends JpaRepository<BookWorkLinkEntity, Long> {
}
