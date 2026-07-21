package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.AudiobookMetadata;
import org.booklore.model.dto.BookMetadata;
import org.booklore.service.reader.FfprobeService;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v2ChapterFrames;
import org.jaudiotagger.tag.id3.framebody.FrameBodyCHAP;
import org.jaudiotagger.tag.images.Artwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.jaudiotagger.audio.AudioFileIO;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AudiobookMetadataExtractorTest {

    private static final String TEST_MP3 = "test.mp3";
    private static final String FULL_AUDIOBOOK_TITLE = "Full Audiobook";

    @Mock
    private FfprobeService ffprobeService;

    private AudiobookMetadataExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        extractor = new AudiobookMetadataExtractor(new ObjectMapper(), ffprobeService);
    }

    @Nested
    class ExtractMetadataTests {

        @Test
        void titleFromAlbumTag() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mockAudioFile("My Album", "Track Title", null);
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getTitle()).isEqualTo("My Album");
            }
        }

        @Test
        void titleFallsBackToTitleTag() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mockAudioFile("", "Track Title", null);
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getTitle()).isEqualTo("Track Title");
            }
        }

        @Test
        void titleFallsBackToFileName() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mockAudioFile("", "", null);
                File file = new File(tempDir.toFile(), "my-audiobook.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getTitle()).isEqualTo("my-audiobook");
            }
        }

        @Test
        void titleFallsBackToFileNameWhenNoTag() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mock(AudioFile.class);
                AudioHeader header = mock(AudioHeader.class);
                when(audioFile.getTag()).thenReturn(null);
                when(audioFile.getAudioHeader()).thenReturn(header);
                File file = new File(tempDir.toFile(), "notags.m4b");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getTitle()).isEqualTo("notags");
            }
        }

        @Test
        void authorFromAlbumArtist() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(FieldKey.ALBUM)).thenReturn("Book");
                when(tag.getFirst(FieldKey.TITLE)).thenReturn("");
                when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("Jane Austen");
                when(tag.getFirst(FieldKey.ARTIST)).thenReturn("Some Artist");
                stubRemainingTagFields(tag);

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(mock(AudioHeader.class));
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getAuthors()).containsExactly("Jane Austen");
            }
        }

        @Test
        void authorFallsBackToArtist() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(FieldKey.ALBUM)).thenReturn("Book");
                when(tag.getFirst(FieldKey.TITLE)).thenReturn("");
                when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.ARTIST)).thenReturn("Fallback Artist");
                stubRemainingTagFields(tag);

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(mock(AudioHeader.class));
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getAuthors()).containsExactly("Fallback Artist");
            }
        }

        @Test
        void yearParsing_validYear() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mockAudioFileWithYear("2020");
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2020, Month.JANUARY, 1));
            }
        }

        @Test
        void yearParsing_truncatesLongString() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mockAudioFileWithYear("20201231");
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2020, Month.JANUARY, 1));
            }
        }

        @Test
        void yearParsing_invalidStringIgnored() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mockAudioFileWithYear("abcd");
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getPublishedDate()).isNull();
            }
        }

        @Test
        void yearParsing_zeroIgnored() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mockAudioFileWithYear("0000");
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getPublishedDate()).isNull();
            }
        }

        @Test
        void narratorFromComposerTag() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(FieldKey.ALBUM)).thenReturn("Book");
                when(tag.getFirst(FieldKey.TITLE)).thenReturn("");
                when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.ARTIST)).thenReturn("");
                stubRemainingTagFields(tag);
                when(tag.getFirst(FieldKey.COMPOSER)).thenReturn("Stephen Fry");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(mock(AudioHeader.class));
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getNarrator()).isEqualTo("Stephen Fry");
            }
        }

        @Test
        void seriesFromGroupingAndTrack() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(FieldKey.ALBUM)).thenReturn("Book");
                when(tag.getFirst(FieldKey.TITLE)).thenReturn("");
                when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.COMPOSER)).thenReturn("");
                when(tag.getFirst(FieldKey.COMMENT)).thenReturn("");
                when(tag.getFirst(FieldKey.RECORD_LABEL)).thenReturn("");
                when(tag.getFirst(FieldKey.YEAR)).thenReturn("");
                when(tag.getFirst(FieldKey.GENRE)).thenReturn("");
                when(tag.getFirst(FieldKey.LANGUAGE)).thenReturn("");
                when(tag.getFirst(FieldKey.GROUPING)).thenReturn("Harry Potter");
                when(tag.getFirst(FieldKey.TRACK)).thenReturn("3/7");
                when(tag.getFirst(FieldKey.TRACK_TOTAL)).thenReturn("7");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(mock(AudioHeader.class));
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getSeriesName()).isEqualTo("Harry Potter");
                assertThat(metadata.getSeriesNumber()).isEqualTo(3.0f);
                assertThat(metadata.getSeriesTotal()).isEqualTo(7);
            }
        }

        @Test
        void trackNumberWithoutSlash() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(FieldKey.ALBUM)).thenReturn("Book");
                when(tag.getFirst(FieldKey.TITLE)).thenReturn("");
                when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.COMPOSER)).thenReturn("");
                when(tag.getFirst(FieldKey.COMMENT)).thenReturn("");
                when(tag.getFirst(FieldKey.RECORD_LABEL)).thenReturn("");
                when(tag.getFirst(FieldKey.YEAR)).thenReturn("");
                when(tag.getFirst(FieldKey.GENRE)).thenReturn("");
                when(tag.getFirst(FieldKey.LANGUAGE)).thenReturn("");
                when(tag.getFirst(FieldKey.GROUPING)).thenReturn("");
                when(tag.getFirst(FieldKey.TRACK)).thenReturn("5");
                when(tag.getFirst(FieldKey.TRACK_TOTAL)).thenReturn("");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(mock(AudioHeader.class));
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getSeriesNumber()).isEqualTo(5.0f);
            }
        }

        @Test
        void exceptionReturnsFallbackMetadata() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                File file = new File(tempDir.toFile(), "broken.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenThrow(new RuntimeException("corrupt"));

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata).isNotNull();
                assertThat(metadata.getTitle()).isEqualTo("broken");
            }
        }

        @Test
        void descriptionFromComment() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(FieldKey.ALBUM)).thenReturn("Book");
                when(tag.getFirst(FieldKey.TITLE)).thenReturn("");
                when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.COMPOSER)).thenReturn("");
                when(tag.getFirst(FieldKey.COMMENT)).thenReturn("A thrilling story.");
                when(tag.getFirst(FieldKey.RECORD_LABEL)).thenReturn("");
                when(tag.getFirst(FieldKey.YEAR)).thenReturn("");
                when(tag.getFirst(FieldKey.GENRE)).thenReturn("");
                when(tag.getFirst(FieldKey.LANGUAGE)).thenReturn("");
                when(tag.getFirst(FieldKey.GROUPING)).thenReturn("");
                when(tag.getFirst(FieldKey.TRACK)).thenReturn("");
                when(tag.getFirst(FieldKey.TRACK_TOTAL)).thenReturn("");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(mock(AudioHeader.class));
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getDescription()).isEqualTo("A thrilling story.");
            }
        }

        @Test
        void genreExtracted() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(FieldKey.ALBUM)).thenReturn("Book");
                when(tag.getFirst(FieldKey.TITLE)).thenReturn("");
                when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.ARTIST)).thenReturn("");
                when(tag.getFirst(FieldKey.COMPOSER)).thenReturn("");
                when(tag.getFirst(FieldKey.COMMENT)).thenReturn("");
                when(tag.getFirst(FieldKey.RECORD_LABEL)).thenReturn("");
                when(tag.getFirst(FieldKey.YEAR)).thenReturn("");
                when(tag.getFirst(FieldKey.GENRE)).thenReturn("Fantasy");
                when(tag.getFirst(FieldKey.LANGUAGE)).thenReturn("");
                when(tag.getFirst(FieldKey.GROUPING)).thenReturn("");
                when(tag.getFirst(FieldKey.TRACK)).thenReturn("");
                when(tag.getFirst(FieldKey.TRACK_TOTAL)).thenReturn("");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(mock(AudioHeader.class));
                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getCategories()).containsExactly("Fantasy");
            }
        }
    }

    @Nested
    class ParseChannelsTests {

        @Test
        void stereo() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                verifyChannelsParsed("Stereo", 2, mocked);
            }
        }

        @Test
        void mono() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                verifyChannelsParsed("Mono", 1, mocked);
            }
        }

        @Test
        void surround51() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                verifyChannelsParsed("5.1 Surround", 6, mocked);
            }
        }

        @Test
        void numericChannels() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                verifyChannelsParsed("4", 4, mocked);
            }
        }

        @Test
        void nullChannels() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioHeader header = mock(AudioHeader.class);
                when(header.getChannels()).thenReturn(null);
                when(header.getPreciseTrackLength()).thenReturn(0.0);
                when(header.getBitRateAsNumber()).thenReturn(0L);
                when(header.getSampleRateAsNumber()).thenReturn(0);
                when(header.getEncodingType()).thenReturn("");

                Tag tag = mock(Tag.class);
                when(tag.getFirst(any(FieldKey.class))).thenReturn("");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(header);

                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getAudiobookMetadata().getChannels()).isNull();
            }
        }

        private void verifyChannelsParsed(String channelStr, int expected, MockedStatic<AudioFileIO> mocked) {
            AudioHeader header = mock(AudioHeader.class);
            when(header.getChannels()).thenReturn(channelStr);
            when(header.getPreciseTrackLength()).thenReturn(100.0);
            when(header.getBitRateAsNumber()).thenReturn(128L);
            when(header.getSampleRateAsNumber()).thenReturn(44100);
            when(header.getEncodingType()).thenReturn("mp3");

            Tag tag = mock(Tag.class);
            when(tag.getFirst(any(FieldKey.class))).thenReturn("");

            AudioFile audioFile = mock(AudioFile.class);
            when(audioFile.getTag()).thenReturn(tag);
            when(audioFile.getAudioHeader()).thenReturn(header);

            File file = new File(tempDir.toFile(), "channels-test.mp3");
            mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata.getAudiobookMetadata().getChannels()).isEqualTo(expected);
        }
    }

    @Nested
    class AudioHeaderTests {

        @Test
        void extractsDurationBitrateSampleRateCodec() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioHeader header = mock(AudioHeader.class);
                when(header.getPreciseTrackLength()).thenReturn(3661.5);
                when(header.getBitRateAsNumber()).thenReturn(256L);
                when(header.getSampleRateAsNumber()).thenReturn(48000);
                when(header.getChannels()).thenReturn("Stereo");
                when(header.getEncodingType()).thenReturn("AAC");

                Tag tag = mock(Tag.class);
                when(tag.getFirst(any(FieldKey.class))).thenReturn("");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(header);

                File file = new File(tempDir.toFile(), "test.m4b");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                AudiobookMetadata abMeta = metadata.getAudiobookMetadata();

                assertThat(abMeta.getDurationSeconds()).isEqualTo(3661L);
                assertThat(abMeta.getBitrate()).isEqualTo(256);
                assertThat(abMeta.getSampleRate()).isEqualTo(48000);
                assertThat(abMeta.getChannels()).isEqualTo(2);
                assertThat(abMeta.getCodec()).isEqualTo("AAC");
            }
        }

        @Test
        void nullHeaderHandledGracefully() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(any(FieldKey.class))).thenReturn("");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(null);

                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getAudiobookMetadata()).isNotNull();
            }
        }
    }

    @Nested
    class ChapterExtractionTests {

        @Test
        void id3v2ChaptersExtracted() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                FrameBodyCHAP chapBody1 = mock(FrameBodyCHAP.class);
                when(chapBody1.getObjectValue("StartTime")).thenReturn(0L);
                when(chapBody1.getObjectValue("EndTime")).thenReturn(60000L);
                when(chapBody1.getObjectValue("ElementID")).thenReturn("Introduction");

                FrameBodyCHAP chapBody2 = mock(FrameBodyCHAP.class);
                when(chapBody2.getObjectValue("StartTime")).thenReturn(60000L);
                when(chapBody2.getObjectValue("EndTime")).thenReturn(120000L);
                when(chapBody2.getObjectValue("ElementID")).thenReturn("ch2");

                AbstractID3v2Frame frame1 = mock(AbstractID3v2Frame.class);
                when(frame1.getBody()).thenReturn(chapBody1);
                AbstractID3v2Frame frame2 = mock(AbstractID3v2Frame.class);
                when(frame2.getBody()).thenReturn(chapBody2);

                AbstractID3v2Tag id3v2Tag = mock(AbstractID3v2Tag.class);
                when(id3v2Tag.getFrame(ID3v2ChapterFrames.FRAME_ID_CHAPTER)).thenReturn(List.of(frame1, frame2));
                when(id3v2Tag.getFirst(any(FieldKey.class))).thenReturn("");

                AudioHeader header = mock(AudioHeader.class);
                when(header.getPreciseTrackLength()).thenReturn(120.0);
                when(header.getBitRateAsNumber()).thenReturn(128L);
                when(header.getSampleRateAsNumber()).thenReturn(44100);
                when(header.getChannels()).thenReturn("Stereo");
                when(header.getEncodingType()).thenReturn("mp3");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(id3v2Tag);
                when(audioFile.getAudioHeader()).thenReturn(header);

                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                AudiobookMetadata abMeta = metadata.getAudiobookMetadata();

                assertThat(abMeta.getChapters()).hasSize(2);
                assertThat(abMeta.getChapterCount()).isEqualTo(2);
                assertThat(abMeta.getChapters().get(0).getTitle()).isEqualTo("Introduction");
                assertThat(abMeta.getChapters().get(0).getStartTimeMs()).isZero();
                assertThat(abMeta.getChapters().get(0).getEndTimeMs()).isEqualTo(60000L);
                assertThat(abMeta.getChapters().get(0).getDurationMs()).isEqualTo(60000L);
                assertThat(abMeta.getChapters().get(1).getTitle()).isEqualTo("Chapter 2");
            }
        }

        @Test
        void chapterTitleFallsBackToDefaultForGenericIds() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                FrameBodyCHAP chapBody = mock(FrameBodyCHAP.class);
                when(chapBody.getObjectValue("StartTime")).thenReturn(0L);
                when(chapBody.getObjectValue("EndTime")).thenReturn(60000L);
                when(chapBody.getObjectValue("ElementID")).thenReturn("chp3");

                AbstractID3v2Frame frame = mock(AbstractID3v2Frame.class);
                when(frame.getBody()).thenReturn(chapBody);

                AbstractID3v2Tag id3v2Tag = mock(AbstractID3v2Tag.class);
                when(id3v2Tag.getFrame(ID3v2ChapterFrames.FRAME_ID_CHAPTER)).thenReturn(List.of(frame));
                when(id3v2Tag.getFirst(any(FieldKey.class))).thenReturn("");

                AudioHeader header = mock(AudioHeader.class);
                when(header.getPreciseTrackLength()).thenReturn(60.0);
                when(header.getBitRateAsNumber()).thenReturn(128L);
                when(header.getSampleRateAsNumber()).thenReturn(44100);
                when(header.getChannels()).thenReturn("Stereo");
                when(header.getEncodingType()).thenReturn("mp3");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(id3v2Tag);
                when(audioFile.getAudioHeader()).thenReturn(header);

                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);

                assertThat(metadata.getAudiobookMetadata().getChapters().get(0).getTitle()).isEqualTo("Chapter 1");
            }
        }

        @Test
        void defaultChapterCreatedWhenNoChaptersFound() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirst(any(FieldKey.class))).thenReturn("");

                AudioHeader header = mock(AudioHeader.class);
                when(header.getPreciseTrackLength()).thenReturn(300.0);
                when(header.getBitRateAsNumber()).thenReturn(128L);
                when(header.getSampleRateAsNumber()).thenReturn(44100);
                when(header.getChannels()).thenReturn("Stereo");
                when(header.getEncodingType()).thenReturn("mp3");

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);
                when(audioFile.getAudioHeader()).thenReturn(header);

                when(ffprobeService.getFfprobeBinary()).thenReturn(null);

                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                AudiobookMetadata abMeta = metadata.getAudiobookMetadata();

                assertThat(abMeta.getChapters()).hasSize(1);
                assertThat(abMeta.getChapters().get(0).getTitle()).isEqualTo("Full Audiobook");
                assertThat(abMeta.getChapters().get(0).getStartTimeMs()).isZero();
                assertThat(abMeta.getChapters().get(0).getDurationMs()).isEqualTo(300000L);
            }
        }
    }

    @Nested
    class ExtractCoverTests {

        @Test
        void coverFromArtworkTag() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                byte[] imageData = {0x01, 0x02, 0x03};
                Artwork artwork = mock(Artwork.class);
                when(artwork.getBinaryData()).thenReturn(imageData);

                Tag tag = mock(Tag.class);
                when(tag.getFirstArtwork()).thenReturn(artwork);

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);

                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                byte[] cover = extractor.extractCover(file);

                assertThat(cover).isEqualTo(imageData);
            }
        }

        @Test
        void noCoverReturnsNull() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                Tag tag = mock(Tag.class);
                when(tag.getFirstArtwork()).thenReturn(null);

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(tag);

                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                byte[] cover = extractor.extractCover(file);

                assertThat(cover).isNull();
            }
        }

        @Test
        void noTagReturnsNull() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(null);

                File file = new File(tempDir.toFile(), "test.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                byte[] cover = extractor.extractCover(file);

                assertThat(cover).isNull();
            }
        }

        /**
         * A file jaudiotagger cannot read at all is not a file without artwork. Driven with a real
         * unreadable file rather than a stubbed static: the point of the whole change is that the
         * real collaborator throws, and stubbing one that throws proves nothing about whether it
         * does.
         */
        @Test
        void unreadableAudioFileThrowsRatherThanReportingNoCover() throws IOException {
            File file = new File(tempDir.toFile(), "corrupt.mp3");
            Files.write(file.toPath(), new byte[]{0x00, 0x01, 0x02, 0x03});

            assertThatThrownBy(() -> extractor.extractCover(file))
                    .isInstanceOf(CoverExtractionException.class)
                    .hasMessageContaining("corrupt.mp3");
        }

        @Test
        void missingAudioFileThrowsRatherThanReportingNoCover() {
            File missing = new File(tempDir.toFile(), "gone.mp3");

            assertThatThrownBy(() -> extractor.extractCover(missing))
                    .isInstanceOf(CoverExtractionException.class);
        }
    }

    /**
     * Drives {@code extractChaptersWithFfprobe} through a real {@code ProcessBuilder} invocation
     * (as production code does) rather than mocking the private method: the fake "ffprobe" is a
     * tiny shell script that prints canned JSON to stdout, so the real process-execution and
     * JSON-parsing code paths both run.
     */
    @Nested
    class FfprobeChapterExtractionTests {

        private Path fakeFfprobe(String stdout, int exitCode) throws IOException {
            Path script = tempDir.resolve("fake-ffprobe.sh");
            String content = "#!/bin/sh\ncat <<'JSON_EOF'\n" + stdout + "\nJSON_EOF\nexit " + exitCode + "\n";
            Files.writeString(script, content);
            assertThat(script.toFile().setExecutable(true)).isTrue();
            return script;
        }

        private AudioFile audioFileWithNoId3v2Chapters(double trackLengthSeconds) {
            Tag tag = mock(Tag.class);
            when(tag.getFirst(any(FieldKey.class))).thenReturn("");

            AudioHeader header = mock(AudioHeader.class);
            when(header.getPreciseTrackLength()).thenReturn(trackLengthSeconds);
            when(header.getBitRateAsNumber()).thenReturn(128L);
            when(header.getSampleRateAsNumber()).thenReturn(44100);
            when(header.getChannels()).thenReturn("Stereo");
            when(header.getEncodingType()).thenReturn("mp3");

            AudioFile audioFile = mock(AudioFile.class);
            when(audioFile.getTag()).thenReturn(tag);
            when(audioFile.getAudioHeader()).thenReturn(header);
            return audioFile;
        }

        @Test
        void directSecondsFields_titleFromTagsWithDefaultFallback() throws IOException {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                when(ffprobeService.getFfprobeBinary()).thenReturn(fakeFfprobe("""
                        {"chapters": [
                          {"start_time": 0.0, "end_time": 125.5, "tags": {"title": "Prologue"}},
                          {"start_time": 125.5, "end_time": 300.0}
                        ]}""", 0));

                AudioFile audioFile = audioFileWithNoId3v2Chapters(300.0);
                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                List<AudiobookMetadata.ChapterInfo> chapters = metadata.getAudiobookMetadata().getChapters();

                assertThat(chapters).hasSize(2);
                assertThat(chapters.get(0).getTitle()).isEqualTo("Prologue");
                assertThat(chapters.get(0).getEndTimeMs()).isEqualTo(125500L);
                assertThat(chapters.get(1).getTitle()).isEqualTo("Chapter 2");
                assertThat(chapters.get(1).getStartTimeMs()).isEqualTo(125500L);
                assertThat(chapters.get(1).getEndTimeMs()).isEqualTo(300000L);
            }
        }

        @Test
        void rawFields_convertedUsingExplicitTimeBase() throws IOException {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                when(ffprobeService.getFfprobeBinary()).thenReturn(fakeFfprobe(
                        "{\"chapters\": [{\"start\": 0, \"end\": 48000, \"time_base\": \"1/48000\"}]}", 0));

                AudioFile audioFile = audioFileWithNoId3v2Chapters(1.0);
                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                List<AudiobookMetadata.ChapterInfo> chapters = metadata.getAudiobookMetadata().getChapters();

                assertThat(chapters).hasSize(1);
                assertThat(chapters.getFirst().getStartTimeMs()).isZero();
                assertThat(chapters.getFirst().getEndTimeMs()).isEqualTo(1000L);
            }
        }

        @Test
        void rawFields_defaultToMillisecondTimeBaseWhenAbsent() throws IOException {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                when(ffprobeService.getFfprobeBinary()).thenReturn(fakeFfprobe(
                        "{\"chapters\": [{\"start\": 2500, \"end\": 5000}]}", 0));

                AudioFile audioFile = audioFileWithNoId3v2Chapters(5.0);
                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                List<AudiobookMetadata.ChapterInfo> chapters = metadata.getAudiobookMetadata().getChapters();

                assertThat(chapters).hasSize(1);
                assertThat(chapters.getFirst().getStartTimeMs()).isEqualTo(2500L);
                assertThat(chapters.getFirst().getEndTimeMs()).isEqualTo(5000L);
            }
        }

        @Test
        void malformedTimeBase_fallsBackToDividingByOneThousand() throws IOException {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                when(ffprobeService.getFfprobeBinary()).thenReturn(fakeFfprobe(
                        "{\"chapters\": [{\"start\": 5000, \"end\": 10000, \"time_base\": \"bogus\"}]}", 0));

                AudioFile audioFile = audioFileWithNoId3v2Chapters(10.0);
                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                List<AudiobookMetadata.ChapterInfo> chapters = metadata.getAudiobookMetadata().getChapters();

                assertThat(chapters).hasSize(1);
                assertThat(chapters.getFirst().getStartTimeMs()).isEqualTo(5000L);
                assertThat(chapters.getFirst().getEndTimeMs()).isEqualTo(10000L);
            }
        }

        @Test
        void emptyChaptersArray_fallsBackToDefaultChapter() throws IOException {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                when(ffprobeService.getFfprobeBinary()).thenReturn(fakeFfprobe("{\"chapters\": []}", 0));

                AudioFile audioFile = audioFileWithNoId3v2Chapters(90.0);
                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                List<AudiobookMetadata.ChapterInfo> chapters = metadata.getAudiobookMetadata().getChapters();

                assertThat(chapters).hasSize(1);
                assertThat(chapters.getFirst().getTitle()).isEqualTo(FULL_AUDIOBOOK_TITLE);
            }
        }

        @Test
        void malformedJsonOutput_fallsBackToDefaultChapter() throws IOException {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                when(ffprobeService.getFfprobeBinary()).thenReturn(fakeFfprobe("not valid json at all", 0));

                AudioFile audioFile = audioFileWithNoId3v2Chapters(90.0);
                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                List<AudiobookMetadata.ChapterInfo> chapters = metadata.getAudiobookMetadata().getChapters();

                assertThat(chapters).hasSize(1);
                assertThat(chapters.getFirst().getTitle()).isEqualTo(FULL_AUDIOBOOK_TITLE);
            }
        }

        @Test
        void nonZeroExitCode_fallsBackToDefaultChapter() throws IOException {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                when(ffprobeService.getFfprobeBinary()).thenReturn(fakeFfprobe("{\"chapters\": []}", 1));

                AudioFile audioFile = audioFileWithNoId3v2Chapters(90.0);
                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                List<AudiobookMetadata.ChapterInfo> chapters = metadata.getAudiobookMetadata().getChapters();

                assertThat(chapters).hasSize(1);
                assertThat(chapters.getFirst().getTitle()).isEqualTo(FULL_AUDIOBOOK_TITLE);
            }
        }

        @Test
        void literalEmptyJsonObject_fallsBackToDefaultChapter() throws IOException {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                when(ffprobeService.getFfprobeBinary()).thenReturn(fakeFfprobe("{}", 0));

                AudioFile audioFile = audioFileWithNoId3v2Chapters(90.0);
                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                BookMetadata metadata = extractor.extractMetadata(file);
                List<AudiobookMetadata.ChapterInfo> chapters = metadata.getAudiobookMetadata().getChapters();

                assertThat(chapters).hasSize(1);
                assertThat(chapters.getFirst().getTitle()).isEqualTo(FULL_AUDIOBOOK_TITLE);
            }
        }
    }

    @Nested
    class ExtractChaptersFromFileTests {

        @Test
        void delegatesToTheSameChapterExtractionAsExtractMetadata() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                FrameBodyCHAP chapBody = mock(FrameBodyCHAP.class);
                when(chapBody.getObjectValue("StartTime")).thenReturn(0L);
                when(chapBody.getObjectValue("EndTime")).thenReturn(60000L);
                when(chapBody.getObjectValue("ElementID")).thenReturn("Intro");

                AbstractID3v2Frame frame = mock(AbstractID3v2Frame.class);
                when(frame.getBody()).thenReturn(chapBody);

                AbstractID3v2Tag id3v2Tag = mock(AbstractID3v2Tag.class);
                when(id3v2Tag.getFrame(ID3v2ChapterFrames.FRAME_ID_CHAPTER)).thenReturn(List.of(frame));

                AudioFile audioFile = mock(AudioFile.class);
                when(audioFile.getTag()).thenReturn(id3v2Tag);
                when(audioFile.getAudioHeader()).thenReturn(mock(AudioHeader.class));

                File file = new File(tempDir.toFile(), TEST_MP3);
                mocked.when(() -> AudioFileIO.read(file)).thenReturn(audioFile);

                List<AudiobookMetadata.ChapterInfo> chapters = extractor.extractChaptersFromFile(file);

                assertThat(chapters).hasSize(1);
                assertThat(chapters.getFirst().getTitle()).isEqualTo("Intro");
            }
        }

        @Test
        void returnsEmptyListRatherThanThrowingWhenTheFileCannotBeRead() {
            try (MockedStatic<AudioFileIO> mocked = mockStatic(AudioFileIO.class)) {
                File file = new File(tempDir.toFile(), "broken.mp3");
                mocked.when(() -> AudioFileIO.read(file)).thenThrow(new RuntimeException("corrupt"));

                List<AudiobookMetadata.ChapterInfo> chapters = extractor.extractChaptersFromFile(file);

                assertThat(chapters).isEmpty();
            }
        }
    }

    private AudioFile mockAudioFile(String album, String title, AudioHeader header) {
        Tag tag = mock(Tag.class);
        when(tag.getFirst(FieldKey.ALBUM)).thenReturn(album);
        when(tag.getFirst(FieldKey.TITLE)).thenReturn(title);
        when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("");
        when(tag.getFirst(FieldKey.ARTIST)).thenReturn("");
        stubRemainingTagFields(tag);

        if (header == null) {
            header = mock(AudioHeader.class);
        }

        AudioFile audioFile = mock(AudioFile.class);
        when(audioFile.getTag()).thenReturn(tag);
        when(audioFile.getAudioHeader()).thenReturn(header);
        return audioFile;
    }

    private AudioFile mockAudioFileWithYear(String year) {
        Tag tag = mock(Tag.class);
        when(tag.getFirst(FieldKey.ALBUM)).thenReturn("Book");
        when(tag.getFirst(FieldKey.TITLE)).thenReturn("");
        when(tag.getFirst(FieldKey.ALBUM_ARTIST)).thenReturn("");
        when(tag.getFirst(FieldKey.ARTIST)).thenReturn("");
        when(tag.getFirst(FieldKey.COMPOSER)).thenReturn("");
        when(tag.getFirst(FieldKey.COMMENT)).thenReturn("");
        when(tag.getFirst(FieldKey.RECORD_LABEL)).thenReturn("");
        when(tag.getFirst(FieldKey.YEAR)).thenReturn(year);
        when(tag.getFirst(FieldKey.GENRE)).thenReturn("");
        when(tag.getFirst(FieldKey.LANGUAGE)).thenReturn("");
        when(tag.getFirst(FieldKey.GROUPING)).thenReturn("");
        when(tag.getFirst(FieldKey.TRACK)).thenReturn("");
        when(tag.getFirst(FieldKey.TRACK_TOTAL)).thenReturn("");

        AudioHeader header = mock(AudioHeader.class);

        AudioFile audioFile = mock(AudioFile.class);
        when(audioFile.getTag()).thenReturn(tag);
        when(audioFile.getAudioHeader()).thenReturn(header);
        return audioFile;
    }

    private void stubRemainingTagFields(Tag tag) {
        when(tag.getFirst(FieldKey.COMPOSER)).thenReturn("");
        when(tag.getFirst(FieldKey.COMMENT)).thenReturn("");
        when(tag.getFirst(FieldKey.RECORD_LABEL)).thenReturn("");
        when(tag.getFirst(FieldKey.YEAR)).thenReturn("");
        when(tag.getFirst(FieldKey.GENRE)).thenReturn("");
        when(tag.getFirst(FieldKey.LANGUAGE)).thenReturn("");
        when(tag.getFirst(FieldKey.GROUPING)).thenReturn("");
        when(tag.getFirst(FieldKey.TRACK)).thenReturn("");
        when(tag.getFirst(FieldKey.TRACK_TOTAL)).thenReturn("");
    }
}
