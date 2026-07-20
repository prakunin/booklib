package org.booklore.service.metadata.writer;

import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.service.ArchiveService;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIf("org.booklore.service.ArchiveService#isAvailable")
class CbxMetadataWriterTest {

    private CbxMetadataWriter writer;
    private Path tempDir;

    @BeforeEach
    void setup() throws Exception {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings settings = new AppSettings();
        MetadataPersistenceSettings persistence = new MetadataPersistenceSettings();
        MetadataPersistenceSettings.SaveToOriginalFile save = new MetadataPersistenceSettings.SaveToOriginalFile();
        MetadataPersistenceSettings.FormatSettings cbx = new MetadataPersistenceSettings.FormatSettings();
        cbx.setEnabled(true);
        cbx.setMaxFileSizeInMb(100);
        save.setCbx(cbx);
        persistence.setSaveToOriginalFile(save);
        settings.setMetadataPersistenceSettings(persistence);
        when(appSettingService.getAppSettings()).thenReturn(settings);

        writer = new CbxMetadataWriter(appSettingService, new ArchiveService());
        tempDir = Files.createTempDirectory("cbx_writer_test_");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception _) {
                            // best-effort cleanup, ignore failures
                        }
                    });
        }
    }

    @Test
    void getSupportedBookType_isCbx() {
        assertEquals(BookFileType.CBX, writer.getSupportedBookType());
    }

    @Test
    void saveMetadataToFile_cbz_updatesOrCreatesComicInfo_andPreservesOtherFiles() throws Exception {
        // Create a CBZ without ComicInfo.xml and with a couple of images
        File cbz = createCbz(tempDir.resolve("sample.cbz"), new String[]{
                "images/002.jpg", "images/001.jpg"
        });

        // Prepare metadata
        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("My Comic");
        meta.setDescription("Short desc");
        meta.setPublisher("Indie");
        meta.setSeriesName("Series X");
        meta.setSeriesNumber(2.5f);
        meta.setSeriesTotal(12);
        meta.setPublishedDate(LocalDate.of(2020, Month.JULY, 14));
        meta.setPageCount(42);
        meta.setLanguage("en");

        List<AuthorEntity> authors = new ArrayList<>();
        AuthorEntity aliceAuthor = new AuthorEntity();
        aliceAuthor.setId(1L);
        aliceAuthor.setName("Alice");
        AuthorEntity bobAuthor = new AuthorEntity();
        bobAuthor.setId(2L);
        bobAuthor.setName("Bob");
        authors.add(aliceAuthor);
        authors.add(bobAuthor);
        meta.setAuthors(authors);
        Set<CategoryEntity> cats = new HashSet<>();
        CategoryEntity actionCat = new CategoryEntity();
        actionCat.setId(1L);
        actionCat.setName("action");
        CategoryEntity adventureCat = new CategoryEntity();
        adventureCat.setId(2L);
        adventureCat.setName("adventure");
        cats.add(actionCat);
        cats.add(adventureCat);
        meta.setCategories(cats);

        // Execute
        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        // Assert ComicInfo.xml exists and contains our fields
        try (ZipFile zip = new ZipFile(cbz)) {
            ZipEntry ci = zip.getEntry("ComicInfo.xml");
            assertNotNull(ci, "ComicInfo.xml should be present after write");

            Document doc = parseXml(zip.getInputStream(ci));
            String title = text(doc, "Title");
            String summary = text(doc, "Summary");
            String publisher = text(doc, "Publisher");
            String series = text(doc, "Series");
            String number = text(doc, "Number");
            String count = text(doc, "Count");
            String year = text(doc, "Year");
            String month = text(doc, "Month");
            String day = text(doc, "Day");
            String pageCount = text(doc, "PageCount");
            String lang = text(doc, "LanguageISO");
            String writerEl = text(doc, "Writer");
            String genre = text(doc, "Genre");

            assertEquals("My Comic", title);
            assertEquals("Short desc", summary);
            assertEquals("Indie", publisher);
            assertEquals("Series X", series);
            assertEquals("2.5", number);
            assertEquals("12", count);
            assertEquals("2020", year);
            assertEquals("7", month);
            assertEquals("14", day);
            assertEquals("42", pageCount);
            assertEquals("en", lang);
            if (writerEl != null) {
                assertTrue(writerEl.contains("Alice"));
                assertTrue(writerEl.contains("Bob"));
            }
            if (genre != null) {
                assertTrue(genre.toLowerCase().contains("action"));
                assertTrue(genre.toLowerCase().contains("adventure"));
            }

            // Ensure original image entries are preserved
            assertNotNull(zip.getEntry("images/001.jpg"));
            assertNotNull(zip.getEntry("images/002.jpg"));
        }
    }

    @Test
    void saveMetadataToFile_cbz_writesTagsRatingAndWebField() throws Exception {
        File cbz = createCbz(tempDir.resolve("tags_rating.cbz"), new String[]{"page1.jpg"});

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Rating Test");
        meta.setRating(8.4);
        meta.setGoodreadsId("12345");
        meta.setAsin("B00TEST123");
        
        Set<TagEntity> tags = new HashSet<>();
        TagEntity tag1 = new TagEntity();
        tag1.setId(1L);
        tag1.setName("Fantasy");
        TagEntity tag2 = new TagEntity();
        tag2.setId(2L);
        tag2.setName("Epic");
        tags.add(tag1);
        tags.add(tag2);
        meta.setTags(tags);

        Set<MoodEntity> moods = new HashSet<>();
        MoodEntity mood = new MoodEntity();
        mood.setId(1L);
        mood.setName("Dark");
        moods.add(mood);
        meta.setMoods(moods);

        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(cbz)) {
            ZipEntry ci = zip.getEntry("ComicInfo.xml");
            assertNotNull(ci);
            Document doc = parseXml(zip.getInputStream(ci));

            String notesVal = text(doc, "Notes");
            assertNotNull(notesVal);
            assertTrue(notesVal.contains("[BookLore:Tags]"));
            assertTrue(notesVal.contains("Fantasy"));
            assertTrue(notesVal.contains("Epic"));

            // Tags now written as dedicated element per Anansi v2.1
            String tagsVal = text(doc, "Tags");
            assertNotNull(tagsVal, "Tags should be written as standalone element per Anansi v2.1");
            assertTrue(tagsVal.contains("Fantasy") || tagsVal.contains("Epic"), "Tags should contain Fantasy or Epic");

            String rating = text(doc, "CommunityRating");
            assertNotNull(rating);
            assertEquals("4.2", rating);

            String web = text(doc, "Web");
            assertNotNull(web);
            assertTrue(web.contains("goodreads.com"));
            // assertTrue(web.contains("amazon.com")); // Only primary URL is stored in Web field now

            String notes = text(doc, "Notes");
            assertNotNull(notes);
            assertTrue(notes.contains("[BookLore:Moods]"));
            assertTrue(notes.contains("Dark"));
        }
    }

    @Test
    void saveMetadataToFile_cbz_updatesExistingComicInfo() throws Exception {
        // Create a CBZ *with* an existing ComicInfo.xml
        Path out = tempDir.resolve("with_meta.cbz");
        String xml = """
                <ComicInfo>
                  <Title>Old Title</Title>
                  <Summary>Old Summary</Summary>
                </ComicInfo>""";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
            put(zos, "ComicInfo.xml", xml.getBytes(StandardCharsets.UTF_8));
            put(zos, "a.jpg", new byte[]{1});
        }

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("New Title");
        meta.setDescription("New Summary");

        writer.saveMetadataToFile(out.toFile(), meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(out.toFile())) {
            ZipEntry ci = zip.getEntry("ComicInfo.xml");
            Document doc = parseXml(zip.getInputStream(ci));
            assertEquals("New Title", text(doc, "Title"));
            assertEquals("New Summary", text(doc, "Summary"));
            // a.jpg should still exist
            assertNotNull(zip.getEntry("a.jpg"));
        }
    }

    @Test
    void saveMetadataToFile_ZipNamedAsCbr_ShouldUpdateMetadata() throws Exception {
        File zipAsCbr = createCbz(tempDir.resolve("mismatched.cbr"), new String[]{"page1.jpg"});
        Path zipAsCbz = tempDir.resolve("mismatched.cbz");

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Mismatched Title");

        writer.saveMetadataToFile(zipAsCbr, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(zipAsCbz.toFile())) {
            ZipEntry ci = zip.getEntry("ComicInfo.xml");
            assertNotNull(ci, "ComicInfo.xml should be present");
            Document doc = parseXml(zip.getInputStream(ci));
            assertEquals("Mismatched Title", text(doc, "Title"));
            assertNotNull(zip.getEntry("page1.jpg"));
        }
    }

    @Test
    void saveMetadataToFile_cbz_writesComicSpecificMetadata() throws Exception {
        File cbz = createCbz(tempDir.resolve("comic_meta.cbz"), new String[]{"page1.jpg"});

        // Create metadata with ComicMetadataEntity
        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Spider-Man #1");
        meta.setSeriesName("Spider-Man");
        meta.setSeriesNumber(1.0f);
        meta.setAgeRating(13); // Teen rating

        // Create ComicMetadataEntity with all comic-specific fields
        ComicMetadataEntity comic = ComicMetadataEntity.builder()
                .volumeNumber(2023)
                .alternateSeries("Amazing Spider-Man")
                .alternateIssue("700.1")
                .storyArc("Superior")
                .format("Single Issue")
                .imprint("Marvel Knights")
                .blackAndWhite(false)
                .manga(true)
                .readingDirection("RTL")
                .build();

        // Characters
        ComicCharacterEntity char1 = new ComicCharacterEntity();
        char1.setId(1L);
        char1.setName("Peter Parker");
        ComicCharacterEntity char2 = new ComicCharacterEntity();
        char2.setId(2L);
        char2.setName("Mary Jane");
        Set<ComicCharacterEntity> characters = new HashSet<>();
        characters.add(char1);
        characters.add(char2);
        comic.setCharacters(characters);

        // Teams
        ComicTeamEntity team1 = new ComicTeamEntity();
        team1.setId(1L);
        team1.setName("Avengers");
        Set<ComicTeamEntity> teams = new HashSet<>();
        teams.add(team1);
        comic.setTeams(teams);

        // Locations
        ComicLocationEntity loc1 = new ComicLocationEntity();
        loc1.setId(1L);
        loc1.setName("New York City");
        Set<ComicLocationEntity> locations = new HashSet<>();
        locations.add(loc1);
        comic.setLocations(locations);

        // Creators
        ComicCreatorEntity penciller = new ComicCreatorEntity();
        penciller.setId(1L);
        penciller.setName("John Romita Jr.");
        ComicCreatorEntity inker = new ComicCreatorEntity();
        inker.setId(2L);
        inker.setName("Klaus Janson");
        ComicCreatorMappingEntity pencillerMapping = ComicCreatorMappingEntity.builder()
                .creator(penciller)
                .role(ComicCreatorRole.PENCILLER)
                .comicMetadata(comic)
                .build();
        ComicCreatorMappingEntity inkerMapping = ComicCreatorMappingEntity.builder()
                .creator(inker)
                .role(ComicCreatorRole.INKER)
                .comicMetadata(comic)
                .build();
        Set<ComicCreatorMappingEntity> creatorMappings = new HashSet<>();
        creatorMappings.add(pencillerMapping);
        creatorMappings.add(inkerMapping);
        comic.setCreatorMappings(creatorMappings);

        meta.setComicMetadata(comic);

        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(cbz)) {
            ZipEntry ci = zip.getEntry("ComicInfo.xml");
            assertNotNull(ci, "ComicInfo.xml should be present");
            Document doc = parseXml(zip.getInputStream(ci));

            // Basic metadata
            assertEquals("Spider-Man #1", text(doc, "Title"));
            assertEquals("Spider-Man", text(doc, "Series"));

            // Comic-specific fields
            assertEquals("2023", text(doc, "Volume"));
            assertEquals("Amazing Spider-Man", text(doc, "AlternateSeries"));
            assertEquals("700.1", text(doc, "AlternateNumber"));
            assertEquals("Superior", text(doc, "StoryArc"));
            assertEquals("Single Issue", text(doc, "Format"));
            assertEquals("Marvel Knights", text(doc, "Imprint"));
            assertEquals("No", text(doc, "BlackAndWhite"));
            assertEquals("YesAndRightToLeft", text(doc, "Manga"));
            assertEquals("Teen", text(doc, "AgeRating"));

            // Characters, Teams, Locations
            String charactersStr = text(doc, "Characters");
            assertNotNull(charactersStr);
            assertTrue(charactersStr.contains("Peter Parker"));
            assertTrue(charactersStr.contains("Mary Jane"));

            String teamsStr = text(doc, "Teams");
            assertNotNull(teamsStr);
            assertTrue(teamsStr.contains("Avengers"));

            String locationsStr = text(doc, "Locations");
            assertNotNull(locationsStr);
            assertTrue(locationsStr.contains("New York City"));

            // Creators
            String pencillerStr = text(doc, "Penciller");
            assertNotNull(pencillerStr);
            assertTrue(pencillerStr.contains("John Romita Jr."));

            String inkerStr = text(doc, "Inker");
            assertNotNull(inkerStr);
            assertTrue(inkerStr.contains("Klaus Janson"));
        }
    }

    @Test
    void shouldSaveMetadataToFile_disabled_returnsFalse() throws Exception {
        File cbz = createCbz(tempDir.resolve("disabled.cbz"), new String[]{"page1.jpg"});
        configureCbxSettings(false, 100);

        assertFalse(writer.shouldSaveMetadataToFile(cbz));
    }

    @Test
    void shouldSaveMetadataToFile_oversized_returnsFalse() throws Exception {
        File cbz = createCbz(tempDir.resolve("oversized.cbz"), new String[]{"page1.jpg"});
        configureCbxSettings(true, -1);

        assertFalse(writer.shouldSaveMetadataToFile(cbz));
    }

    @Test
    void saveMetadataToFile_disabled_doesNotModifyFile() throws Exception {
        File cbz = createCbz(tempDir.resolve("skip_disabled.cbz"), new String[]{"page1.jpg"});
        configureCbxSettings(false, 100);
        byte[] before = Files.readAllBytes(cbz.toPath());

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Should not be written");
        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        assertArrayEquals(before, Files.readAllBytes(cbz.toPath()));
    }

    @Test
    void saveMetadataToFile_withNullClearFlags_doesNotThrowAndAppliesFields() throws Exception {
        File cbz = createCbz(tempDir.resolve("null_clear.cbz"), new String[]{"page1.jpg"});

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Null Clear Flags");
        meta.setDescription("Some description");

        writer.saveMetadataToFile(cbz, meta, null, null);

        try (ZipFile zip = new ZipFile(cbz)) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            assertEquals("Null Clear Flags", text(doc, "Title"));
            assertEquals("Some description", text(doc, "Summary"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "hcb1, cv1, gr1, asin1, https://hardcover.app/books/hcb1",
            "'', 4050-999, gr1, asin1, https://comicvine.gamespot.com/volume/4050-999/",
            "'', 4000-999, gr1, asin1, https://comicvine.gamespot.com/issue/4000-999/",
            "'', 12-34, gr1, asin1, https://comicvine.gamespot.com/issue/12-34/",
            "'', 12345, gr1, asin1, https://comicvine.gamespot.com/issue/4000-12345/",
            "'', '', gr1, asin1, https://www.goodreads.com/book/show/gr1",
            "'', '', '', asin1, https://www.amazon.com/dp/asin1",
            "'', '', '', '', ''"
    })
    void saveMetadataToFile_resolvesPrimaryWebUrlByPriority(String hardcoverBookId, String comicvineId, String goodreadsId, String asin, String expectedWeb) throws Exception {
        File cbz = createCbz(tempDir.resolve("web_" + System.nanoTime() + ".cbz"), new String[]{"page1.jpg"});

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Web Priority");
        meta.setHardcoverBookId(hardcoverBookId.isBlank() ? null : hardcoverBookId);
        meta.setComicvineId(comicvineId.isBlank() ? null : comicvineId);
        meta.setGoodreadsId(goodreadsId.isBlank() ? null : goodreadsId);
        meta.setAsin(asin.isBlank() ? null : asin);

        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(cbz)) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            assertEquals(expectedWeb.isBlank() ? null : expectedWeb, text(doc, "Web"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "18, Adults Only 18+",
            "17, Mature 17+",
            "15, MA15+",
            "13, Teen",
            "10, Everyone 10+",
            "6, Everyone",
            "3, Early Childhood"
    })
    void saveMetadataToFile_mapsAgeRatingLadder(int ageRating, String expected) throws Exception {
        File cbz = createCbz(tempDir.resolve("age_" + ageRating + ".cbz"), new String[]{"page1.jpg"});

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Age Rating");
        meta.setAgeRating(ageRating);

        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(cbz)) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            assertEquals(expected, text(doc, "AgeRating"));
        }
    }

    @Test
    void saveMetadataToFile_mangaExplicitlyFalse_writesNo() throws Exception {
        File cbz = createCbz(tempDir.resolve("manga_no.cbz"), new String[]{"page1.jpg"});

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Not Manga");
        ComicMetadataEntity comic = ComicMetadataEntity.builder().manga(false).build();
        meta.setComicMetadata(comic);

        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(cbz)) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            assertEquals("No", text(doc, "Manga"));
        }
    }

    @Test
    void saveMetadataToFile_writesRemainingCreatorRoles() throws Exception {
        File cbz = createCbz(tempDir.resolve("creator_roles.cbz"), new String[]{"page1.jpg"});

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Creator Roles");

        ComicMetadataEntity comic = new ComicMetadataEntity();
        ComicCreatorEntity colorist = new ComicCreatorEntity();
        colorist.setId(1L);
        colorist.setName("Cody Colorist");
        ComicCreatorEntity letterer = new ComicCreatorEntity();
        letterer.setId(2L);
        letterer.setName("Larry Letterer");
        ComicCreatorEntity coverArtist = new ComicCreatorEntity();
        coverArtist.setId(3L);
        coverArtist.setName("Cara CoverArtist");
        ComicCreatorEntity editor = new ComicCreatorEntity();
        editor.setId(4L);
        editor.setName("Eddie Editor");

        Set<ComicCreatorMappingEntity> mappings = new HashSet<>();
        mappings.add(ComicCreatorMappingEntity.builder().creator(colorist).role(ComicCreatorRole.COLORIST).comicMetadata(comic).build());
        mappings.add(ComicCreatorMappingEntity.builder().creator(letterer).role(ComicCreatorRole.LETTERER).comicMetadata(comic).build());
        mappings.add(ComicCreatorMappingEntity.builder().creator(coverArtist).role(ComicCreatorRole.COVER_ARTIST).comicMetadata(comic).build());
        mappings.add(ComicCreatorMappingEntity.builder().creator(editor).role(ComicCreatorRole.EDITOR).comicMetadata(comic).build());
        comic.setCreatorMappings(mappings);
        meta.setComicMetadata(comic);

        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(cbz)) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            assertEquals("Cody Colorist", text(doc, "Colorist"));
            assertEquals("Larry Letterer", text(doc, "Letterer"));
            assertEquals("Cara CoverArtist", text(doc, "CoverArtist"));
            assertEquals("Eddie Editor", text(doc, "Editor"));
        }
    }

    @Test
    void saveMetadataToFile_noCreatorMappings_leavesRoleFieldsUnset() throws Exception {
        File cbz = createCbz(tempDir.resolve("no_creator_mappings.cbz"), new String[]{"page1.jpg"});

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("No Creators");
        meta.setComicMetadata(ComicMetadataEntity.builder().build());

        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(cbz)) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            assertNull(text(doc, "Penciller"));
        }
    }

    @Test
    void saveMetadataToFile_wholeNumberSeriesNumber_writesWithoutDecimal() throws Exception {
        File cbz = createCbz(tempDir.resolve("whole_number.cbz"), new String[]{"page1.jpg"});

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Whole Number");
        meta.setSeriesNumber(3.0f);

        writer.saveMetadataToFile(cbz, meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(cbz)) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            assertEquals("3", text(doc, "Number"));
        }
    }

    @Test
    void saveMetadataToFile_preservesNonBookloreNotesLines() throws Exception {
        Path out = tempDir.resolve("preserve_notes.cbz");
        String xml = """
                <ComicInfo>
                  <Title>Old Title</Title>
                  <Notes>A custom curator note
                [BookLore:Tags] old-tag</Notes>
                </ComicInfo>""";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
            put(zos, "ComicInfo.xml", xml.getBytes(StandardCharsets.UTF_8));
            put(zos, "page1.jpg", new byte[]{1});
        }

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("New Title");
        TagEntity tag = new TagEntity();
        tag.setId(1L);
        tag.setName("new-tag");
        meta.setTags(Set.of(tag));

        writer.saveMetadataToFile(out.toFile(), meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(out.toFile())) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            String notes = text(doc, "Notes");
            assertNotNull(notes);
            assertTrue(notes.contains("A custom curator note"));
            assertTrue(notes.contains("[BookLore:Tags] new-tag"));
            assertFalse(notes.contains("old-tag"));
        }
    }

    @Test
    void saveMetadataToFile_malformedExistingComicInfo_fallsBackToFreshComicInfo() throws Exception {
        Path out = tempDir.resolve("malformed.cbz");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
            put(zos, "ComicInfo.xml", "<ComicInfo><Title>Unterminated".getBytes(StandardCharsets.UTF_8));
            put(zos, "page1.jpg", new byte[]{1});
        }

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Recovered Title");

        assertDoesNotThrow(() -> writer.saveMetadataToFile(out.toFile(), meta, null, new MetadataClearFlags()));

        try (ZipFile zip = new ZipFile(out.toFile())) {
            Document doc = parseXml(zip.getInputStream(zip.getEntry("ComicInfo.xml")));
            assertEquals("Recovered Title", text(doc, "Title"));
        }
    }

    @Test
    void saveMetadataToFile_skipsUnsafeZipSlipEntryNames() throws Exception {
        Path out = tempDir.resolve("zipslip.cbz");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
            put(zos, "page1.jpg", new byte[]{1, 2, 3});
            put(zos, "../evil.txt", new byte[]{9});
        }

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Zip Slip Guard");

        writer.saveMetadataToFile(out.toFile(), meta, null, new MetadataClearFlags());

        try (ZipFile zip = new ZipFile(out.toFile())) {
            assertNotNull(zip.getEntry("page1.jpg"));
            assertNull(zip.getEntry("../evil.txt"));
        }
    }

    @Test
    void saveMetadataToFile_missingParentDirectory_doesNotThrow() {
        File missingParent = new File(tempDir.resolve("no-such-dir").toFile(), "ghost.cbz");

        BookMetadataEntity meta = new BookMetadataEntity();
        meta.setTitle("Ghost");

        assertDoesNotThrow(() -> writer.saveMetadataToFile(missingParent, meta, null, new MetadataClearFlags()));
    }

    private void configureCbxSettings(boolean enabled, int maxFileSizeInMb) {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings settings = new AppSettings();
        MetadataPersistenceSettings persistence = new MetadataPersistenceSettings();
        MetadataPersistenceSettings.SaveToOriginalFile save = new MetadataPersistenceSettings.SaveToOriginalFile();
        MetadataPersistenceSettings.FormatSettings cbx = new MetadataPersistenceSettings.FormatSettings();
        cbx.setEnabled(enabled);
        cbx.setMaxFileSizeInMb(maxFileSizeInMb);
        save.setCbx(cbx);
        persistence.setSaveToOriginalFile(save);
        settings.setMetadataPersistenceSettings(persistence);
        when(appSettingService.getAppSettings()).thenReturn(settings);
        writer = new CbxMetadataWriter(appSettingService, new ArchiveService());
    }

    // ------------- helpers -------------

    private static File createCbz(Path path, String[] imageNames) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(path.toFile()))) {
            for (String name : imageNames) {
                put(zos, name, new byte[]{1, 2, 3});
            }
        }
        return path.toFile();
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws Exception {
        ZipEntry ze = new ZipEntry(name);
        ze.setTime(0L);
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }

    private static Document parseXml(InputStream is) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(is);
    }

    private static String text(Document doc, String tag) {
        var list = doc.getElementsByTagName(tag);
        if (list.getLength() == 0) return null;
        return list.item(0).getTextContent();
    }
}