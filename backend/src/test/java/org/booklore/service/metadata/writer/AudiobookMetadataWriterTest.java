package org.booklore.service.metadata.writer;

import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudiobookMetadataWriterTest {

    private AudiobookMetadataWriter writer;
    private BookMetadataEntity metadata;
    private AppSettingService appSettingService;
    private FileService fileService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        appSettingService = mock(AppSettingService.class);
        fileService = mock(FileService.class);
        configureAudiobookSettings(true, 100);

        writer = new AudiobookMetadataWriter(appSettingService, fileService);
        metadata = new BookMetadataEntity();
    }

    private void configureAudiobookSettings(boolean enabled, int maxFileSizeInMb) {
        MetadataPersistenceSettings.FormatSettings audiobookFormatSettings = MetadataPersistenceSettings.FormatSettings.builder()
                .enabled(enabled)
                .maxFileSizeInMb(maxFileSizeInMb)
                .build();
        MetadataPersistenceSettings.SaveToOriginalFile saveToOriginalFile = MetadataPersistenceSettings.SaveToOriginalFile.builder()
                .audiobook(audiobookFormatSettings)
                .build();
        MetadataPersistenceSettings metadataPersistenceSettings = new MetadataPersistenceSettings();
        metadataPersistenceSettings.setSaveToOriginalFile(saveToOriginalFile);

        AppSettings appSettings = mock(AppSettings.class);
        when(appSettings.getMetadataPersistenceSettings()).thenReturn(metadataPersistenceSettings);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);
    }

    private Tag tagWithBlankDefaults() {
        Tag tag = mock(Tag.class);
        when(tag.getFirst(any(FieldKey.class))).thenReturn("");
        return tag;
    }

    private AudioFile mockAudioFile(Tag tag) {
        AudioFile audioFile = mock(AudioFile.class);
        when(audioFile.getTagOrCreateAndSetDefault()).thenReturn(tag);
        return audioFile;
    }

    private BookEntity audiobookEntity(String fileName, boolean folderBased) {
        BookEntity book = new BookEntity();
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(tempDir.toString());
        book.setLibraryPath(libraryPath);
        BookFileEntity file = new BookFileEntity();
        file.setBook(book);
        file.setFileSubPath("");
        file.setFileName(fileName);
        file.setBookType(BookFileType.AUDIOBOOK);
        file.setFolderBased(folderBased);
        book.setBookFiles(List.of(file));
        return book;
    }

    @Nested
    @DisplayName("getSupportedBookType Tests")
    class SupportedTypeTests {
        @Test
        void returnsAudiobook() {
            assertThat(writer.getSupportedBookType()).isEqualTo(BookFileType.AUDIOBOOK);
        }
    }

    @Nested
    @DisplayName("shouldSaveMetadataToFile Tests")
    class ShouldSaveMetadataToFileTests {

        private File smallFile;

        @BeforeEach
        void createSmallFile() throws IOException {
            smallFile = tempDir.resolve("small-" + System.nanoTime() + ".mp3").toFile();
            Files.write(smallFile.toPath(), new byte[]{1, 2, 3});
        }

        @Test
        @DisplayName("Should return false when audiobook writing is disabled")
        void disabledReturnsFalse() {
            configureAudiobookSettings(false, 100);
            assertThat(writer.shouldSaveMetadataToFile(smallFile)).isFalse();
        }

        @Test
        @DisplayName("Should return false when the file exceeds the configured max size")
        void oversizedReturnsFalse() {
            configureAudiobookSettings(true, -1);
            assertThat(writer.shouldSaveMetadataToFile(smallFile)).isFalse();
        }

        @Test
        @DisplayName("Should return true when enabled and within size limits")
        void enabledWithinLimitsReturnsTrue() {
            configureAudiobookSettings(true, 100);
            assertThat(writer.shouldSaveMetadataToFile(smallFile)).isTrue();
        }
    }

    @Nested
    @DisplayName("Folder Cover Tests")
    class FolderCoverTests {

        @Test
        @DisplayName("Should write the folder cover when a thumbnail URL loads successfully")
        void writesCoverForFolderBasedAudiobook() throws Exception {
            Path folder = tempDir.resolve("audiobook-dir-" + System.nanoTime());
            Files.createDirectories(folder);
            when(fileService.downloadImageFromUrl(anyString())).thenReturn(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));

            writer.saveMetadataToFile(folder.toFile(), metadata, "https://example.com/thumb.jpg", new MetadataClearFlags());

            assertThat(Files.exists(folder.resolve("cover.png"))).isTrue();
        }

        @Test
        @DisplayName("Should not write a cover when the thumbnail URL is blank")
        void blankThumbnailWritesNothing() throws Exception {
            Path folder = tempDir.resolve("audiobook-dir-blank-" + System.nanoTime());
            Files.createDirectories(folder);

            writer.saveMetadataToFile(folder.toFile(), metadata, "  ", new MetadataClearFlags());

            try (var stream = Files.list(folder)) {
                assertThat(stream.count()).isZero();
            }
        }

        @Test
        @DisplayName("Should not write a cover when the thumbnail fails to load")
        void loadFailureWritesNothing() throws Exception {
            Path folder = tempDir.resolve("audiobook-dir-fail-" + System.nanoTime());
            Files.createDirectories(folder);
            when(fileService.downloadImageFromUrl(anyString())).thenThrow(new IOException("boom"));

            writer.saveMetadataToFile(folder.toFile(), metadata, "https://example.com/thumb.jpg", new MetadataClearFlags());

            try (var stream = Files.list(folder)) {
                assertThat(stream.count()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("saveCoverToFolder Tests")
    class SaveCoverToFolderTests {

        @Test
        @DisplayName("Should no-op for null/empty data or null folder path")
        void guardsAgainstInvalidInput() {
            assertDoesNotThrow(() -> writer.saveCoverToFolder(tempDir, null));
            assertDoesNotThrow(() -> writer.saveCoverToFolder(tempDir, new byte[0]));
            assertDoesNotThrow(() -> writer.saveCoverToFolder(null, new byte[]{1, 2, 3}));
        }

        @Test
        @DisplayName("Should delete existing covers and write the PNG variant for PNG data")
        void writesPngAndDeletesExistingCovers() throws IOException {
            Path folder = tempDir.resolve("cover-dir-" + System.nanoTime());
            Files.createDirectories(folder);
            Files.write(folder.resolve("cover.jpg"), new byte[]{9});
            Files.write(folder.resolve("folder.png"), new byte[]{9});

            byte[] pngSignature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            writer.saveCoverToFolder(folder, pngSignature);

            assertThat(Files.exists(folder.resolve("cover.png"))).isTrue();
            assertThat(Files.exists(folder.resolve("cover.jpg"))).isFalse();
            assertThat(Files.exists(folder.resolve("folder.png"))).isFalse();
        }

        @Test
        @DisplayName("Should write the JPG variant for non-PNG data")
        void writesJpgForNonPngData() throws IOException {
            Path folder = tempDir.resolve("cover-dir-jpg-" + System.nanoTime());
            Files.createDirectories(folder);

            writer.saveCoverToFolder(folder, new byte[]{1, 2, 3, 4});

            assertThat(Files.exists(folder.resolve("cover.jpg"))).isTrue();
        }
    }

    @Nested
    @DisplayName("Tag Field Writing Tests")
    class TagFieldWritingTests {

        @Test
        @DisplayName("Should set ALBUM and TITLE from metadata title")
        void setsAlbumAndTitle() throws Exception {
            metadata.setTitle("My Audiobook");
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.ALBUM, "My Audiobook");
            verify(tag).setField(FieldKey.TITLE, "My Audiobook");
        }

        @Test
        @DisplayName("Should set ALBUM_ARTIST and ARTIST from a single author")
        void setsAuthorFields() throws Exception {
            AuthorEntity author = new AuthorEntity();
            author.setId(1L);
            author.setName("Jane Austen");
            metadata.setAuthors(List.of(author));
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.ALBUM_ARTIST, "Jane Austen");
            verify(tag).setField(FieldKey.ARTIST, "Jane Austen");
        }

        @Test
        @DisplayName("Should set COMPOSER from narrator")
        void setsNarratorAsComposer() throws Exception {
            metadata.setNarrator("Stephen Fry");
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.COMPOSER, "Stephen Fry");
        }

        @Test
        @DisplayName("Should set COMMENT from description")
        void setsDescriptionAsComment() throws Exception {
            metadata.setDescription("A thrilling tale.");
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.COMMENT, "A thrilling tale.");
        }

        @Test
        @DisplayName("Should set RECORD_LABEL from publisher")
        void setsPublisherAsRecordLabel() throws Exception {
            metadata.setPublisher("Acme Audio");
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.RECORD_LABEL, "Acme Audio");
        }

        @Test
        @DisplayName("Should set YEAR from published date")
        void setsYearFromPublishedDate() throws Exception {
            metadata.setPublishedDate(LocalDate.of(2020, Month.JANUARY, 1));
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.YEAR, "2020");
        }

        @Test
        @DisplayName("Should set GENRE from categories")
        void setsGenreFromCategories() throws Exception {
            CategoryEntity fantasy = new CategoryEntity();
            fantasy.setId(1L);
            fantasy.setName("Fantasy");
            metadata.setCategories(Set.of(fantasy));
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.GENRE, "Fantasy");
        }

        @Test
        @DisplayName("Should set LANGUAGE from metadata language")
        void setsLanguage() throws Exception {
            metadata.setLanguage("en");
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.LANGUAGE, "en");
        }

        @Test
        @DisplayName("Should set GROUPING/TRACK/TRACK_TOTAL from series fields")
        void setsSeriesFields() throws Exception {
            metadata.setSeriesName("Harry Potter");
            metadata.setSeriesNumber(3.0f);
            metadata.setSeriesTotal(7);
            Tag tag = runSaveAndReturnTag();

            verify(tag).setField(FieldKey.GROUPING, "Harry Potter");
            verify(tag).setField(FieldKey.TRACK, "3");
            verify(tag).setField(FieldKey.TRACK_TOTAL, "7");
        }

        @Test
        @DisplayName("Should apply cover art when a thumbnail URL is provided")
        void appliesCoverArt() throws Exception {
            when(fileService.downloadImageFromUrl(anyString())).thenReturn(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
            Tag tag = runSaveAndReturnTag("https://example.com/thumb.jpg");

            verify(tag).deleteArtworkField();
            ArgumentCaptor<Artwork> captor = ArgumentCaptor.forClass(Artwork.class);
            verify(tag).setField(captor.capture());
            assertThat(captor.getValue().getBinaryData()).isNotEmpty();
            assertThat(captor.getValue().getMimeType()).isEqualTo("image/png");
        }

        @Test
        @DisplayName("Should clear fields and delete existing tag values when clear flags are set")
        void clearFlagsDeleteExistingValues() throws Exception {
            Tag tag = mock(Tag.class);
            when(tag.getFirst(any(FieldKey.class))).thenReturn("existing-value");

            MetadataClearFlags clear = new MetadataClearFlags();
            clear.setTitle(true);
            clear.setAuthors(true);
            clear.setDescription(true);
            clear.setPublisher(true);
            clear.setLanguage(true);

            runSave(tag, null, clear);

            verify(tag).deleteField(FieldKey.ALBUM);
            verify(tag).deleteField(FieldKey.TITLE);
            verify(tag).deleteField(FieldKey.ALBUM_ARTIST);
            verify(tag).deleteField(FieldKey.ARTIST);
            verify(tag).deleteField(FieldKey.COMMENT);
            verify(tag).deleteField(FieldKey.RECORD_LABEL);
            verify(tag).deleteField(FieldKey.LANGUAGE);
        }

        @Test
        @DisplayName("Should skip commit entirely when nothing changed")
        void noChangesSkipsCommit() throws Exception {
            File audioFile = tempDir.resolve("nochange-" + System.nanoTime() + ".mp3").toFile();
            Files.write(audioFile.toPath(), new byte[]{1, 2, 3});

            Tag tag = tagWithBlankDefaults();
            AudioFile mockedAudio = mockAudioFile(tag);

            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                mocked.when(() -> AudioFileIO.read(audioFile)).thenReturn(mockedAudio);

                writer.saveMetadataToFile(audioFile, metadata, null, new MetadataClearFlags());

                verify(mockedAudio, never()).commit();
            }
        }

        @Test
        @DisplayName("Should not throw and should clean up the backup file when reading the audio file fails")
        void readFailureDoesNotThrowAndCleansUpBackup() throws Exception {
            File audioFile = tempDir.resolve("broken-" + System.nanoTime() + ".mp3").toFile();
            Files.write(audioFile.toPath(), new byte[]{1, 2, 3});

            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                mocked.when(() -> AudioFileIO.read(audioFile)).thenThrow(new RuntimeException("corrupt"));

                assertDoesNotThrow(() -> writer.saveMetadataToFile(audioFile, metadata, null, new MetadataClearFlags()));

                assertThat(Files.exists(Path.of(audioFile.getPath() + ".bak"))).isFalse();
            }
        }

        private Tag runSaveAndReturnTag() throws Exception {
            return runSaveAndReturnTag(null);
        }

        private Tag runSaveAndReturnTag(String thumbnailUrl) throws Exception {
            Tag tag = tagWithBlankDefaults();
            runSave(tag, thumbnailUrl, new MetadataClearFlags());
            return tag;
        }

        private void runSave(Tag tag, String thumbnailUrl, MetadataClearFlags clear) throws Exception {
            File audioFile = tempDir.resolve("audio-" + System.nanoTime() + ".mp3").toFile();
            Files.write(audioFile.toPath(), new byte[]{1, 2, 3});
            AudioFile mockedAudio = mockAudioFile(tag);

            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                mocked.when(() -> AudioFileIO.read(audioFile)).thenReturn(mockedAudio);
                writer.saveMetadataToFile(audioFile, metadata, thumbnailUrl, clear);
            }
        }
    }

    @Nested
    @DisplayName("Cover Replacement Guard Clause Tests")
    class CoverReplacementGuardTests {

        @Test
        void nullBytesDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(null, null));
        }

        @Test
        void emptyBytesDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(audiobookEntity("a.mp3", false), new byte[0]));
        }

        @Test
        void noAudiobookFileReturnsWithoutThrowing() {
            BookEntity noFiles = new BookEntity();
            noFiles.setBookFiles(Collections.emptyList());
            assertDoesNotThrow(() -> writer.replaceCoverImageFromBytes(noFiles, new byte[]{1, 2, 3}));
        }

        @Test
        void nullUploadDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUpload(audiobookEntity("a.mp3", false), null));
        }

        @Test
        void emptyUploadDoesNotThrow() {
            MultipartFile empty = new MockMultipartFile("cover.png", "cover.png", "image/png", new byte[0]);
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUpload(audiobookEntity("a.mp3", false), empty));
        }

        @Test
        void nullUrlDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUrl(audiobookEntity("a.mp3", false), null));
        }

        @Test
        void blankUrlDoesNotThrow() {
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUrl(audiobookEntity("a.mp3", false), "   "));
        }

        @Test
        void urlLoadFailureDoesNotThrow() throws IOException {
            when(fileService.downloadImageFromUrl(anyString())).thenThrow(new IOException("boom"));
            assertDoesNotThrow(() -> writer.replaceCoverImageFromUrl(audiobookEntity("a.mp3", false), "https://example.com/cover.jpg"));
        }
    }

    @Nested
    @DisplayName("Cover Replacement Success Tests")
    class CoverReplacementSuccessTests {

        @Test
        @DisplayName("Should write directly to the folder for folder-based audiobooks")
        void folderBasedWritesToFolder() throws Exception {
            Path folder = tempDir.resolve("cover-replace-folder-" + System.nanoTime());
            Files.createDirectories(folder);
            BookEntity book = new BookEntity();
            LibraryPathEntity libraryPath = new LibraryPathEntity();
            libraryPath.setPath(tempDir.toString());
            book.setLibraryPath(libraryPath);
            BookFileEntity file = new BookFileEntity();
            file.setBook(book);
            file.setFileSubPath("");
            // For folder-based audiobooks, getFullFilePath() (libraryPath + fileSubPath + fileName)
            // must resolve to the folder itself, not a file inside it.
            file.setFileName(folder.getFileName().toString());
            file.setBookType(BookFileType.AUDIOBOOK);
            file.setFolderBased(true);
            book.setBookFiles(List.of(file));

            byte[] pngSignature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            writer.replaceCoverImageFromBytes(book, pngSignature);

            assertThat(Files.exists(folder.resolve("cover.png"))).isTrue();
        }

        @Test
        @DisplayName("Should update the embedded artwork for file-based audiobooks")
        void fileBasedUpdatesEmbeddedArtwork() throws Exception {
            String fileName = "cover-file-" + System.nanoTime() + ".mp3";
            File audioFile = tempDir.resolve(fileName).toFile();
            Files.write(audioFile.toPath(), new byte[]{1, 2, 3});

            BookEntity book = audiobookEntity(fileName, false);
            Tag tag = tagWithBlankDefaults();
            AudioFile mockedAudio = mockAudioFile(tag);

            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                mocked.when(() -> AudioFileIO.read(audioFile)).thenReturn(mockedAudio);

                writer.replaceCoverImageFromBytes(book, new byte[]{9, 9, 9});

                verify(tag).deleteArtworkField();
                verify(mockedAudio).commit();
            }
        }

        @Test
        @DisplayName("Upload should delegate through to embedded artwork replacement")
        void uploadDelegatesToBytes() throws Exception {
            String fileName = "cover-upload-" + System.nanoTime() + ".mp3";
            File audioFile = tempDir.resolve(fileName).toFile();
            Files.write(audioFile.toPath(), new byte[]{1, 2, 3});

            BookEntity book = audiobookEntity(fileName, false);
            Tag tag = tagWithBlankDefaults();
            AudioFile mockedAudio = mockAudioFile(tag);
            MultipartFile upload = new MockMultipartFile("cover.png", "cover.png", "image/png", new byte[]{1, 2, 3, 4});

            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                mocked.when(() -> AudioFileIO.read(audioFile)).thenReturn(mockedAudio);

                writer.replaceCoverImageFromUpload(book, upload);

                verify(mockedAudio).commit();
            }
        }

        @Test
        @DisplayName("URL replacement should delegate through to embedded artwork replacement")
        void urlDelegatesToBytes() throws Exception {
            String fileName = "cover-url-" + System.nanoTime() + ".mp3";
            File audioFile = tempDir.resolve(fileName).toFile();
            Files.write(audioFile.toPath(), new byte[]{1, 2, 3});

            BookEntity book = audiobookEntity(fileName, false);
            Tag tag = tagWithBlankDefaults();
            AudioFile mockedAudio = mockAudioFile(tag);
            when(fileService.downloadImageFromUrl(anyString())).thenReturn(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));

            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                mocked.when(() -> AudioFileIO.read(audioFile)).thenReturn(mockedAudio);

                writer.replaceCoverImageFromUrl(book, "https://example.com/cover.jpg");

                verify(mockedAudio).commit();
            }
        }

        @Test
        @DisplayName("Should not attempt a write when metadata writing to file is disabled")
        void disabledSettingSkipsWrite() throws Exception {
            configureAudiobookSettings(false, 100);
            String fileName = "cover-disabled-" + System.nanoTime() + ".mp3";
            File audioFile = tempDir.resolve(fileName).toFile();
            Files.write(audioFile.toPath(), new byte[]{1, 2, 3});
            BookEntity book = audiobookEntity(fileName, false);

            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                writer.replaceCoverImageFromBytes(book, new byte[]{1, 2, 3});

                mocked.verifyNoInteractions();
            }
        }
    }
}
