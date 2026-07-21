package org.booklore.service.metadata.writer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudiobookMetadataWriter implements MetadataWriter {

    static {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.WARNING);
    }

    private final AppSettingService appSettingService;
    private final FileService fileService;

    @Override
    public void saveMetadataToFile(File audioFile, BookMetadataEntity metadata, String thumbnailUrl, MetadataClearFlags clear) {
        if (audioFile.isDirectory()) {
            saveFolderCover(audioFile, thumbnailUrl);
            return;
        }

        if (!shouldSaveMetadataToFile(audioFile)) {
            return;
        }

        File backupFile = new File(audioFile.getParentFile(), audioFile.getName() + ".bak");
        try {
            Files.copy(audioFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            log.warn("Failed to create backup of audiobook {}: {}", audioFile.getName(), ex.getMessage());
            return;
        }

        try {
            AudioFile f = AudioFileIO.read(audioFile);
            Tag tag = f.getTagOrCreateAndSetDefault();

            if (applyAudiobookTags(tag, metadata, thumbnailUrl, clear, audioFile.getName())) {
                f.commit();
                log.info("Metadata updated in audiobook: {}", audioFile.getName());
            } else {
                log.debug("No changes detected. Skipping audiobook write for: {}", audioFile.getName());
            }

        } catch (Exception e) {
            log.warn("Failed to write metadata to audiobook file {}: {}", audioFile.getName(), e.getMessage(), e);
            restoreAudiobookFromBackup(backupFile, audioFile);
        } finally {
            deleteAudiobookBackup(backupFile, audioFile.getName());
        }
    }

    private void saveFolderCover(File audioFile, String thumbnailUrl) {
        if (StringUtils.isNotBlank(thumbnailUrl)) {
            byte[] coverData = loadImage(thumbnailUrl);
            if (coverData != null) {
                saveCoverToFolder(audioFile.toPath(), coverData);
            }
        }
    }

    private boolean applyAudiobookTags(Tag tag, BookMetadataEntity metadata, String thumbnailUrl, MetadataClearFlags clear, String fileName) {
        boolean[] hasChanges = {false};
        MetadataCopyHelper helper = new MetadataCopyHelper(metadata);

        helper.copyTitle(isClear(clear, MetadataClearFlags::isTitle), val -> {
            setTagField(tag, FieldKey.ALBUM, val, hasChanges);
            setTagField(tag, FieldKey.TITLE, val, hasChanges);
        });

        helper.copyAuthors(isClear(clear, MetadataClearFlags::isAuthors), authors -> {
            String authorStr = authorsOf(authors);
            setTagField(tag, FieldKey.ALBUM_ARTIST, authorStr, hasChanges);
            setTagField(tag, FieldKey.ARTIST, authorStr, hasChanges);
        });

        if (StringUtils.isNotBlank(metadata.getNarrator())) {
            setTagField(tag, FieldKey.COMPOSER, metadata.getNarrator(), hasChanges);
        }

        helper.copyDescription(isClear(clear, MetadataClearFlags::isDescription), val ->
                setTagField(tag, FieldKey.COMMENT, val, hasChanges));

        helper.copyPublisher(isClear(clear, MetadataClearFlags::isPublisher), val ->
                setTagField(tag, FieldKey.RECORD_LABEL, val, hasChanges));

        helper.copyPublishedDate(isClear(clear, MetadataClearFlags::isPublishedDate), val ->
                setTagField(tag, FieldKey.YEAR, yearOf(val), hasChanges));

        helper.copyCategories(isClear(clear, MetadataClearFlags::isCategories), categories ->
                setTagField(tag, FieldKey.GENRE, genreOf(categories), hasChanges));

        helper.copyLanguage(isClear(clear, MetadataClearFlags::isLanguage), val ->
                setTagField(tag, FieldKey.LANGUAGE, val, hasChanges));

        helper.copySeriesName(isClear(clear, MetadataClearFlags::isSeriesName), val ->
                setTagField(tag, FieldKey.GROUPING, val, hasChanges));

        helper.copySeriesNumber(isClear(clear, MetadataClearFlags::isSeriesNumber), val ->
                setTagField(tag, FieldKey.TRACK, trackNumberOf(val), hasChanges));

        helper.copySeriesTotal(isClear(clear, MetadataClearFlags::isSeriesTotal), val ->
                setTagField(tag, FieldKey.TRACK_TOTAL, trackTotalOf(val), hasChanges));

        applyCoverArtIfProvided(tag, thumbnailUrl, fileName, hasChanges);

        return hasChanges[0];
    }

    private static boolean isClear(MetadataClearFlags clear, Predicate<MetadataClearFlags> flag) {
        return clear != null && flag.test(clear);
    }

    private static String yearOf(LocalDate publishedDate) {
        return publishedDate != null ? String.valueOf(publishedDate.getYear()) : null;
    }

    private static String authorsOf(Set<String> authors) {
        return authors != null ? String.join("; ", authors) : null;
    }

    private static String genreOf(Set<String> categories) {
        return categories != null && !categories.isEmpty() ? String.join("; ", categories) : null;
    }

    private static String trackNumberOf(Float seriesNumber) {
        return seriesNumber != null ? String.format("%.0f", seriesNumber) : null;
    }

    private static String trackTotalOf(Integer seriesTotal) {
        return seriesTotal != null ? String.valueOf(seriesTotal) : null;
    }

    private void applyCoverArtIfProvided(Tag tag, String thumbnailUrl, String fileName, boolean[] hasChanges) {
        if (StringUtils.isNotBlank(thumbnailUrl)) {
            byte[] coverData = loadImage(thumbnailUrl);
            if (coverData != null) {
                setCoverArtwork(tag, coverData, fileName, hasChanges);
            }
        }
    }

    private void restoreAudiobookFromBackup(File backupFile, File audioFile) {
        if (backupFile.exists()) {
            try {
                Files.copy(backupFile.toPath(), audioFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("Restored audiobook from backup: {}", audioFile.getName());
            } catch (IOException io) {
                log.error("Failed to restore audiobook from backup for {}: {}", audioFile.getName(), io.getMessage(), io);
            }
        }
    }

    private void deleteAudiobookBackup(File backupFile, String fileName) {
        if (backupFile.exists()) {
            try {
                Files.delete(backupFile.toPath());
            } catch (IOException ex) {
                log.warn("Failed to delete backup for {}: {}", fileName, ex.getMessage());
            }
        }
    }

    private void setCoverArtwork(Tag tag, byte[] coverData, String fileName, boolean[] hasChanges) {
        try {
            tag.deleteArtworkField();
            Artwork artwork = ArtworkFactory.getNew();
            artwork.setBinaryData(coverData);
            artwork.setMimeType(detectMimeType(coverData));
            tag.setField(artwork);
            hasChanges[0] = true;
        } catch (Exception e) {
            log.warn("Failed to set cover art for {}: {}", fileName, e.getMessage());
        }
    }

    public void saveCoverToFolder(Path folderPath, byte[] coverData) {
        if (coverData == null || coverData.length == 0 || folderPath == null) {
            return;
        }

        try {
            String extension = detectImageExtension(coverData);
            String filename = "cover" + extension;
            Path coverPath = folderPath.resolve(filename);

            deleteExistingCovers(folderPath);

            Files.write(coverPath, coverData);
            log.info("Cover image saved to folder: {}", coverPath);
        } catch (IOException e) {
            log.warn("Failed to save cover to folder {}: {}", folderPath, e.getMessage());
        }
    }

    private void deleteExistingCovers(Path folderPath) throws IOException {
        String[] coverNames = {"cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png"};
        for (String name : coverNames) {
            Path existing = folderPath.resolve(name);
            Files.deleteIfExists(existing);
        }
    }

    private String detectImageExtension(byte[] data) {
        if (data.length > 3 && data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return ".png";
        }
        return ".jpg";
    }

    private void setTagField(Tag tag, FieldKey key, String value, boolean[] hasChanges) {
        try {
            String existingValue = tag.getFirst(key);
            if (value == null || value.isBlank()) {
                if (StringUtils.isNotBlank(existingValue)) {
                    tag.deleteField(key);
                    hasChanges[0] = true;
                }
            } else if (!value.equals(existingValue)) {
                tag.setField(key, value);
                hasChanges[0] = true;
            }
        } catch (Exception e) {
            log.debug("Failed to set tag field {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void replaceCoverImageFromBytes(BookEntity bookEntity, byte[] coverData) {
        if (coverData == null || coverData.length == 0) {
            log.warn("Cover update failed: empty or null byte array.");
            return;
        }

        BookFileEntity audioFile = getAudiobookFile(bookEntity);
        if (audioFile == null) {
            return;
        }

        if (audioFile.isFolderBased()) {
            Path folderPath = audioFile.getFullFilePath();
            saveCoverToFolder(folderPath, coverData);
        } else {
            File file = audioFile.getFullFilePath().toFile();
            if (!shouldSaveMetadataToFile(file)) {
                return;
            }
            replaceCoverImageInternal(file, coverData, "byte array");
        }
    }

    @Override
    public void replaceCoverImageFromUpload(BookEntity bookEntity, MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            log.warn("Cover upload failed: empty or null file.");
            return;
        }

        try {
            byte[] coverData = multipartFile.getBytes();
            replaceCoverImageFromBytes(bookEntity, coverData);
        } catch (IOException e) {
            log.warn("Failed to read uploaded cover image: {}", e.getMessage(), e);
        }
    }

    @Override
    public void replaceCoverImageFromUrl(BookEntity bookEntity, String url) {
        if (url == null || url.isBlank()) {
            log.warn("Cover update via URL failed: empty or null URL.");
            return;
        }

        byte[] coverData = loadImage(url);
        if (coverData == null) {
            log.warn("Failed to load image from URL: {}", url);
            return;
        }

        replaceCoverImageFromBytes(bookEntity, coverData);
    }

    private void replaceCoverImageInternal(File audioFile, byte[] coverData, String source) {
        try {
            AudioFile f = AudioFileIO.read(audioFile);
            Tag tag = f.getTagOrCreateAndSetDefault();

            tag.deleteArtworkField();
            Artwork artwork = ArtworkFactory.getNew();
            artwork.setBinaryData(coverData);
            artwork.setMimeType(detectMimeType(coverData));
            tag.setField(artwork);
            f.commit();

            log.info("Cover image updated in audiobook from {}: {}", source, audioFile.getName());
        } catch (Exception e) {
            log.warn("Failed to update audiobook cover image from {}: {}", source, e.getMessage(), e);
        }
    }

    private BookFileEntity getAudiobookFile(BookEntity bookEntity) {
        if (bookEntity == null || bookEntity.getBookFiles() == null) {
            return null;
        }
        return bookEntity.getBookFiles().stream()
                .filter(bf -> bf.getBookType() == BookFileType.AUDIOBOOK)
                .findFirst()
                .orElse(null);
    }

    @Override
    public BookFileType getSupportedBookType() {
        return BookFileType.AUDIOBOOK;
    }

    @Override
    public boolean shouldSaveMetadataToFile(File audioFile) {
        MetadataPersistenceSettings.SaveToOriginalFile settings = appSettingService.getAppSettings()
                .getMetadataPersistenceSettings().getSaveToOriginalFile();

        MetadataPersistenceSettings.FormatSettings audiobookSettings = settings.getAudiobook();
        if (audiobookSettings == null || !audiobookSettings.isEnabled()) {
            log.debug("Audiobook metadata writing is disabled. Skipping: {}", audioFile.getName());
            return false;
        }

        long fileSizeInMb = audioFile.length() / (1024 * 1024);
        if (fileSizeInMb > audiobookSettings.getMaxFileSizeInMb()) {
            log.info("Audiobook file {} ({} MB) exceeds max size limit ({} MB). Skipping metadata write.",
                    audioFile.getName(), fileSizeInMb, audiobookSettings.getMaxFileSizeInMb());
            return false;
        }

        return true;
    }

    // S1168: null here is a load-failure sentinel that callers check via `!= null`/`== null` before
    // writing artwork/cover bytes; an empty array would be silently treated as a successful load and
    // written out as bogus zero-length artwork/cover data.
    @SuppressWarnings("java:S1168")
    private byte[] loadImage(String imageUrl) {
        try {
            return encodeImage(fileService.downloadImageFromUrl(imageUrl));
        } catch (IOException | SecurityException e) {
            log.warn("Failed to load image from {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    private byte[] encodeImage(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("No PNG writer available");
            }
            return output.toByteArray();
        }
    }

    private String detectMimeType(byte[] data) {
        if (data.length > 3 && data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return "image/png";
        }
        return "image/jpeg";
    }
}
