package org.booklore.util;

import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.CoverCroppingSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.CoverSaveOutcome;
import org.booklore.service.appsettings.AppSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
@Service
public class FileService {

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final AppSettingService appSettingService;
    private final RestTemplate noRedirectRestTemplate;

    private static final int MAX_REDIRECTS = 5;

    /**
     * The size a stored cover is capped at: {@link #saveCoverImages} scales anything larger down to
     * fit inside this box, so no cover on disk is ever bigger.
     * <p>
     * Public because a producer that <em>renders</em> its cover rather than reading it - see
     * {@code PdfProcessor} - can render straight to this size instead of producing a full-resolution
     * raster for this class to throw most of away. That is not merely an optimisation: an A0 page
     * rendered at full DPI exceeds {@link #MAX_IMAGE_PIXELS} and was rejected outright as a
     * decompression bomb. Sharing the constant is what stops the renderer's idea of "big enough for
     * a cover" drifting from this class's.
     */
    public static final int MAX_ORIGINAL_WIDTH = 1000;
    /** @see #MAX_ORIGINAL_WIDTH */
    public static final int MAX_ORIGINAL_HEIGHT = 1500;

    private static final double TARGET_COVER_ASPECT_RATIO = 1.5;
    private static final int SMART_CROP_COLOR_TOLERANCE = 30;
    private static final double SMART_CROP_MARGIN_PERCENT = 0.02;

    // @formatter:off
    private static final String IMAGES_DIR                    = "images";
    private static final String AUTHOR_IMAGES_DIR             = "author-images";
    private static final String BACKGROUNDS_DIR               = "backgrounds";
    private static final String ICONS_DIR                     = "icons";
    private static final String SVG_DIR                       = "svg";
    private static final String THUMBNAIL_FILENAME            = "thumbnail.jpg";
    private static final String COVER_FILENAME                = "cover.jpg";
    private static final String AUTHOR_PHOTO_FILENAME         = "photo.jpg";
    private static final String AUTHOR_THUMBNAIL_FILENAME     = "thumbnail.jpg";
    private static final String AUDIOBOOK_THUMBNAIL_FILENAME  = "audiobook-thumbnail.jpg";
    private static final String AUDIOBOOK_COVER_FILENAME      = "audiobook-cover.jpg";
    private static final String JPEG_MIME_TYPE                = "image/jpeg";
    private static final String PNG_MIME_TYPE                 = "image/png";
    private static final long   MAX_FILE_SIZE_BYTES           = 5L * 1024 * 1024;
    // 20 MP covers legitimate book covers and author photos with a comfortable safety margin.
    private static final long   MAX_IMAGE_PIXELS              = 20_000_000L;
    private static final int    THUMBNAIL_WIDTH               = 250;
    private static final int    THUMBNAIL_HEIGHT              = 350;
    private static final int    SQUARE_THUMBNAIL_SIZE         = 250;
    private static final int    MAX_SQUARE_SIZE               = 1000;
    private static final String IMAGE_FORMAT                  = "JPEG";
    private static final String FAILED_TO_SAVE_COVER_IMAGES_MESSAGE           = "Failed to save cover images";
    private static final String FAILED_TO_SAVE_AUDIOBOOK_COVER_IMAGES_MESSAGE = "Failed to save audiobook cover images";
    // @formatter:on

    // ========================================
    // PATH UTILITIES
    // ========================================

    public String getImagesFolder(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId)).toString();
    }

    public String getThumbnailFile(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId), THUMBNAIL_FILENAME).toString();
    }

    public String getCoverFile(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId), COVER_FILENAME).toString();
    }

    public String getAudiobookThumbnailFile(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId), AUDIOBOOK_THUMBNAIL_FILENAME).toString();
    }

    public String getAudiobookCoverFile(long bookId) {
        return Paths.get(appProperties.getPathConfig(), IMAGES_DIR, String.valueOf(bookId), AUDIOBOOK_COVER_FILENAME).toString();
    }

    public String getAuthorImagesFolder(long authorId) {
        return Paths.get(appProperties.getPathConfig(), AUTHOR_IMAGES_DIR, String.valueOf(authorId)).toString();
    }

    public String getAuthorPhotoFile(long authorId) {
        return Paths.get(appProperties.getPathConfig(), AUTHOR_IMAGES_DIR, String.valueOf(authorId), AUTHOR_PHOTO_FILENAME).toString();
    }

    public String getAuthorThumbnailFile(long authorId) {
        return Paths.get(appProperties.getPathConfig(), AUTHOR_IMAGES_DIR, String.valueOf(authorId), AUTHOR_THUMBNAIL_FILENAME).toString();
    }

    // Author thumbnails live in one subdirectory per author id under AUTHOR_IMAGES_DIR, so a single
    // directory listing yields every author that has a photo — far cheaper than a Files.exists per
    // author on the hot listing path. Returns the ids whose thumbnail file is actually present.
    public Set<Long> listAuthorIdsWithThumbnail() {
        Path root = Paths.get(appProperties.getPathConfig(), AUTHOR_IMAGES_DIR);
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                long authorId;
                try {
                    authorId = Long.parseLong(dir.getFileName().toString());
                } catch (NumberFormatException _) {
                    return;
                }
                if (Files.exists(dir.resolve(AUTHOR_THUMBNAIL_FILENAME))) {
                    ids.add(authorId);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list author thumbnail directory {}: {}", root, e.getMessage());
            return Set.of();
        }
        return Set.copyOf(ids);
    }

    public String getBackgroundsFolder(Long userId) {
        if (userId != null) {
            return Paths.get(appProperties.getPathConfig(), BACKGROUNDS_DIR, "user-" + userId).toString();
        }
        return Paths.get(appProperties.getPathConfig(), BACKGROUNDS_DIR).toString();
    }

    public String getBackgroundsFolder() {
        return getBackgroundsFolder(null);
    }

    public static String getBackgroundUrl(String filename, Long userId) {
        if (userId != null) {
            return Paths.get("/", BACKGROUNDS_DIR, "user-" + userId, filename).toString().replace("\\", "/");
        }
        return Paths.get("/", BACKGROUNDS_DIR, filename).toString().replace("\\", "/");
    }

    public String getBookMetadataBackupPath(long bookId) {
        return Paths.get(appProperties.getPathConfig(), "metadata_backup", String.valueOf(bookId)).toString();
    }

    public String getPdfCachePath() {
        return Paths.get(appProperties.getPathConfig(), "pdf_cache").toString();
    }

    public String getTempBookdropCoverImagePath(long bookdropFileId) {
        return Paths.get(appProperties.getPathConfig(), "bookdrop_temp", bookdropFileId + ".jpg").toString();
    }

    private String getSystemSearchPath() {
        // Search first in the application folder's "local" `bin`.
        StringBuilder localPaths = new StringBuilder("bin");

        // Then, check the legacy "tools" path from previous app versions.
        localPaths.append(File.pathSeparator).append(Path.of(appProperties.getPathConfig(), "tools"));

        // If not found in those, then search the system $PATH.
        String systemSearchPath = System.getenv("PATH");
        if (systemSearchPath != null) {
            localPaths.append(File.pathSeparator).append(systemSearchPath);
        }

        return localPaths.toString();
    }

    public Path findSystemFile(String filename) {
        String[] searchPaths = getSystemSearchPath().split(":");

        for (String path : searchPaths) {
            Path possiblePath = Paths
                    .get(path)
                    .resolve(filename)
                    .toAbsolutePath()
                    .normalize();

            if (Files.isRegularFile(possiblePath)) {
                return possiblePath;
            }
        }

        return null;
    }


    // ========================================
    // VALIDATION
    // ========================================

    private long getMaxFileUploadSizeMb() {
        AppSettings appSettings = this.appSettingService.getAppSettings();

        Integer maxFileUploadSizeMb = appSettings.getMaxFileUploadSizeInMb();

        if (maxFileUploadSizeMb == null) {
            log.warn("Max File Upload Size is unset, cannot continue");
            throw ApiError.INTERNAL_SERVER_ERROR.createException("Max File Upload Size is Unset");
        }

        return maxFileUploadSizeMb.longValue();
    }

    private void validateCoverFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Content type is required");
        }
        String lowerType = contentType.toLowerCase();
        if (!lowerType.startsWith(JPEG_MIME_TYPE) && !lowerType.startsWith(PNG_MIME_TYPE)) {
            throw new IllegalArgumentException("Only JPEG and PNG files are allowed");
        }
        long maxSizeMb = getMaxFileUploadSizeMb();
        long maxFileSize = maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxFileSize) {
            throw ApiError.FILE_TOO_LARGE.createException(maxSizeMb);
        }
    }

    // ========================================
    // IMAGE OPERATIONS
    // ========================================

    public static BufferedImage readImage(InputStream inputStream) throws IOException {
        return readImage(inputStream.readAllBytes());
    }

    public static BufferedImage readImage(byte[] imageData) throws IOException {
        if (imageData == null || imageData.length == 0) {
            throw new IOException("Image data is null or empty");
        }

        // MemoryCacheImageInputStream, not ImageIO.createImageInputStream: the latter honours the
        // global ImageIO.setUseCache (true by default) and would spool this array through a temp
        // file in java.io.tmpdir. We are decoding from a byte[] that is already in memory, so the
        // disk cache buys nothing and costs everything - on a full disk it fails the decode with
        // IIOException("Can't create cache file!"), which every caller above reads as "these bytes
        // are not an image" and the INPX probe records as a permanent verdict on the book. See
        // ImageIoConfig, which turns the global off for the other ImageIO entry points; naming the
        // stream here means this one does not depend on that having happened first.
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(imageData))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);

                    long pixelCount = (long) width * height;
                    if (pixelCount > MAX_IMAGE_PIXELS) {
                        throw new IOException(String.format("Rejected image: dimensions %dx%d (%d pixels) exceed limit %d — possible decompression bomb",
                                width, height, pixelCount, MAX_IMAGE_PIXELS));
                    }

                    return reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ImageIO decode failed (possibly unsupported format): " + e.getMessage(), e);
        }

        throw new IOException("Unable to decode image, likely unsupported format");
    }

    /**
     * Scales an image to {@code width} x {@code height}.
     * <p>
     * Both are floored at 1. Scaling a cover down preserves its aspect ratio, and any ratio extreme
     * enough rounds the short side to zero: a 20000x1 cover scaled to fit 1000 wide computes a
     * height of 0, and {@code new BufferedImage(1000, 0, ...)} throws
     * {@code IllegalArgumentException: Width (1000) and height (0) cannot be <= 0}. That is a
     * property of the bytes, so it recurred on every retry forever, and it was reported as a
     * transient write failure - leaving the INPX probe re-reading the same archive on every scan.
     * A one-pixel-tall cover is a silly image, but it is an image, and the caller asked for a
     * picture rather than an exception.
     */
    public static BufferedImage resizeImage(BufferedImage originalImage, int width, int height) {
        int targetWidth = Math.max(1, width);
        int targetHeight = Math.max(1, height);
        Image tmp = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resizedImage.createGraphics();
        g2d.drawImage(tmp, 0, 0, null);
        g2d.dispose();
        return resizedImage;
    }

    public static void saveImage(byte[] imageData, String filePath) throws IOException {
        BufferedImage originalImage = null;
        try {
            originalImage = readImage(imageData);
            if (originalImage == null) {
                log.warn("Skipping saveImage for {}: decoded image is null", filePath);
                return;
            }
            File outputFile = new File(filePath);
            File parentDir = outputFile.getParentFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + parentDir);
            }
            ImageIO.write(originalImage, IMAGE_FORMAT, outputFile);
            log.info("Image saved successfully to: {}", filePath);
        } finally {
            if (originalImage != null) {
                originalImage.flush(); // Release native resources
            }
        }
    }

    public BufferedImage downloadImageFromUrl(String imageUrl) throws IOException {
        try {
            return downloadImageFromUrlInternal(imageUrl);
        } catch (Exception e) {
            log.warn("Failed to download image from {}: {}", imageUrl, e.getMessage());
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            if (e instanceof SecurityException securityException) {
                throw securityException;
            }
            throw new IOException("Failed to download image from " + imageUrl + ": " + e.getMessage(), e);
        }
    }

    private BufferedImage downloadImageFromUrlInternal(String imageUrl) throws IOException {
        String currentUrl = imageUrl;
        int redirectCount = 0;

        while (redirectCount <= MAX_REDIRECTS) {
            URI uri = URI.create(currentUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Only HTTP and HTTPS protocols are allowed");
            }

            String host = uri.getHost();
            if (host == null) {
                throw new IOException("Invalid URL: no host found in " + currentUrl);
            }

            NetworkAddressValidator.validateExternalHttpUrl(currentUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, "BookLore/1.0 (Book and Comic Metadata Fetcher; +https://github.com/booklore-app/booklore)");
            headers.set(HttpHeaders.ACCEPT, "image/*");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.debug("Downloading image from: {}", currentUrl);

            ResponseEntity<byte[]> response = noRedirectRestTemplate.exchange(
                    currentUrl,
                    HttpMethod.GET,
                    entity,
                    byte[].class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return readImage(response.getBody());
            } else if (response.getStatusCode().is3xxRedirection()) {
                String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                if (location == null) {
                    throw new IOException("Redirection response without Location header");
                }
                URI redirectUri = uri.resolve(location);
                NetworkAddressValidator.validateExternalHttpUrl(redirectUri.toString());

                // When a CDN redirects to a raw IP (e.g. CloudFront -> 3.168.64.124),
                // the Host header would become the bare IP, which the CDN rejects with
                // 400. Rewrite the URL to keep the previous hostname so the JDK
                // HttpClient sets the correct Host header automatically.
                if (isRawIpAddress(redirectUri.getHost())) {
                    try {
                        redirectUri = new URI(
                                redirectUri.getScheme(),
                                redirectUri.getUserInfo(),
                                host,
                                redirectUri.getPort(),
                                redirectUri.getPath(),
                                redirectUri.getQuery(),
                                redirectUri.getFragment()
                        );
                    } catch (URISyntaxException e) {
                        throw new IOException("Invalid redirect URI: " + e.getMessage(), e);
                    }
                }

                currentUrl = redirectUri.toString();
                redirectCount++;
            } else {
                throw new IOException("Failed to download image. HTTP Status: " + response.getStatusCode());
            }
        }

        throw new IOException("Too many redirects (max " + MAX_REDIRECTS + ")");
    }

    private boolean isRawIpAddress(String host) {
        if (host == null) {
            return false;
        }
        // IPv6 in URI brackets
        if (host.startsWith("[")) {
            return true;
        }
        // IPv4: all segments are digits
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            for (String part : parts) {
                if (!part.chars().allMatch(Character::isDigit)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    // ========================================
    // COVER OPERATIONS
    // ========================================

    public void createThumbnailFromFile(long bookId, MultipartFile file) {
        try {
            validateCoverFile(file);
            BufferedImage originalImage;
            try (InputStream inputStream = file.getInputStream()) {
                originalImage = readImage(inputStream);
            }
            if (originalImage == null) {
                log.warn("Could not decode image from file, skipping thumbnail creation for book: {}", bookId);
                return;
            }
            boolean success = saveCoverImages(originalImage, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException(FAILED_TO_SAVE_COVER_IMAGES_MESSAGE);
            }
            originalImage.flush(); // Release resources after processing
            log.info("Cover images created and saved for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating the thumbnail: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public void createThumbnailFromBytes(long bookId, byte[] imageBytes) {
        try {
            BufferedImage originalImage = readImage(imageBytes);
            if (originalImage == null) {
                log.warn("Skipping thumbnail creation for book {}: image decode failed", bookId);
                return;
            }
            boolean success = saveCoverImages(originalImage, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException(FAILED_TO_SAVE_COVER_IMAGES_MESSAGE);
            }
            originalImage.flush();
            log.info("Cover images created and saved from bytes for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating thumbnail from bytes: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    /**
     * Decodes {@code imageData} and writes it as the book's cover and thumbnail.
     * <p>
     * This is the one place that turns extracted cover bytes into cover files, so callers holding
     * bytes from {@code BookFileProcessor#extractCover} - the processors themselves and
     * {@code BookCoverService} alike - do not each need their own copy of the decode-and-save
     * dance, and none has to know what an image format is.
     * <p>
     * Returns a {@link CoverSaveOutcome} rather than throwing: both failure modes are ordinary
     * outcomes for a cover pulled out of an arbitrary book file, and callers need to react to them,
     * not be interrupted by them. It reports which of the two happened rather than a bare boolean
     * because they are not the same kind of fact, and a caller that persists a permanent marker
     * needs to tell them apart - see {@link CoverSaveOutcome}.
     * <p>
     * The work is therefore in three guarded steps, not two, and the middle one is the one that was
     * missing. {@code saveCoverImages} does image <em>processing</em> - convert, crop, resize - as
     * well as writing, and both used to sit under a single {@code catch -> WRITE_FAILED}. Processing
     * failures are facts about the bytes, exactly like decode failures: a 20000x1 cover decodes
     * fine, then scales to a height of zero and throws, identically, forever. Calling that transient
     * left the INPX probe re-reading the same archive on every scan - the very defect the marker
     * exists to prevent, alive through the adjacent door. Decode, prepare, write are guarded apart
     * because a failure in each says something different, and one {@code catch} around them cannot
     * say which.
     * <p>
     * {@code OutOfMemoryError} is caught across all three and is deliberately <em>not</em> permanent:
     * heap exhaustion is a fact about this moment, and the same bytes may well decode on a quieter
     * server. It is caught at all because the alternative is worse than the usual argument against
     * catching it - {@code PdfProcessor} already degrades gracefully on OOM while rendering, and
     * once the render moved behind this method's decode, an OOM that used to cost one cover instead
     * escaped {@code regenerateCover} as an HTTP 500.
     */
    public CoverSaveOutcome saveCoverImageFromBytes(long bookId, byte[] imageData) {
        BufferedImage originalImage;
        try {
            originalImage = readImage(imageData);
        } catch (OutOfMemoryError _) {
            log.error("Out of memory decoding cover image for book {}; leaving it eligible for a retry", bookId);
            return CoverSaveOutcome.SAVE_FAILED;
        } catch (Exception e) {
            // readImage throws (it does not return null) for empty input, for bytes no ImageIO
            // reader claims - an SVG cover is the common one - and for dimensions that look like a
            // decompression bomb. All three are properties of these bytes: the next probe re-reads
            // the same archive entry and fails identically, so retrying forever is pointless.
            log.warn("Cover image for book {} could not be decoded (possibly SVG or an unsupported format): {}", bookId, e.getMessage());
            return CoverSaveOutcome.UNDECODABLE;
        }
        if (originalImage == null) {
            // Defensive: readImage's contract is throw-or-return-an-image, but treating a null as
            // "these bytes are not an image" keeps this in step with that contract if it ever slips.
            log.warn("Cover image for book {} decoded to nothing (possibly SVG or an unsupported format)", bookId);
            return CoverSaveOutcome.UNDECODABLE;
        }

        PreparedCover prepared;
        try {
            prepared = prepareCoverImages(originalImage, getCoverCroppingSettings());
        } catch (OutOfMemoryError _) {
            log.error("Out of memory processing cover image for book {}; leaving it eligible for a retry", bookId);
            return CoverSaveOutcome.SAVE_FAILED;
        } catch (Exception e) {
            log.warn("Cover image for book {} decoded but could not be processed into a cover: {}", bookId, e.getMessage(), e);
            return CoverSaveOutcome.UNDECODABLE;
        } finally {
            originalImage.flush();
        }

        try {
            return writeCoverImages(prepared, bookId) ? CoverSaveOutcome.SAVED : CoverSaveOutcome.SAVE_FAILED;
        } catch (OutOfMemoryError _) {
            log.error("Out of memory writing cover image for book {}; leaving it eligible for a retry", bookId);
            return CoverSaveOutcome.SAVE_FAILED;
        } catch (Exception e) {
            log.error("Failed to write cover image for book {}: {}", bookId, e.getMessage(), e);
            return CoverSaveOutcome.SAVE_FAILED;
        } finally {
            prepared.flush();
        }
    }

    public void createThumbnailFromUrl(long bookId, String imageUrl) {
        try {
            BufferedImage originalImage = downloadImageFromUrl(imageUrl);
            if (originalImage == null) {
                log.warn("Skipping thumbnail creation for book {}: download/decode failed", bookId);
                return;
            }
            boolean success = saveCoverImages(originalImage, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException(FAILED_TO_SAVE_COVER_IMAGES_MESSAGE);
            }
            originalImage.flush();
            log.info("Cover images created and saved from URL for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating thumbnail from URL: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    // ========================================
    // AUTHOR PHOTO OPERATIONS
    // ========================================

    public void createAuthorThumbnailFromUrl(long authorId, String imageUrl) {
        try {
            BufferedImage originalImage = downloadImageFromUrl(imageUrl);
            if (originalImage == null) {
                log.warn("Skipping author thumbnail creation for author {}: download/decode failed", authorId);
                return;
            }
            boolean success = saveAuthorImages(originalImage, authorId);
            if (!success) {
                log.warn("Failed to save author images for author ID: {}", authorId);
            }
            originalImage.flush();
            log.info("Author images created and saved from URL for author ID: {}", authorId);
        } catch (Exception e) {
            log.warn("Failed to create author thumbnail from URL for author {}: {}", authorId, e.getMessage());
        }
    }

    public boolean saveAuthorImages(BufferedImage sourceImage, long authorId) throws IOException {
        BufferedImage rgbImage = null;
        BufferedImage resized = null;
        BufferedImage thumb = null;
        try {
            String folderPath = getAuthorImagesFolder(authorId);
            File folder = new File(folderPath);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
            }

            rgbImage = new BufferedImage(
                    sourceImage.getWidth(),
                    sourceImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(sourceImage, 0, 0, Color.WHITE, null);
            g.dispose();

            double scale = Math.min(
                    (double) MAX_ORIGINAL_WIDTH / rgbImage.getWidth(),
                    (double) MAX_ORIGINAL_HEIGHT / rgbImage.getHeight()
            );
            if (scale < 1.0) {
                resized = resizeImage(rgbImage, (int) (rgbImage.getWidth() * scale), (int) (rgbImage.getHeight() * scale));
                rgbImage.flush();
                rgbImage = resized;
            }

            File photoFile = new File(folder, AUTHOR_PHOTO_FILENAME);
            boolean photoSaved = ImageIO.write(rgbImage, IMAGE_FORMAT, photoFile);

            double targetRatio = (double) THUMBNAIL_WIDTH / THUMBNAIL_HEIGHT;
            double sourceRatio = (double) rgbImage.getWidth() / rgbImage.getHeight();
            int cropWidth, cropHeight, cropX, cropY;
            if (sourceRatio > targetRatio) {
                cropHeight = rgbImage.getHeight();
                cropWidth = (int) (cropHeight * targetRatio);
                cropX = (rgbImage.getWidth() - cropWidth) / 2;
                cropY = 0;
            } else {
                cropWidth = rgbImage.getWidth();
                cropHeight = (int) (cropWidth / targetRatio);
                cropX = 0;
                cropY = (rgbImage.getHeight() - cropHeight) / 2;
            }
            BufferedImage cropped = rgbImage.getSubimage(cropX, cropY, cropWidth, cropHeight);
            thumb = resizeImage(cropped, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);

            File thumbnailFile = new File(folder, AUTHOR_THUMBNAIL_FILENAME);
            boolean thumbnailSaved = ImageIO.write(thumb, IMAGE_FORMAT, thumbnailFile);

            return photoSaved && thumbnailSaved;
        } finally {
            if (rgbImage != null) {
                rgbImage.flush();
            }
            if (resized != null && resized != rgbImage) {
                resized.flush();
            }
            if (thumb != null) {
                thumb.flush();
            }
        }
    }

    public void deleteAuthorImages(long authorId) {
        String authorImageFolder = getAuthorImagesFolder(authorId);
        Path folderPath = Paths.get(authorImageFolder);
        try {
            if (Files.exists(folderPath) && Files.isDirectory(folderPath)) {
                try (Stream<Path> walk = Files.walk(folderPath)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    log.error("Failed to delete file: {} - {}", path, e.getMessage());
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.error("Error deleting author images for author {}: {}", authorId, e.getMessage());
        }
    }

    // ========================================
    // AUDIOBOOK COVER OPERATIONS
    // ========================================

    public void createAudiobookThumbnailFromFile(long bookId, MultipartFile file) {
        try {
            validateCoverFile(file);
            BufferedImage originalImage;
            try (InputStream inputStream = file.getInputStream()) {
                originalImage = readImage(inputStream);
            }
            if (originalImage == null) {
                log.warn("Could not decode image from file, skipping audiobook thumbnail creation for book: {}", bookId);
                return;
            }
            boolean success = saveAudiobookCoverImages(originalImage, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException(FAILED_TO_SAVE_AUDIOBOOK_COVER_IMAGES_MESSAGE);
            }
            originalImage.flush();
            log.info("Audiobook cover images created and saved for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating the audiobook thumbnail: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public void createAudiobookThumbnailFromBytes(long bookId, byte[] imageBytes) {
        try {
            BufferedImage originalImage = readImage(imageBytes);
            if (originalImage == null) {
                log.warn("Skipping audiobook thumbnail creation for book {}: image decode failed", bookId);
                return;
            }
            boolean success = saveAudiobookCoverImages(originalImage, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException(FAILED_TO_SAVE_AUDIOBOOK_COVER_IMAGES_MESSAGE);
            }
            originalImage.flush();
            log.info("Audiobook cover images created and saved from bytes for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating audiobook thumbnail from bytes: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public void createAudiobookThumbnailFromUrl(long bookId, String imageUrl) {
        try {
            BufferedImage originalImage = downloadImageFromUrl(imageUrl);
            if (originalImage == null) {
                log.warn("Skipping audiobook thumbnail creation for book {}: download/decode failed", bookId);
                return;
            }
            boolean success = saveAudiobookCoverImages(originalImage, bookId);
            if (!success) {
                throw ApiError.FILE_READ_ERROR.createException(FAILED_TO_SAVE_AUDIOBOOK_COVER_IMAGES_MESSAGE);
            }
            originalImage.flush();
            log.info("Audiobook cover images created and saved from URL for book ID: {}", bookId);
        } catch (Exception e) {
            log.error("An error occurred while creating audiobook thumbnail from URL: {}", e.getMessage(), e);
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
    }

    public boolean saveAudiobookCoverImages(BufferedImage coverImage, long bookId) throws IOException {
        BufferedImage rgbImage = null;
        BufferedImage resized = null;
        BufferedImage thumb = null;
        try {
            String folderPath = getImagesFolder(bookId);
            File folder = new File(folderPath);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
            }

            rgbImage = new BufferedImage(
                    coverImage.getWidth(),
                    coverImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(coverImage, 0, 0, Color.WHITE, null);
            g.dispose();

            // Resize to square if needed, maintaining 1:1 aspect ratio
            int size = Math.min(rgbImage.getWidth(), rgbImage.getHeight());
            int x = (rgbImage.getWidth() - size) / 2;
            int y = (rgbImage.getHeight() - size) / 2;
            BufferedImage cropped = rgbImage.getSubimage(x, y, size, size);

            // Resize if too large
            if (size > MAX_SQUARE_SIZE) {
                resized = resizeImage(cropped, MAX_SQUARE_SIZE, MAX_SQUARE_SIZE);
            } else {
                resized = cropped;
            }

            File originalFile = new File(folder, AUDIOBOOK_COVER_FILENAME);
            boolean originalSaved = ImageIO.write(resized, IMAGE_FORMAT, originalFile);

            // Create square thumbnail
            thumb = resizeImage(resized, SQUARE_THUMBNAIL_SIZE, SQUARE_THUMBNAIL_SIZE);
            File thumbnailFile = new File(folder, AUDIOBOOK_THUMBNAIL_FILENAME);
            boolean thumbnailSaved = ImageIO.write(thumb, IMAGE_FORMAT, thumbnailFile);

            return originalSaved && thumbnailSaved;
        } finally {
            if (rgbImage != null) {
                rgbImage.flush();
            }
            if (resized != null && resized != rgbImage) {
                resized.flush();
            }
            if (thumb != null) {
                thumb.flush();
            }
        }
    }

    public boolean saveCoverImages(BufferedImage coverImage, long bookId) throws IOException {
        PreparedCover prepared = prepareCoverImages(coverImage, getCoverCroppingSettings());
        try {
            return writeCoverImages(prepared, bookId);
        } finally {
            prepared.flush();
        }
    }

    /**
     * A cover and its thumbnail, processed and ready to write. Splitting these out of
     * {@link #saveCoverImages} is what lets {@link #saveCoverImageFromBytes} say <em>which</em> half
     * failed - see there for why one {@code catch} around both could not.
     */
    private record PreparedCover(BufferedImage cover, BufferedImage thumbnail) {
        void flush() {
            cover.flush();
            if (thumbnail != cover) {
                thumbnail.flush();
            }
        }
    }

    private CoverCroppingSettings getCoverCroppingSettings() {
        return appSettingService.getAppSettings().getCoverCroppingSettings();
    }

    /**
     * Turns a decoded image into the cover and thumbnail rasters. Pure: no IO, no settings lookup
     * (they are passed in, so that a settings-service failure cannot be mistaken for a fact about
     * the image), and nothing here can fail transiently.
     */
    private PreparedCover prepareCoverImages(BufferedImage coverImage, CoverCroppingSettings croppingSettings) {
        BufferedImage rgbImage = new BufferedImage(
                coverImage.getWidth(),
                coverImage.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = rgbImage.createGraphics();
        g.drawImage(coverImage, 0, 0, Color.WHITE, null);
        g.dispose();
        // Note: coverImage is not flushed here - caller is responsible for its lifecycle

        BufferedImage cropped = applyCoverCropping(rgbImage, croppingSettings);
        if (cropped != rgbImage) {
            rgbImage.flush();
            rgbImage = cropped;
        }

        // Resize original image if too large to prevent OOM
        double scale = Math.min(
                (double) MAX_ORIGINAL_WIDTH / rgbImage.getWidth(),
                (double) MAX_ORIGINAL_HEIGHT / rgbImage.getHeight()
        );
        if (scale < 1.0) {
            BufferedImage resized = resizeImage(rgbImage, (int) (rgbImage.getWidth() * scale), (int) (rgbImage.getHeight() * scale));
            rgbImage.flush(); // Release resources of the original large image
            rgbImage = resized;
        }

        // Determine thumbnail dimensions based on source aspect ratio
        int thumbWidth, thumbHeight;
        double aspectRatio = (double) rgbImage.getWidth() / rgbImage.getHeight();
        if (aspectRatio >= 0.85 && aspectRatio <= 1.15) {
            // Square-ish image (e.g., audiobook covers) - keep square
            thumbWidth = THUMBNAIL_WIDTH;
            thumbHeight = THUMBNAIL_WIDTH;
        } else {
            // Portrait/landscape - use standard dimensions
            thumbWidth = THUMBNAIL_WIDTH;
            thumbHeight = THUMBNAIL_HEIGHT;
        }
        return new PreparedCover(rgbImage, resizeImage(rgbImage, thumbWidth, thumbHeight));
    }

    /** Writes prepared rasters to disk. Every failure here is a fact about this attempt, not the image. */
    private boolean writeCoverImages(PreparedCover prepared, long bookId) throws IOException {
        String folderPath = getImagesFolder(bookId);
        File folder = new File(folderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Failed to create directory: " + folder.getAbsolutePath());
        }

        boolean originalSaved = ImageIO.write(prepared.cover(), IMAGE_FORMAT, new File(folder, COVER_FILENAME));
        boolean thumbnailSaved = ImageIO.write(prepared.thumbnail(), IMAGE_FORMAT, new File(folder, THUMBNAIL_FILENAME));
        return originalSaved && thumbnailSaved;
    }

    private BufferedImage applyCoverCropping(BufferedImage image, CoverCroppingSettings settings) {
        if (settings == null) {
            return image;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        double heightToWidthRatio = (double) height / width;
        double widthToHeightRatio = (double) width / height;
        double threshold = settings.getAspectRatioThreshold();
        boolean smartCrop = settings.isSmartCroppingEnabled();

        boolean isExtremelyTall = settings.isVerticalCroppingEnabled() && heightToWidthRatio > threshold;
        if (isExtremelyTall) {
            int croppedHeight = clamp((int) (width * TARGET_COVER_ASPECT_RATIO), height);
            log.debug("Cropping tall image: {}x{} (ratio {}) -> {}x{}, smartCrop={}",
                    width, height, String.format("%.2f", heightToWidthRatio), width, croppedHeight, smartCrop);
            return cropFromTop(image, width, croppedHeight, smartCrop);
        }

        boolean isExtremelyWide = settings.isHorizontalCroppingEnabled() && widthToHeightRatio > threshold;
        if (isExtremelyWide) {
            int croppedWidth = clamp((int) (height / TARGET_COVER_ASPECT_RATIO), width);
            log.debug("Cropping wide image: {}x{} (ratio {}) -> {}x{}, smartCrop={}",
                    width, height, String.format("%.2f", widthToHeightRatio), croppedWidth, height, smartCrop);
            return cropFromLeft(image, croppedWidth, height, smartCrop);
        }

        return image;
    }

    /**
     * Holds a crop target inside {@code [1, available]}.
     * <p>
     * Both ends are reachable from real settings and real bytes, and {@code getSubimage} throws
     * {@code RasterFormatException} at either. The low end is the mirror of {@link #resizeImage}'s
     * floor: a 20000x1 cover asks for a crop {@code (int) (1 / 1.5) = 0} pixels wide. The high end
     * is reachable whenever {@code aspectRatioThreshold} is configured below
     * {@link #TARGET_COVER_ASPECT_RATIO}, which makes a merely tallish image "extremely tall" and
     * then asks to crop it to a height greater than it has.
     */
    private static int clamp(int target, int available) {
        return Math.max(1, Math.min(target, available));
    }

    private BufferedImage cropFromTop(BufferedImage image, int targetWidth, int targetHeight, boolean smartCrop) {
        int startY = 0;
        if (smartCrop) {
            int contentStartY = findContentStartY(image);
            int margin = (int) (targetHeight * SMART_CROP_MARGIN_PERCENT);
            startY = Math.max(0, contentStartY - margin);

            int maxStartY = image.getHeight() - targetHeight;
            startY = Math.min(startY, maxStartY);
        }
        return image.getSubimage(0, startY, targetWidth, targetHeight);
    }

    private BufferedImage cropFromLeft(BufferedImage image, int targetWidth, int targetHeight, boolean smartCrop) {
        int startX = 0;
        if (smartCrop) {
            int contentStartX = findContentStartX(image);
            int margin = (int) (targetWidth * SMART_CROP_MARGIN_PERCENT);
            startX = Math.max(0, contentStartX - margin);

            int maxStartX = image.getWidth() - targetWidth;
            startX = Math.min(startX, maxStartX);
        }
        return image.getSubimage(startX, 0, targetWidth, targetHeight);
    }

    private int findContentStartY(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            if (!isRowUniformColor(image, y)) {
                return y;
            }
        }
        return 0;
    }

    private int findContentStartX(BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x++) {
            if (!isColumnUniformColor(image, x)) {
                return x;
            }
        }
        return 0;
    }

    private boolean isRowUniformColor(BufferedImage image, int y) {
        int firstPixel = image.getRGB(0, y);
        for (int x = 1; x < image.getWidth(); x++) {
            if (!colorsAreSimilar(firstPixel, image.getRGB(x, y))) {
                return false;
            }
        }
        return true;
    }

    private boolean isColumnUniformColor(BufferedImage image, int x) {
        int firstPixel = image.getRGB(x, 0);
        for (int y = 1; y < image.getHeight(); y++) {
            if (!colorsAreSimilar(firstPixel, image.getRGB(x, y))) {
                return false;
            }
        }
        return true;
    }

    private boolean colorsAreSimilar(int rgb1, int rgb2) {
        int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;
        return Math.abs(r1 - r2) <= SMART_CROP_COLOR_TOLERANCE
                && Math.abs(g1 - g2) <= SMART_CROP_COLOR_TOLERANCE
                && Math.abs(b1 - b2) <= SMART_CROP_COLOR_TOLERANCE;
    }

    public static void setBookCoverPath(BookMetadataEntity bookMetadataEntity) {
        bookMetadataEntity.setCoverUpdatedOn(Instant.now());
    }

    public void deleteBookCovers(Set<Long> bookIds) {
        for (Long bookId : bookIds) {
            String bookCoverFolder = getImagesFolder(bookId);
            Path folderPath = Paths.get(bookCoverFolder);
            try {
                if (Files.exists(folderPath) && Files.isDirectory(folderPath)) {
                    try (Stream<Path> walk = Files.walk(folderPath)) {
                        walk.sorted(Comparator.reverseOrder())
                                .forEach(path -> {
                                    try {
                                        Files.delete(path);
                                    } catch (IOException e) {
                                        log.error("Failed to delete file: {} - {}", path, e.getMessage());
                                    }
                                });
                    }
                }
            } catch (IOException e) {
                log.error("Error processing folder: {} - {}", folderPath, e.getMessage());
            }
        }
        log.info("Deleted {} book covers", bookIds.size());
    }

    public String getIconsSvgFolder() {
        return Paths.get(appProperties.getPathConfig(), ICONS_DIR, SVG_DIR).toString();
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    public static String truncate(String input, int maxLength) {
        if (input == null) return null;
        if (maxLength <= 0) return "";
        return input.length() <= maxLength ? input : input.substring(0, maxLength);
    }

    public void clearCacheDirectory(String cachePath) {
        Path path = Paths.get(cachePath);
        if (Files.exists(path) && Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                log.error("Failed to delete file in cache: {} - {}", p, e.getMessage());
                            }
                        });
                // Recreate the directory after deletion
                Files.createDirectories(path);
            } catch (IOException e) {
                log.error("Failed to clear cache directory: {} - {}", cachePath, e.getMessage());
            }
        }
    }
}
