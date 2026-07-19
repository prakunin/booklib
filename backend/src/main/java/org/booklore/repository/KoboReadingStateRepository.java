package org.booklore.repository;

import org.booklore.model.entity.KoboReadingStateEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface KoboReadingStateRepository extends JpaRepository<KoboReadingStateEntity, Long> {
    Optional<KoboReadingStateEntity> findByEntitlementIdAndUserId(String entitlementId, Long userId);
    Optional<KoboReadingStateEntity> findFirstByEntitlementIdAndUserIdIsNullOrderByPriorityTimestampDescLastModifiedStringDescIdDesc(
            String entitlementId);

    @Modifying
    @Query("DELETE FROM KoboReadingStateEntity state WHERE state.entitlementId IN :entitlementIds")
    void deleteByEntitlementIdIn(@Param("entitlementIds") Collection<String> entitlementIds);
}
