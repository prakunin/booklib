package org.booklore.service.inpx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NestedArchiveLocatorTest {

    @Test
    void escapesAReservedPrefixOnADirectEntry() {
        String entry = "nested:v1:ordinary-book.fb2";

        String locator = NestedArchiveLocator.encode(List.of(entry));

        assertThat(locator).startsWith("direct:v1:");
        assertThat(NestedArchiveLocator.decode(locator)).containsExactly(entry);
        assertThat(NestedArchiveLocator.isNested(locator)).isFalse();
    }

    @Test
    void rejectsAnOversizedDirectEntry() {
        String entry = "a".repeat(NestedArchiveLocator.MAX_LENGTH + 1);

        assertThatThrownBy(() -> NestedArchiveLocator.encode(List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");
    }
}
