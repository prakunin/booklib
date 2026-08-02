package org.booklore.service.inpx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("java:S5976")
class InpxFilenameMetadataParserTest {

    private final InpxFilenameMetadataParser parser = new InpxFilenameMetadataParser();

    @Test
    void splitsSlavicThreePartNameByPatronymic() {
        InpxFilenameMetadataParser.ParsedName parsed =
                parser.parse("Mark_Semyonovich_Solonin_23_iyunya._Den_M.zip");

        assertThat(parsed.author()).isEqualTo("Mark Semyonovich Solonin");
        assertThat(parsed.title()).isEqualTo("23 iyunya. Den M");
    }

    @Test
    void splitsUkrainianThreePartNameByPatronymic() {
        InpxFilenameMetadataParser.ParsedName parsed =
                parser.parse("Vsevolod_Zinovevich_Nestayko_Charivni_okulyari.fb2");

        assertThat(parsed.author()).isEqualTo("Vsevolod Zinovevich Nestayko");
        assertThat(parsed.title()).isEqualTo("Charivni okulyari");
    }

    @Test
    void takesTwoTokensForAWesternNameWithoutPatronymic() {
        InpxFilenameMetadataParser.ParsedName parsed =
                parser.parse("Megan_Lindholm_Silver_Lady_And_The_Fortyish_Man.zip");

        assertThat(parsed.author()).isEqualTo("Megan Lindholm");
        assertThat(parsed.title()).isEqualTo("Silver Lady And The Fortyish Man");
    }

    @Test
    void treatsLeadingUnderscoreAsPeriodicalWithNoAuthor() {
        InpxFilenameMetadataParser.ParsedName parsed =
                parser.parse("_zhurnal_Radio_Radio_1972_10.djvu");

        assertThat(parsed.author()).isNull();
        assertThat(parsed.title()).isEqualTo("zhurnal Radio Radio 1972 10");
    }

    @Test
    void doesNotInventAnAuthorWhenTheSecondTokenIsNotAName() {
        InpxFilenameMetadataParser.ParsedName parsed = parser.parse("Radio_1972_10.djvu");

        assertThat(parsed.author()).isNull();
        assertThat(parsed.title()).isEqualTo("Radio 1972 10");
    }

    @Test
    void keepsASingleTokenAsTitleOnly() {
        InpxFilenameMetadataParser.ParsedName parsed = parser.parse("Foobar.pdf");

        assertThat(parsed.author()).isNull();
        assertThat(parsed.title()).isEqualTo("Foobar");
    }
}
