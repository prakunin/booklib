package org.booklore.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorConfigTest {

    @Test
    void taskExecutorUsesSecurityContextTaskDecorator() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new TaskExecutorConfig().taskExecutor();

        assertThat(ReflectionTestUtils.getField(executor, "taskDecorator")).isNotNull();
    }
}
