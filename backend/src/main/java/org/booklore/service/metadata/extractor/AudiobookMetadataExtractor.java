package org.booklore.service.metadata.extractor;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.dto.AudiobookMetadata;
import org.booklore.model.dto.BookMetadata;
import org.booklore.service.reader.FfprobeService;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v2ChapterFrames;
import org.jaudiotagger.tag.id3.framebody.FrameBodyCHAP;
import org.jaudiotagger.tag.images.Artwork;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Slf4j
@Component
@AllArgsConstructor
public class AudiobookMetadataExtractor implements FileMetadataExtractor {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile("(?i)^(chp?|chapter)?\\d+$");
    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("\\D");
    private static final String TIME_BASE_FIELD = "time_base";
    private final ObjectMapper mapper;
    private final FfprobeService ffprobeService;

    static {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.WARNING);
    }

    @Override
    public BookMetadata extractMetadata(File audioFile) {
        try {
            AudioFile f = AudioFileIO.read(audioFile);
            Tag tag = f.getTag();
            AudioHeader header = f.getAudioHeader();

            BookMetadata.BookMetadataBuilder builder = BookMetadata.builder();
            AudiobookMetadata.AudiobookMetadataBuilder audiobookBuilder = AudiobookMetadata.builder();

            extractAudioHeaderInfo(audiobookBuilder, header);

            List<AudiobookMetadata.ChapterInfo> chapters = extractChapters(audioFile, f, header);
            if (chapters != null && !chapters.isEmpty()) {
                audiobookBuilder.chapters(chapters);
                audiobookBuilder.chapterCount(chapters.size());
            }

            applyNarrator(builder, tag);
            builder.audiobookMetadata(audiobookBuilder.build());

            if (tag == null) {
                builder.title(FilenameUtils.getBaseName(audioFile.getName()));
                return builder.build();
            }

            applyTitle(builder, tag, audioFile);
            applyAuthors(builder, tag);
            applyTagFields(builder, tag);

            return builder.build();

        } catch (Exception e) {
            log.error("Failed to read metadata from audio file {}: {}", audioFile.getName(), e.getMessage(), e);
            return BookMetadata.builder()
                    .title(FilenameUtils.getBaseName(audioFile.getName()))
                    .build();
        }
    }

    private void applyNarrator(BookMetadata.BookMetadataBuilder builder, Tag tag) {
        if (tag == null) {
            return;
        }
        String narrator = tag.getFirst(FieldKey.COMPOSER);
        if (StringUtils.isNotBlank(narrator)) {
            builder.narrator(narrator);
        }
    }

    private void applyTitle(BookMetadata.BookMetadataBuilder builder, Tag tag, File audioFile) {
        String album = tag.getFirst(FieldKey.ALBUM);
        String title = tag.getFirst(FieldKey.TITLE);
        if (StringUtils.isNotBlank(album)) {
            builder.title(album);
        } else if (StringUtils.isNotBlank(title)) {
            builder.title(title);
        } else {
            builder.title(FilenameUtils.getBaseName(audioFile.getName()));
        }
    }

    private void applyAuthors(BookMetadata.BookMetadataBuilder builder, Tag tag) {
        String albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST);
        String artist = tag.getFirst(FieldKey.ARTIST);
        List<String> authors = new ArrayList<>();
        if (StringUtils.isNotBlank(albumArtist)) {
            authors.add(albumArtist);
        } else if (StringUtils.isNotBlank(artist)) {
            authors.add(artist);
        }
        if (!authors.isEmpty()) {
            builder.authors(authors);
        }
    }

    private void applyTagFields(BookMetadata.BookMetadataBuilder builder, Tag tag) {
        String comment = tag.getFirst(FieldKey.COMMENT);
        if (StringUtils.isNotBlank(comment)) {
            builder.description(comment);
        }

        String publisher = tag.getFirst(FieldKey.RECORD_LABEL);
        if (StringUtils.isNotBlank(publisher)) {
            builder.publisher(publisher);
        }

        String year = tag.getFirst(FieldKey.YEAR);
        if (StringUtils.isNotBlank(year)) {
            applyYearIfValid(builder, year);
        }

        String genre = tag.getFirst(FieldKey.GENRE);
        if (StringUtils.isNotBlank(genre)) {
            Set<String> categories = new HashSet<>();
            categories.add(genre);
            builder.categories(categories);
        }

        String language = tag.getFirst(FieldKey.LANGUAGE);
        if (StringUtils.isNotBlank(language)) {
            builder.language(language);
        }

        String grouping = tag.getFirst(FieldKey.GROUPING);
        if (StringUtils.isNotBlank(grouping)) {
            builder.seriesName(grouping);
        }

        String trackNo = tag.getFirst(FieldKey.TRACK);
        if (StringUtils.isNotBlank(trackNo)) {
            applyTrackNumberIfValid(builder, trackNo);
        }

        String trackTotal = tag.getFirst(FieldKey.TRACK_TOTAL);
        if (StringUtils.isNotBlank(trackTotal)) {
            applyTrackTotalIfValid(builder, trackTotal);
        }
    }

    private void applyYearIfValid(BookMetadata.BookMetadataBuilder builder, String year) {
        try {
            int yearInt = Integer.parseInt(year.trim().substring(0, Math.min(4, year.trim().length())));
            if (yearInt >= 1 && yearInt <= 9999) {
                builder.publishedDate(LocalDate.of(yearInt, Month.JANUARY, 1));
            }
        } catch (NumberFormatException _) {
            // ignore malformed year value
        }
    }

    private void applyTrackNumberIfValid(BookMetadata.BookMetadataBuilder builder, String trackNo) {
        try {
            String trackNum = trackNo.contains("/") ? trackNo.split("/")[0] : trackNo;
            builder.seriesNumber((float) Integer.parseInt(trackNum.trim()));
        } catch (NumberFormatException _) {
            // ignore malformed track number
        }
    }

    private void applyTrackTotalIfValid(BookMetadata.BookMetadataBuilder builder, String trackTotal) {
        try {
            builder.seriesTotal(Integer.parseInt(trackTotal.trim()));
        } catch (NumberFormatException _) {
            // ignore malformed track total
        }
    }

    private void extractAudioHeaderInfo(AudiobookMetadata.AudiobookMetadataBuilder builder, AudioHeader header) {
        if (header == null) {
            return;
        }

        long durationSeconds = (long) header.getPreciseTrackLength();
        if (durationSeconds > 0) {
            builder.durationSeconds(durationSeconds);
        }

        long bitrate = header.getBitRateAsNumber();
        if (bitrate > 0) {
            builder.bitrate((int) bitrate);
        }

        int sampleRate = header.getSampleRateAsNumber();
        if (sampleRate > 0) {
            builder.sampleRate(sampleRate);
        }

        String channelsStr = header.getChannels();
        Integer channels = parseChannels(channelsStr);
        if (channels != null) {
            builder.channels(channels);
        }

        String codec = header.getEncodingType();
        if (StringUtils.isNotBlank(codec)) {
            builder.codec(codec);
        }
    }

    private Integer parseChannels(String channels) {
        if (channels == null) {
            return null;
        }
        String lower = channels.toLowerCase();
        if (lower.contains("stereo")) {
            return 2;
        }
        if (lower.contains("mono")) {
            return 1;
        }
        if (lower.contains("5.1") || lower.contains("surround")) {
            return 6;
        }
        try {
            return Integer.parseInt(NON_DIGIT_PATTERN.matcher(channels).replaceAll(""));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public List<AudiobookMetadata.ChapterInfo> extractChaptersFromFile(File audioFile) {
        try {
            AudioFile f = AudioFileIO.read(audioFile);
            return extractChapters(audioFile, f, f.getAudioHeader());
        } catch (Exception e) {
            log.warn("Failed to extract chapters from {}: {}", audioFile.getName(), e.getMessage());
            return List.of();
        }
    }

    private List<AudiobookMetadata.ChapterInfo> extractChapters(File audioFile, AudioFile taggedFile, AudioHeader header) {
        List<AudiobookMetadata.ChapterInfo> chapters = extractId3v2Chapters(taggedFile);
        if (chapters != null && !chapters.isEmpty()) {
            log.debug("Extracted {} chapters from ID3v2 CHAP frames for {}", chapters.size(), audioFile.getName());
            return chapters;
        }

        chapters = extractChaptersWithFfprobe(audioFile);
        if (chapters != null && !chapters.isEmpty()) {
            log.debug("Extracted {} chapters using ffprobe for {}", chapters.size(), audioFile.getName());
            return chapters;
        }

        return createDefaultChapter(header);
    }

    @SuppressWarnings("unchecked")
    private List<AudiobookMetadata.ChapterInfo> extractId3v2Chapters(AudioFile taggedFile) {
        try {
            Tag tag = taggedFile.getTag();
            if (!(tag instanceof AbstractID3v2Tag id3v2Tag)) {
                return List.of();
            }

            Object chapFrames = id3v2Tag.getFrame(ID3v2ChapterFrames.FRAME_ID_CHAPTER);
            if (chapFrames == null) {
                return List.of();
            }

            List<AudiobookMetadata.ChapterInfo> chapters = new ArrayList<>();
            List<AbstractID3v2Frame> frameList;

            if (chapFrames instanceof List) {
                frameList = (List<AbstractID3v2Frame>) chapFrames;
            } else if (chapFrames instanceof AbstractID3v2Frame abstractid3v2frame) {
                frameList = List.of(abstractid3v2frame);
            } else {
                return List.of();
            }

            frameList = new ArrayList<>(frameList);
            frameList.sort((f1, f2) -> {
                if (f1.getBody() instanceof FrameBodyCHAP chap1 && f2.getBody() instanceof FrameBodyCHAP chap2) {
                    long start1 = getChapStartTime(chap1);
                    long start2 = getChapStartTime(chap2);
                    return Long.compare(start1, start2);
                }
                return 0;
            });

            for (int i = 0; i < frameList.size(); i++) {
                AbstractID3v2Frame frame = frameList.get(i);
                if (!(frame.getBody() instanceof FrameBodyCHAP chapBody)) {
                    continue;
                }

                long startTimeMs = getChapStartTime(chapBody);
                long endTimeMs = getChapEndTime(chapBody);
                long durationMs = endTimeMs - startTimeMs;

                String title = extractChapterTitle(chapBody, i);

                chapters.add(AudiobookMetadata.ChapterInfo.builder()
                        .index(i)
                        .title(title)
                        .startTimeMs(startTimeMs)
                        .endTimeMs(endTimeMs)
                        .durationMs(durationMs)
                        .build());
            }

            return chapters.isEmpty() ? null : chapters;
        } catch (Exception e) {
            log.debug("Failed to extract ID3v2 CHAP frames: {}", e.getMessage());
            return List.of();
        }
    }

    private long getChapStartTime(FrameBodyCHAP chapBody) {
        Object startTime = chapBody.getObjectValue("StartTime");
        if (startTime instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    private long getChapEndTime(FrameBodyCHAP chapBody) {
        Object endTime = chapBody.getObjectValue("EndTime");
        if (endTime instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    private String extractChapterTitle(FrameBodyCHAP chapBody, int index) {
        try {
            Object elementId = chapBody.getObjectValue("ElementID");
            if (elementId instanceof String str && StringUtils.isNotBlank(str) && !CHAPTER_PATTERN.matcher(str).matches()) {
                return str;
            }
        } catch (Exception _) {
            // fall back to generated chapter title
        }

        return "Chapter " + (index + 1);
    }

    private List<AudiobookMetadata.ChapterInfo> extractChaptersWithFfprobe(File audioFile) {
        try {
            String jsonOutput = runFfprobeShowChapters(audioFile);
            if (jsonOutput == null || jsonOutput.isEmpty() || jsonOutput.equals("{}")) {
                return List.of();
            }

            JsonNode root = mapper.readTree(jsonOutput);
            JsonNode chaptersNode = root.get("chapters");

            if (chaptersNode == null || !chaptersNode.isArray() || chaptersNode.isEmpty()) {
                return List.of();
            }

            List<AudiobookMetadata.ChapterInfo> chapters = new ArrayList<>();
            for (int i = 0; i < chaptersNode.size(); i++) {
                chapters.add(parseFfprobeChapter(chaptersNode.get(i), i));
            }

            return chapters;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Failed to extract chapters with ffprobe from {}: {}", audioFile.getName(), e.getMessage());
            return List.of();
        }
    }

    private String runFfprobeShowChapters(File audioFile) throws Exception {
        Path ffprobeBinary = ffprobeService.getFfprobeBinary();
        if (ffprobeBinary == null) {
            log.debug("ffprobe binary not available, skipping chapter extraction for {}", audioFile.getName());
            return null;
        }

        ProcessBuilder pb = new ProcessBuilder(
                ffprobeBinary.toAbsolutePath().toString(),
                "-v", "quiet",
                "-print_format", "json",
                "-show_chapters",
                audioFile.getAbsolutePath()
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.debug("FFprobe exited with code {} for {}", exitCode, audioFile.getName());
            return null;
        }

        return output.toString().trim();
    }

    private AudiobookMetadata.ChapterInfo parseFfprobeChapter(JsonNode chapterNode, int index) {
        double startTime = resolveChapterTimeSeconds(chapterNode, "start_time", "start");
        double endTime = resolveChapterTimeSeconds(chapterNode, "end_time", "end");

        long startTimeMs = Math.round(startTime * 1000);
        long endTimeMs = Math.round(endTime * 1000);
        long durationMs = endTimeMs - startTimeMs;

        String title = "Chapter " + (index + 1);
        JsonNode tagsNode = chapterNode.get("tags");
        if (tagsNode != null && tagsNode.has("title")) {
            title = tagsNode.get("title").asString();
        }

        return AudiobookMetadata.ChapterInfo.builder()
                .index(index)
                .title(title)
                .startTimeMs(startTimeMs)
                .endTimeMs(endTimeMs)
                .durationMs(durationMs)
                .build();
    }

    private double resolveChapterTimeSeconds(JsonNode chapterNode, String secondsKey, String rawKey) {
        if (chapterNode.has(secondsKey)) {
            return chapterNode.get(secondsKey).asDouble();
        }
        if (chapterNode.has(rawKey)) {
            long raw = chapterNode.get(rawKey).asLong();
            String timeBase = chapterNode.has(TIME_BASE_FIELD) ? chapterNode.get(TIME_BASE_FIELD).asString() : "1/1000";
            return convertTimebaseToSeconds(raw, timeBase);
        }
        return 0;
    }

    private List<AudiobookMetadata.ChapterInfo> createDefaultChapter(AudioHeader header) {
        if (header == null) {
            return new ArrayList<>();
        }
        long durationMs = (long) (header.getPreciseTrackLength() * 1000);
        return List.of(AudiobookMetadata.ChapterInfo.builder()
                .index(0)
                .title("Full Audiobook")
                .startTimeMs(0L)
                .endTimeMs(durationMs)
                .durationMs(durationMs)
                .build());
    }

    private double convertTimebaseToSeconds(long value, String timeBase) {
        String[] parts = timeBase.split("/");
        if (parts.length == 2) {
            try {
                double numerator = Double.parseDouble(parts[0]);
                double denominator = Double.parseDouble(parts[1]);
                return value * (numerator / denominator);
            } catch (NumberFormatException _) {
                return value / 1000.0;
            }
        }
        return value / 1000.0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * An audio file that reads but carries no tag, or a tag with no artwork, genuinely has no cover.
     * A file jaudiotagger cannot read at all says nothing about artwork either way.
     */
    @Override
    @SuppressWarnings("java:S1168") // null (not empty array) means "no cover"; BookCoverGenerator/BookdropMetadataService branch on == null
    public byte[] extractCover(File audioFile) {
        try {
            AudioFile f = AudioFileIO.read(audioFile);
            Tag tag = f.getTag();

            if (tag == null) {
                // Read fine, simply untagged: nowhere for a cover to hide.
                return null;
            }

            Artwork artwork = tag.getFirstArtwork();
            if (artwork != null) {
                return artwork.getBinaryData();
            }

            return null;
        } catch (Exception e) {
            throw new CoverExtractionException("Failed to extract cover from audio file: " + audioFile.getName(), e);
        }
    }
}
