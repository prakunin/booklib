package org.booklore.task.tasks;

import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.inpx.ArchivedBookContentService;
import org.booklore.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Keeps the INPX extraction cache under its configured ceiling.
 * <p>
 * Books stored inside INPX archives have to be extracted somewhere before they can be read, and
 * that copy is kept so the next read is free. Nothing removed it: on this deployment the cache
 * reached 475 GB of 414 000 extracted files against 894 GB of archives, and a full library scan
 * would have taken it past the size of the disk.
 * <p>
 * The sweep runs here rather than inside each extraction because the cache is a deep per-book tree
 * - walking it on every book opened would make reading cost a full-cache stat. The PDF rendition
 * cache evicts inline instead, which it can afford: it holds a handful of files in one directory.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InpxExtractionCacheCleanupTask implements Task {

    private final ArchivedBookContentService archivedBookContentService;
    private final AppSettingService appSettingService;

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
            Integer limitMb = appSettingService.getAppSettings().getInpxExtractionCacheSizeInMb();
            if (limitMb == null || limitMb <= 0) {
                log.info("{}: No cache limit configured, nothing to do", getTaskType());
            } else {
                var result = archivedBookContentService.evictBeyondCacheLimit(limitMb * 1024L * 1024L);
                log.info("{}: Evicted {} extracted books, freeing {} MB to stay under the {} MB limit",
                        getTaskType(), result.deletedFiles(), result.freedBytes() / (1024 * 1024), limitMb);
            }
            builder.status(TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("{}: Error sweeping the INPX extraction cache", getTaskType(), e);
            builder.status(TaskStatus.FAILED);
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        return builder.build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.CLEANUP_INPX_EXTRACTION_CACHE;
    }
}
