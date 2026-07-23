package org.booklore.task.tasks;

import org.booklore.app.service.AppBookService;
import org.booklore.app.service.LibraryStatsRecomputeCoordinator;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Recomputes the materialized per-library facet counts that back the filter-options panel. Only
 * libraries whose books changed since their last recompute are refreshed (dirty-flag sweep), so an
 * idle catalog costs nothing. Each library is recomputed in its own transaction via
 * {@link AppBookService#recomputeLibraryFacetCounts(Long)} so one library's failure does not roll
 * back the others.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FacetCountRecomputeTask implements Task {

    private final AppBookService appBookService;
    private final LibraryStatsRecomputeCoordinator statsRecomputeCoordinator;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .taskType(getTaskType());

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());

        try {
            List<Long> dirty = appBookService.findDirtyLibraryIds();
            int recomputed = 0;
            for (Long libraryId : dirty) {
                if (recomputeQuietly(libraryId)) {
                    recomputed++;
                }
            }
            log.info("{}: Recomputed facet counts for {} of {} dirty librar{}",
                    getTaskType(), recomputed, dirty.size(), dirty.size() == 1 ? "y" : "ies");
            recomputeStats();
            builder.status(TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("{}: Error recomputing facet counts", getTaskType(), e);
            builder.status(TaskStatus.FAILED);
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        return builder.build();
    }

    private boolean recomputeQuietly(Long libraryId) {
        try {
            appBookService.recomputeLibraryFacetCounts(libraryId);
            return true;
        } catch (Exception e) {
            log.error("{}: Failed to recompute facet counts for library {}", getTaskType(), libraryId, e);
            return false;
        }
    }

    // Sweeps the materialized statistics alongside the facets: each dirty library is recomputed under
    // the coordinator's per-library lock, then the whole-catalog scope once, and the caches are
    // invalidated so the fresh rows are served. Uses its own dirty state, independent of the facets.
    private void recomputeStats() {
        List<Long> dirty = appBookService.findDirtyStatLibraryIds();
        int recomputed = 0;
        for (Long libraryId : dirty) {
            if (recomputeStatsQuietly(libraryId)) {
                recomputed++;
            }
        }
        if (recomputed > 0) {
            try {
                statsRecomputeCoordinator.recomputeCatalog();
            } catch (Exception e) {
                log.error("{}: Failed to recompute catalog statistics", getTaskType(), e);
            }
            appBookService.invalidateStatsCaches();
        }
        log.info("{}: Recomputed statistics for {} of {} dirty librar{}",
                getTaskType(), recomputed, dirty.size(), dirty.size() == 1 ? "y" : "ies");
    }

    private boolean recomputeStatsQuietly(Long libraryId) {
        try {
            return statsRecomputeCoordinator.recomputeLibrary(libraryId);
        } catch (Exception e) {
            log.error("{}: Failed to recompute statistics for library {}", getTaskType(), libraryId, e);
            return false;
        }
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.RECOMPUTE_FACET_COUNTS;
    }
}
