package org.booklore.service.metadata.smart;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoodreadsUrlParserTest {

    @Test
    void extractsIdFromBookShowUrl() {
        assertThat(GoodreadsUrlParser.extractBookId("https://www.goodreads.com/book/show/104595.Montaigne_s_Travel_Journal"))
                .contains("104595");
    }

    @Test
    void extractsIdWithoutSlug() {
        assertThat(GoodreadsUrlParser.extractBookId("https://goodreads.com/book/show/104595")).contains("104595");
    }

    @Test
    void ignoresUrlsThatAreNotBookPages() {
        assertThat(GoodreadsUrlParser.extractBookId("https://www.goodreads.com/author/show/17241.Michel_de_Montaigne")).isEmpty();
        assertThat(GoodreadsUrlParser.extractBookId("https://example.com/book/show/104595")).isEmpty();
    }

    @Test
    void handlesMissingInput() {
        assertThat(GoodreadsUrlParser.extractBookId(null)).isEmpty();
        assertThat(GoodreadsUrlParser.extractBookId(" ")).isEmpty();
    }
}
