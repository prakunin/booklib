package org.booklore.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoverUploadValidator {

    private static final String JPEG_MIME_TYPE = "image/jpeg";
    private static final String PNG_MIME_TYPE = "image/png";

    private final AppSettingService appSettingService;

    public void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiError.INVALID_INPUT.createException("Uploaded file is empty");
        }

        long maxSizeMb = getMaxFileUploadSizeMb();
        long maxFileSize = maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxFileSize) {
            throw ApiError.FILE_TOO_LARGE.createException(maxSizeMb);
        }

        try (var inputStream = file.getInputStream()) {
            String detectedMime = MimeDetector.detect(inputStream);
            if (!JPEG_MIME_TYPE.equals(detectedMime) && !PNG_MIME_TYPE.equals(detectedMime)) {
                throw ApiError.INVALID_INPUT.createException("Only JPEG and PNG files are allowed (detected: " + detectedMime + ")");
            }
        } catch (IOException e) {
            throw ApiError.INVALID_INPUT.createException("Failed to read uploaded file for MIME detection");
        }
    }

    private long getMaxFileUploadSizeMb() {
        AppSettings appSettings = appSettingService.getAppSettings();
        Integer maxFileUploadSizeMb = appSettings.getMaxFileUploadSizeInMb();
        if (maxFileUploadSizeMb == null) {
            log.warn("Max File Upload Size is unset, cannot continue");
            throw ApiError.INTERNAL_SERVER_ERROR.createException("Max File Upload Size is Unset");
        }
        return maxFileUploadSizeMb.longValue();
    }
}
