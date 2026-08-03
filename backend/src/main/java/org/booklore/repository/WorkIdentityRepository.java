package org.booklore.repository;

import org.booklore.model.entity.WorkIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkIdentityRepository extends JpaRepository<WorkIdentityEntity, Long> {

    Optional<WorkIdentityEntity> findByWorkKey(String workKey);
}
