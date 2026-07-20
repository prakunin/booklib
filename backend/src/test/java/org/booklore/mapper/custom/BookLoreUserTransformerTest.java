package org.booklore.mapper.custom;

import org.booklore.mapper.LibraryMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.entity.UserSettingEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookLoreUserTransformerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LibraryMapper libraryMapper = mock(LibraryMapper.class);
    private final BookLoreUserTransformer transformer = new BookLoreUserTransformer(objectMapper, libraryMapper);

    private BookLoreUserEntity baseEntity() {
        BookLoreUserEntity entity = new BookLoreUserEntity();
        entity.setId(1L);
        entity.setUsername("reader1");
        entity.setName("Reader One");
        entity.setEmail("reader1@example.com");
        entity.setLocale("en");
        entity.setTheme("dark");
        entity.setThemeAccent("blue");
        entity.setThemeSyncEnabled(true);
        entity.setUiFont("default");
        entity.setDefaultPassword(false);
        entity.setSettings(new HashSet<>());
        UserPermissionsEntity permissions = new UserPermissionsEntity();
        permissions.setPermissionAdmin(true);
        permissions.setPermissionUpload(true);
        entity.setPermissions(permissions);
        return entity;
    }

    private UserSettingEntity setting(String key, String value) {
        return UserSettingEntity.builder().settingKey(key).settingValue(value).build();
    }

    @Nested
    @DisplayName("basic field and permission mapping")
    class BasicFieldMapping {

        @Test
        void mapsScalarFieldsAndPermissions() {
            BookLoreUser dto = transformer.toDTO(baseEntity());

            assertThat(dto.getId()).isEqualTo(1L);
            assertThat(dto.getUsername()).isEqualTo("reader1");
            assertThat(dto.getName()).isEqualTo("Reader One");
            assertThat(dto.getEmail()).isEqualTo("reader1@example.com");
            assertThat(dto.getLocale()).isEqualTo("en");
            assertThat(dto.getTheme()).isEqualTo("dark");
            assertThat(dto.getThemeAccent()).isEqualTo("blue");
            assertThat(dto.isThemeSyncEnabled()).isTrue();
            assertThat(dto.getUiFont()).isEqualTo("default");
            assertThat(dto.isDefaultPassword()).isFalse();
            assertThat(dto.getPermissions().isAdmin()).isTrue();
            assertThat(dto.getPermissions().isCanUpload()).isTrue();
            assertThat(dto.getPermissions().isCanDownload()).isFalse();
        }

        @Test
        void nullLibraries_resultsInEmptyAssignedLibraries() {
            BookLoreUserEntity entity = baseEntity();
            entity.setLibraries(null);

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getAssignedLibraries()).isEmpty();
        }

        @Test
        void presentLibraries_areMappedThroughLibraryMapper() {
            BookLoreUserEntity entity = baseEntity();
            LibraryEntity libraryEntity = new LibraryEntity();
            libraryEntity.setId(5L);
            entity.setLibraries(Set.of(libraryEntity));
            Library mappedLibrary = Library.builder().id(5L).name("Main").build();
            when(libraryMapper.toLibrary(libraryEntity)).thenReturn(mappedLibrary);

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getAssignedLibraries()).containsExactly(mappedLibrary);
        }
    }

    @Nested
    @DisplayName("JSON user settings")
    class JsonUserSettings {

        @Test
        void perBookSetting_deserializesNestedEnum() {
            BookLoreUserEntity entity = baseEntity();
            entity.setSettings(Set.of(setting("perBookSetting", "{\"pdf\":\"Individual\",\"epub\":\"Global\"}")));

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getUserSettings().getPerBookSetting().getPdf())
                    .isEqualTo(BookLoreUser.UserSettings.PerBookSetting.GlobalOrIndividual.Individual);
            assertThat(dto.getUserSettings().getPerBookSetting().getEpub())
                    .isEqualTo(BookLoreUser.UserSettings.PerBookSetting.GlobalOrIndividual.Global);
        }

        @Test
        void sidebarLibrarySorting_deserializesSidebarSortOption() {
            BookLoreUserEntity entity = baseEntity();
            entity.setSettings(Set.of(setting("sidebarLibrarySorting", "{\"field\":\"name\",\"order\":\"asc\"}")));

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getUserSettings().getSidebarLibrarySorting().getField()).isEqualTo("name");
            assertThat(dto.getUserSettings().getSidebarLibrarySorting().getOrder()).isEqualTo("asc");
        }

        @Test
        void tableColumnPreference_deserializesListViaTypeReference() {
            BookLoreUserEntity entity = baseEntity();
            entity.setSettings(Set.of(setting("tableColumnPreference",
                    "[{\"field\":\"title\",\"visible\":true,\"order\":0}]")));

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getUserSettings().getTableColumnPreference()).hasSize(1);
            assertThat(dto.getUserSettings().getTableColumnPreference().getFirst().getField()).isEqualTo("title");
        }

        @Test
        void visibleFiltersAndSortFields_deserializeAsStringLists() {
            BookLoreUserEntity entity = baseEntity();
            entity.setSettings(Set.of(
                    setting("visibleFilters", "[\"author\",\"tag\"]"),
                    setting("visibleSortFields", "[\"title\"]")));

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getUserSettings().getVisibleFilters()).containsExactly("author", "tag");
            assertThat(dto.getUserSettings().getVisibleSortFields()).containsExactly("title");
        }

        @Test
        void dashboardConfig_deserializesNestedScrollerList() {
            BookLoreUserEntity entity = baseEntity();
            entity.setSettings(Set.of(setting("dashboardConfig",
                    "{\"scrollers\":[{\"id\":\"recent\",\"type\":\"RECENT\",\"title\":\"Recent\",\"enabled\":true,\"order\":0}]}")));

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getUserSettings().getDashboardConfig().getScrollers()).hasSize(1);
            assertThat(dto.getUserSettings().getDashboardConfig().getScrollers().getFirst().getId()).isEqualTo("recent");
        }
    }

    @Nested
    @DisplayName("scalar user settings")
    class ScalarUserSettings {

        @Test
        void mapsAllScalarSettingKeys() {
            BookLoreUserEntity entity = baseEntity();
            entity.setSettings(Set.of(
                    setting("filterMode", "AND"),
                    setting("filterSortingMode", "ALPHABETICAL"),
                    setting("metadataCenterViewMode", "GRID"),
                    setting("enableSeriesView", "true"),
                    setting("autoSaveMetadata", "true")));

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getUserSettings().getFilterMode()).isEqualTo("AND");
            assertThat(dto.getUserSettings().getFilterSortingMode()).isEqualTo("ALPHABETICAL");
            assertThat(dto.getUserSettings().getMetadataCenterViewMode()).isEqualTo("GRID");
            assertThat(dto.getUserSettings().isEnableSeriesView()).isTrue();
            assertThat(dto.getUserSettings().isAutoSaveMetadata()).isTrue();
        }
    }

    @Nested
    @DisplayName("malformed or unknown settings are tolerated")
    class ErrorTolerance {

        @Test
        void unknownSettingKey_isSkippedWithoutThrowing() {
            BookLoreUserEntity entity = baseEntity();
            entity.setSettings(Set.of(setting("someLegacyRemovedKey", "value")));

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getUserSettings()).isNotNull();
        }

        @Test
        void malformedJson_isSkippedWithoutThrowing() {
            BookLoreUserEntity entity = baseEntity();
            entity.setSettings(Set.of(setting("perBookSetting", "{not-valid-json")));

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getUserSettings().getPerBookSetting()).isNull();
        }

        @Test
        void nullPermissions_areIgnoredByUserPermissionCopy() {
            BookLoreUserEntity entity = baseEntity();
            entity.setPermissions(null);

            BookLoreUser dto = transformer.toDTO(entity);

            assertThat(dto.getPermissions().isAdmin()).isFalse();
        }

        @Test
        void emptyLibraries_neverInvokesLibraryMapper() {
            BookLoreUserEntity entity = baseEntity();
            entity.setLibraries(Set.of());

            transformer.toDTO(entity);

            verify(libraryMapper, never()).toLibrary(org.mockito.ArgumentMatchers.any());
        }
    }
}
