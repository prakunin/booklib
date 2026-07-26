package org.booklore.model.dto.response;

import org.booklore.model.enums.TaskType;

import java.time.Instant;
import java.util.List;

public record TaskOverviewResponse(
        List<ActiveTask> activeTasks,
        List<ScheduledTask> scheduledTasks) {

    public record ActiveTask(
            String taskId,
            TaskType taskType,
            Instant startedAt) {
    }

    public record ScheduledTask(
            TaskType taskType,
            String cronExpression,
            Instant nextRunAt) {
    }
}
