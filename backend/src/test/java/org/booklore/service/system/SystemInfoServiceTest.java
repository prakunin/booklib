package org.booklore.service.system;

import org.booklore.service.VersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemInfoServiceTest {

    @Mock
    private VersionService versionService;

    private SystemInfoService service;

    @BeforeEach
    void setUp() {
        service = new SystemInfoService(versionService);
    }

    @Nested
    class ApplicationBlock {

        @Test
        void reportsTheAppVersionFromVersionService() {
            when(versionService.getAppVersion()).thenReturn("v1.2.3");

            assertThat(service.getSystemInfo().getApplication().getVersion()).isEqualTo("v1.2.3");
        }

        @Test
        void reportsANonBlankSpringBootVersion() {
            assertThat(service.getSystemInfo().getApplication().getSpringBootVersion()).isNotBlank();
        }
    }

    @Nested
    class RuntimeBlock {

        @Test
        void reportsJvmFacts() {
            var runtime = service.getSystemInfo().getRuntime();

            assertThat(runtime.getJavaVersion()).isNotBlank();
            assertThat(runtime.getJavaVendor()).isNotBlank();
            assertThat(runtime.getJvmUptimeMillis()).isNotNegative();
            assertThat(runtime.getAvailableProcessors()).isPositive();
            assertThat(runtime.getHeapUsedBytes()).isPositive();
            assertThat(runtime.getHeapMaxBytes()).isNotZero();
        }
    }

    @Nested
    class OsBlock {

        @Test
        void reportsOsFacts() {
            var os = service.getSystemInfo().getOs();

            assertThat(os.getName()).isNotBlank();
            assertThat(os.getVersion()).isNotBlank();
            assertThat(os.getArch()).isNotBlank();
        }
    }
}
