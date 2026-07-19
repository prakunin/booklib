package org.booklore.service.reader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.AudiobookMetadata;
import org.booklore.model.dto.response.AudiobookChapter;
import org.booklore.model.dto.response.AudiobookInfo;
import org.booklore.model.dto.response.AudiobookTrack;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.metadata.extractor.AudiobookMetadataExtractor;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AudioMetadataService {

  private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("\\D");
  private final AudioFileUtilityService audioFileUtility;
  private final AudiobookMetadataExtractor audiobookMetadataExtractor;
  private final BookFileRepository bookFileRepository;

  public AudiobookInfo getMetadata(BookFileEntity bookFile, Path audioPath) throws Exception {
    if (bookFile.isFolderBased()) {
      return buildFolderBasedAudiobookInfo(bookFile, audioPath);
    } else {
      return buildSingleFileAudiobookInfo(bookFile, audioPath);
    }
  }

  private AudiobookInfo buildSingleFileAudiobookInfo(BookFileEntity bookFile, Path audioPath)
      throws Exception {
    AudiobookInfo.AudiobookInfoBuilder builder =
        AudiobookInfo.builder()
            .bookId(bookFile.getBook().getId())
            .bookFileId(bookFile.getId())
            .folderBased(false)
                .totalSizeBytes(bookFile.getFileSizeKb() != null ? bookFile.getFileSizeKb() * 1024 : Files.size(audioPath));

    if (bookFile.getDurationSeconds() != null) {
      BookMetadataEntity metadata = bookFile.getBook().getMetadata();
      String narrator = metadata != null ? metadata.getNarrator() : null;

      builder
          .narrator(narrator)
          .durationMs(bookFile.getDurationSeconds() * 1000)
          .bitrate(bookFile.getBitrate())
          .sampleRate(bookFile.getSampleRate())
          .channels(bookFile.getChannels())
          .codec(bookFile.getCodec());

      if (bookFile.getChapters() != null && !bookFile.getChapters().isEmpty()) {
        List<AudiobookChapter> chapters =
            bookFile.getChapters().stream()
                .map(
                    ch ->
                        AudiobookChapter.builder()
                            .index(ch.getIndex())
                            .title(ch.getTitle())
                            .startTimeMs(ch.getStartTimeMs())
                            .endTimeMs(ch.getEndTimeMs())
                            .durationMs(ch.getDurationMs())
                            .build())
                .toList();
        builder.chapters(chapters);
      } else {
        backfillChapters(bookFile, audioPath, builder);
      }

      if (metadata != null) {
        builder.title(metadata.getTitle());
        if (metadata.getAuthors() != null && !metadata.getAuthors().isEmpty()) {
          builder.author(metadata.getAuthors().getFirst().getName());
        }
      }

      return builder.build();
    }

    log.debug("No DB metadata found for audiobook {}, extracting from file", bookFile.getId());
    return extractSingleFileMetadata(builder, audioPath);
  }

  private AudiobookInfo buildFolderBasedAudiobookInfo(BookFileEntity bookFile, Path folderPath)
      throws Exception {
    AudiobookInfo.AudiobookInfoBuilder builder =
        AudiobookInfo.builder()
            .bookId(bookFile.getBook().getId())
            .bookFileId(bookFile.getId())
            .folderBased(true);

    List<Path> audioFiles = audioFileUtility.listAudioFiles(folderPath);
    if (audioFiles.isEmpty()) {
      throw new IllegalStateException("No audio files found in folder: " + folderPath);
    }

    TrackScanResult scan = scanTracks(audioFiles);
    applyAudioTechnicalProperties(builder, bookFile, scan);

    long totalSizeBytes = scan.tracks().stream()
                .mapToLong(t -> t.getFileSizeBytes() != null ? t.getFileSizeBytes() : 0)
                .sum();
    return builder
                .title(resolveFolderTitle(bookFile, scan))
                .author(resolveFolderAuthor(bookFile, scan))
                .durationMs(scan.totalDurationMs())
                .totalSizeBytes(totalSizeBytes > 0 ? totalSizeBytes : null)
                .tracks(scan.tracks())
                .build();
  }

  private record TrackScanResult(
      List<AudiobookTrack> tracks,
      long totalDurationMs,
      String title,
      String author,
      String narrator,
      Integer bitrate,
      String codec,
      Integer sampleRate,
      Integer channels) {}

  private record FirstTrackInfo(
      Integer bitrate,
      String codec,
      Integer sampleRate,
      Integer channels,
      String title,
      String author,
      String narrator) {
    static final FirstTrackInfo EMPTY =
        new FirstTrackInfo(null, null, null, null, null, null, null);
  }

  private TrackScanResult scanTracks(List<Path> audioFiles) throws IOException {
    List<AudiobookTrack> tracks = new ArrayList<>();
    long totalDurationMs = 0;
    FirstTrackInfo first = FirstTrackInfo.EMPTY;

    for (int i = 0; i < audioFiles.size(); i++) {
      Path trackPath = audioFiles.get(i);
      try {
        AudioFile audioFile = AudioFileIO.read(trackPath.toFile());
        AudioHeader header = audioFile.getAudioHeader();
        Tag tag = audioFile.getTag();

        long trackDurationMs = (long) (header.getPreciseTrackLength() * 1000);
        long fileSizeBytes = Files.size(trackPath);

        tracks.add(
            AudiobookTrack.builder()
                .index(i)
                .fileName(trackPath.getFileName().toString())
                .title(resolveTrackTitle(tag, trackPath))
                .durationMs(trackDurationMs)
                .fileSizeBytes(fileSizeBytes)
                .cumulativeStartMs(totalDurationMs)
                .build());

        totalDurationMs += trackDurationMs;

        if (i == 0) {
          first = readFirstTrackInfo(header, tag, trackPath);
        }
      } catch (Exception e) {
        log.warn("Failed to read track metadata: {}", trackPath, e);
        tracks.add(
            AudiobookTrack.builder()
                .index(i)
                .fileName(trackPath.getFileName().toString())
                .title(
                    audioFileUtility.getTrackTitleFromFilename(trackPath.getFileName().toString()))
                .fileSizeBytes(Files.size(trackPath))
                .cumulativeStartMs(totalDurationMs)
                .build());
      }
    }

    return new TrackScanResult(
        tracks,
        totalDurationMs,
        first.title(),
        first.author(),
        first.narrator(),
        first.bitrate(),
        first.codec(),
        first.sampleRate(),
        first.channels());
  }

  private String resolveTrackTitle(Tag tag, Path trackPath) {
    String trackTitle = null;
    if (tag != null) {
      trackTitle = tag.getFirst(FieldKey.TITLE);
    }
    if (trackTitle == null || trackTitle.isEmpty()) {
      trackTitle = audioFileUtility.getTrackTitleFromFilename(trackPath.getFileName().toString());
    }
    return trackTitle;
  }

  private FirstTrackInfo readFirstTrackInfo(AudioHeader header, Tag tag, Path trackPath) {
    Integer bitrate = safeBitrate(header, trackPath);
    String codec = safeEncodingType(header, trackPath);
    Integer sampleRate = safeSampleRate(header, trackPath);
    Integer channels = parseChannels(safeChannels(header, trackPath));
    String title = null;
    String author = null;
    String narrator = null;
    if (tag != null) {
      title = getTagValue(tag, FieldKey.ALBUM, FieldKey.TITLE);
      author = getTagValue(tag, FieldKey.ALBUM_ARTIST, FieldKey.ARTIST);
      narrator = getTagValue(tag, FieldKey.COMPOSER);
    }
    return new FirstTrackInfo(bitrate, codec, sampleRate, channels, title, author, narrator);
  }

  private void applyAudioTechnicalProperties(
      AudiobookInfo.AudiobookInfoBuilder builder, BookFileEntity bookFile, TrackScanResult scan) {
    BookMetadataEntity metadata = bookFile.getBook().getMetadata();
    if (metadata != null && metadata.getNarrator() != null) {
      builder.narrator(metadata.getNarrator());
    } else {
      builder.narrator(scan.narrator());
    }

    builder.bitrate(bookFile.getBitrate() != null ? bookFile.getBitrate() : scan.bitrate());
    builder.codec(bookFile.getCodec() != null ? bookFile.getCodec() : scan.codec());
    builder.sampleRate(
        bookFile.getSampleRate() != null ? bookFile.getSampleRate() : scan.sampleRate());
    builder.channels(bookFile.getChannels() != null ? bookFile.getChannels() : scan.channels());
  }

  private String resolveFolderTitle(BookFileEntity bookFile, TrackScanResult scan) {
    BookMetadataEntity metadata = bookFile.getBook().getMetadata();
    if (metadata != null && metadata.getTitle() != null) {
      return metadata.getTitle();
    }
    return scan.title();
  }

  private String resolveFolderAuthor(BookFileEntity bookFile, TrackScanResult scan) {
    BookMetadataEntity metadata = bookFile.getBook().getMetadata();
    if (metadata != null && metadata.getAuthors() != null && !metadata.getAuthors().isEmpty()) {
      return metadata.getAuthors().getFirst().getName();
    }
    return scan.author();
  }

  // jaudiotagger's AudioFileIO.read declares several distinct checked exception types
  // (CannotReadException, TagException, ReadOnlyFileException, InvalidAudioFrameException, IOException);
  // this "throws Exception" umbrella is shared with sibling methods in this class that are out of
  // scope for this change, so narrowing only here would be inconsistent without a wider signature pass.
  @SuppressWarnings("java:S112")
  private AudiobookInfo extractSingleFileMetadata(
      AudiobookInfo.AudiobookInfoBuilder builder, Path audioPath) throws Exception {
    AudioFile audioFile = AudioFileIO.read(audioPath.toFile());
    AudioHeader header = audioFile.getAudioHeader();
    Tag tag = audioFile.getTag();

    long durationMs = safeDurationMs(header, audioPath);
    builder
        .durationMs(durationMs)
        .bitrate(safeBitrate(header, audioPath))
        .codec(safeEncodingType(header, audioPath))
        .sampleRate(safeSampleRate(header, audioPath))
        .channels(parseChannels(safeChannels(header, audioPath)));

    if (tag != null) {
      builder
          .title(getTagValue(tag, FieldKey.TITLE, FieldKey.ALBUM))
          .author(getTagValue(tag, FieldKey.ARTIST, FieldKey.ALBUM_ARTIST))
          .narrator(getTagValue(tag, FieldKey.COMPOSER));
    }

    List<AudiobookChapter> chapters = extractChaptersFromFile(audioPath.toFile(), durationMs);
    builder.chapters(chapters);

    return builder.build();
  }

  private void backfillChapters(
      BookFileEntity bookFile, Path audioPath, AudiobookInfo.AudiobookInfoBuilder builder) {
    try {
      List<AudiobookChapter> chapters =
          extractChaptersFromFile(
              audioPath.toFile(),
              bookFile.getDurationSeconds() != null ? bookFile.getDurationSeconds() * 1000 : 0);
      builder.chapters(chapters);

      List<BookFileEntity.AudioFileChapter> entityChapters =
          chapters.stream()
              .map(
                  ch ->
                      BookFileEntity.AudioFileChapter.builder()
                          .index(ch.getIndex())
                          .title(ch.getTitle())
                          .startTimeMs(ch.getStartTimeMs())
                          .endTimeMs(ch.getEndTimeMs())
                          .durationMs(ch.getDurationMs())
                          .build())
              .toList();
      bookFile.setChapters(entityChapters);
      bookFile.setChapterCount(entityChapters.size());
      bookFileRepository.save(bookFile);
      log.info(
          "Backfilled {} chapters for audiobook file id={}",
          entityChapters.size(),
          bookFile.getId());
    } catch (Exception e) {
      log.debug(
          "Failed to backfill chapters for audiobook file id={}: {}",
          bookFile.getId(),
          e.getMessage());
    }
  }

  private List<AudiobookChapter> extractChaptersFromFile(File audioFile, long fallbackDurationMs) {
    List<AudiobookMetadata.ChapterInfo> extracted =
        audiobookMetadataExtractor.extractChaptersFromFile(audioFile);
    if (extracted != null && !extracted.isEmpty()) {
      return extracted.stream()
          .map(
              ch ->
                  AudiobookChapter.builder()
                      .index(ch.getIndex())
                      .title(ch.getTitle())
                      .startTimeMs(ch.getStartTimeMs())
                      .endTimeMs(ch.getEndTimeMs())
                      .durationMs(ch.getDurationMs())
                      .build())
          .toList();
    }

    List<AudiobookChapter> fallback = new ArrayList<>();
    fallback.add(
        AudiobookChapter.builder()
            .index(0)
            .title("Full Audiobook")
            .startTimeMs(0L)
            .endTimeMs(fallbackDurationMs)
            .durationMs(fallbackDurationMs)
            .build());
    return fallback;
  }

  // null is intentional here and distinct from an empty array: AudiobookReaderController checks
  // `coverData == null` to return 404 Not Found; an empty array would instead return 200 with no body.
  @SuppressWarnings("java:S1168")
  public byte[] getEmbeddedCoverArt(Path audioPath) {
    try {
      AudioFile audioFile = AudioFileIO.read(audioPath.toFile());
      Tag tag = audioFile.getTag();
      if (tag != null) {
        Artwork artwork = tag.getFirstArtwork();
        if (artwork != null) {
          return artwork.getBinaryData();
        }
      }
    } catch (Exception e) {
      log.debug("No embedded cover art found: {}", e.getMessage());
    }
    return null;
  }

  public String getCoverArtMimeType(Path audioPath) {
    try {
      AudioFile audioFile = AudioFileIO.read(audioPath.toFile());
      Tag tag = audioFile.getTag();
      if (tag != null) {
        Artwork artwork = tag.getFirstArtwork();
        if (artwork != null) {
          return resolveArtworkMimeType(artwork);
        }
      }
    } catch (Exception e) {
      log.debug("Could not determine cover art MIME type: {}", e.getMessage());
    }
    return MediaType.IMAGE_JPEG_VALUE;
  }

  private String resolveArtworkMimeType(Artwork artwork) {
    String mimeType = artwork.getMimeType();
    if (mimeType != null && !mimeType.isEmpty()) {
      return mimeType;
    }
    byte[] data = artwork.getBinaryData();
    if (data != null && data.length > 2) {
      if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
        return MediaType.IMAGE_JPEG_VALUE;
      } else if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50) {
        return "image/png";
      }
    }
    return MediaType.IMAGE_JPEG_VALUE;
  }

  private String getTagValue(Tag tag, FieldKey... keys) {
    for (FieldKey key : keys) {
      try {
        String value = tag.getFirst(key);
        if (value != null && !value.isEmpty()) {
          return value;
        }
      } catch (Exception _) {
        // ignore and try the next key
      }
    }
    return null;
  }

  private Integer parseChannels(String channels) {
    if (channels == null) return null;
    if (channels.toLowerCase(Locale.ROOT).contains("stereo")) return 2;
    if (channels.toLowerCase(Locale.ROOT).contains("mono")) return 1;
    try {
      return Integer.parseInt(NON_DIGIT_PATTERN.matcher(channels).replaceAll(""));
    } catch (NumberFormatException _) {
      return null;
    }
  }

    private long safeDurationMs(AudioHeader header, Path audioPath) {
        try {
            return (long) (header.getPreciseTrackLength() * 1000);
        } catch (RuntimeException e) {
            log.warn("Failed to read track duration from {}", audioPath, e);
            return 0L;
        }
    }

    private Integer safeBitrate(AudioHeader header, Path audioPath) {
        try {
            long bitrateValue = header.getBitRateAsNumber();
            return bitrateValue > 0 ? (int) bitrateValue : null;
        } catch (RuntimeException e) {
            log.debug("Audio header has no bitrate for {}", audioPath, e);
            return null;
        }
    }

    private Integer safeSampleRate(AudioHeader header, Path audioPath) {
        try {
            int sampleRate = header.getSampleRateAsNumber();
            return sampleRate > 0 ? sampleRate : null;
        } catch (RuntimeException e) {
            log.debug("Audio header has no sample rate for {}", audioPath, e);
            return null;
        }
    }

    private String safeEncodingType(AudioHeader header, Path audioPath) {
        try {
            String encodingType = header.getEncodingType();
            return encodingType != null && !encodingType.isBlank() ? encodingType : null;
        } catch (RuntimeException e) {
            log.debug("Audio header has no encoding type for {}", audioPath, e);
            return null;
        }
    }

    private String safeChannels(AudioHeader header, Path audioPath) {
        try {
            return header.getChannels();
        } catch (RuntimeException e) {
            log.debug("Audio header has no channel info for {}", audioPath, e);
            return null;
        }
    }
}
