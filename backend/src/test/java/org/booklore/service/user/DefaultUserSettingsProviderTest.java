package org.booklore.service.user;

import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.settings.UserSettingKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultUserSettingsProviderTest {

    @Test
    void defaultsNewUsersToCollapsedSeries() {
        DefaultUserSettingsProvider provider = new DefaultUserSettingsProvider();
        provider.init();

        BookLoreUser.UserSettings.EntityViewPreferences preferences =
                (BookLoreUser.UserSettings.EntityViewPreferences) provider.getDefaultValue(
                        UserSettingKey.ENTITY_VIEW_PREFERENCES);

        assertThat(preferences.getGlobal().getSeriesCollapsed()).isTrue();
    }
}
