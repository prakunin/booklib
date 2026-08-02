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
        List<String> entries = List.of(entry);

        assertThatThrownBy(() -> NestedArchiveLocator.encode(entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");
    }

    @Test
    void roundTripsANestedChainAndReportsItsShape() {
        List<String> entries = List.of("outer.zip", "middle.7z", "book.fb2");

        String locator = NestedArchiveLocator.encode(entries);

        assertThat(NestedArchiveLocator.decode(locator)).containsExactlyElementsOf(entries);
        assertThat(NestedArchiveLocator.isNested(locator)).isTrue();
        assertThat(NestedArchiveLocator.isNested(locator, "book.fb2")).isTrue();
        assertThat(NestedArchiveLocator.isNested(locator, locator)).isFalse();
    }

    @Test
    void rejectsInvalidEncodeChains() {
        List<String> empty = List.of();
        List<String> tooMany = List.of("1", "2", "3", "4", "5", "6", "7");
        List<String> invalid = List.of("outer.zip", "\0");

        assertThatThrownBy(() -> NestedArchiveLocator.encode(empty)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NestedArchiveLocator.encode(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NestedArchiveLocator.encode(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeDirectAndNestedLocators() {
        String malformedDirect = "direct:v1:not-base64!";
        String oneSegmentNested = "nested:v1:" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("outer.zip".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String oversized = "x".repeat(NestedArchiveLocator.MAX_LENGTH + 1);

        assertThatThrownBy(() -> NestedArchiveLocator.decode(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> NestedArchiveLocator.decode("\0")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> NestedArchiveLocator.decode(malformedDirect)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> NestedArchiveLocator.decode(oneSegmentNested)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> NestedArchiveLocator.decode(oversized)).isInstanceOf(RuntimeException.class);
    }
}
