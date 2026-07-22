package org.booklore.task.tasks;

import org.booklore.app.service.AppBookService;
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

    @Override
    public TaskType getTaskType() {
        return TaskType.RECOMPUTE_FACET_COUNTS;
    }
}
