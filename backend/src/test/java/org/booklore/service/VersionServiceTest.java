package org.booklore.service;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Properties;

import org.booklore.model.dto.ReleaseNote;
import org.booklore.model.dto.VersionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class VersionServiceTest {

    private VersionService service;
    private VersionService spyService;

    @BeforeEach
    void setUp() {
        service = new VersionService(new ObjectMapper(), buildPropertiesProvider(null));
        spyService = spy(service);
    }

    /**
     * Stands in for the optional BuildProperties bean: pass a version to emulate a build
     * that generated build-info.properties, or null to emulate one that did not.
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> buildPropertiesProvider(String version) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        BuildProperties properties = null;
        if (version != null) {
            Properties values = new Properties();
            values.setProperty("version", version);
            properties = new BuildProperties(values);
        }
        when(provider.getIfAvailable()).thenReturn(properties);
        return provider;
    }


    @Nested
    class GetAppVersionTests {

        @Test
        void usesBuildInfoVersionAndPrefixesSemanticVersions() {
            VersionService versioned =
                    new VersionService(new ObjectMapper(), buildPropertiesProvider("0.1.0"));

            assertThat(versioned.getAppVersion()).isEqualTo("v0.1.0");
        }

        @Test
        void keepsTheTagStyleLabelForVersionsCarryingALocalBuildNumber() {
            VersionService versioned =
                    new VersionService(new ObjectMapper(), buildPropertiesProvider("3.2.18+57"));

            assertThat(versioned.getAppVersion()).isEqualTo("v3.2.18+57");
        }

        @Test
        void leavesNonSemanticBuildInfoVersionUntouched() {
            VersionService versioned =
                    new VersionService(new ObjectMapper(), buildPropertiesProvider("0.1.0-rc1"));

            assertThat(versioned.getAppVersion()).isEqualTo("0.1.0-rc1");
        }

        @Test
        void fallsBackToDevelopmentWhenNoVersionSourceExists() {
            assertThat(service.getAppVersion()).isEqualTo("development");
        }
    }


    @Nested
    class VersionComparison {

        private Method cmp;

        @BeforeEach
        void init() throws Exception {
            cmp = VersionService.class
                    .getDeclaredMethod("isVersionGreater", String.class, String.class);
            cmp.setAccessible(true);
        }

        @Test
        void returnsTrueWhenMajorIncreases() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "2.0.0", "1.9.9"))
                    .isTrue();
        }

        @Test
        void returnsFalseWhenMajorDecreases() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "1.0.0", "2.0.0"))
                    .isFalse();
        }

        @Test
        void returnsTrueForPatchIncrease() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "1.0.1", "1.0.0"))
                    .isTrue();
        }

        @Test
        void returnsFalseForPatchDecrease() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "1.0.0", "1.0.1"))
                    .isFalse();
        }

        @Test
        void returnsFalseWhenEqual() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "1.2.3", "1.2.3"))
                    .isFalse();
        }

        @Test
        void handlesDifferentLengthVersions() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "1.2.0", "1.1"))
                    .isTrue();
            assertThat((Boolean) cmp.invoke(service, "1.0", "1.0.1"))
                    .isFalse();
        }

        @Test
        void ignoresPrefixAndSafelyHandlesInvalid() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "v1.10", "v1.9.9"))
                    .isTrue();
            assertThat((Boolean) cmp.invoke(service, "x.y", "1.0"))
                    .isFalse();
        }

        @Test
        void returnsFalseWhenVersion2IsNull() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "1.0.0", null))
                    .isFalse();
        }

        @Test
        void returnsFalseWhenVersion1IsNull() throws Exception {
            assertThat((Boolean) cmp.invoke(service, null, "1.0.0"))
                    .isFalse();
        }

        @Test
        void returnsFalseWhenBothVersionsNull() throws Exception {
            assertThat((Boolean) cmp.invoke(service, null, null))
                    .isFalse();
        }

        @Test
        void ignoresTheLocalBuildNumberOnTheCurrentVersion() throws Exception {
            assertThat((Boolean) cmp.invoke(service, "v3.2.19", "v3.2.18+57"))
                    .isTrue();
            assertThat((Boolean) cmp.invoke(service, "v3.2.18", "v3.2.18+57"))
                    .isFalse();
            assertThat((Boolean) cmp.invoke(service, "v3.2.17", "v3.2.18+57"))
                    .isFalse();
        }
    }


    @Nested
    class GetVersionInfoTests {

        @Test
        void includesAppAndLatestOnSuccess() {
            doReturn("v9.9.9")
                    .when(spyService)
                    .fetchLatestGitHubReleaseVersion();

            VersionInfo info = spyService.getVersionInfo();

            assertThat(info.getCurrent())
                    .isEqualTo(service.getAppVersion());
            assertThat(info.getLatest())
                    .isEqualTo("v9.9.9");
        }

        @Test
        void usesFallbackIfMissingPackageVersion() {
            doReturn("v9.9.9")
                    .when(spyService)
                    .fetchLatestGitHubReleaseVersion();

            VersionInfo info = spyService.getVersionInfo();

            // This instance is built with neither a BuildProperties bean nor a packaged
            // Implementation-Version manifest entry, so getAppVersion() falls back.
            assertThat(info.getCurrent())
                    .isEqualTo("development");
            assertThat(info.getLatest())
                    .isEqualTo("v9.9.9");
        }

        @Test
        void usesUnknownIfFetchFails() {
            doThrow(new RuntimeException("fail"))
                    .when(spyService)
                    .fetchLatestGitHubReleaseVersion();

            VersionInfo info = spyService.getVersionInfo();

            assertThat(info.getLatest())
                    .isEqualTo("unknown");
        }
    }


    @Nested
    class GetChangelogSinceCurrentVersionTests {

        // getAppVersion() consults the mocked BuildProperties provider, so it has to be
        // resolved before the stubbing call — calling it inside `.fetchReleaseNotesSince(...)`
        // touches a mock while Mockito is mid-stub and blows up as UnfinishedStubbing.
        private String currentVersion;

        @BeforeEach
        void resolveCurrentVersion() {
            currentVersion = service.getAppVersion();
        }

        @Test
        void returnsNotesWhenAvailable() {
            LocalDateTime fixedTime = LocalDateTime.of(2025, Month.JANUARY, 1, 12, 0, 0);
            ReleaseNote note = new ReleaseNote("v1.1", "n", "b", "u", fixedTime);

            doReturn(List.of(note))
                    .when(spyService)
                    .fetchReleaseNotesSince(currentVersion);

            List<ReleaseNote> result = spyService.getChangelogSinceCurrentVersion();
            assertThat(result).hasSize(1).containsExactly(note);
        }

        @Test
        void returnsEmptyListWhenNoNewReleases() {
            doReturn(List.of())
                    .when(spyService)
                    .fetchReleaseNotesSince(currentVersion);

            var result = spyService.getChangelogSinceCurrentVersion();
            assertThat(result).isEmpty();
        }
    }
}