package org.booklore.util;

import org.booklore.exception.APIException;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverUploadValidatorTest {

    private AppSettingService appSettingService;
    private CoverUploadValidator validator;

    @BeforeEach
    void setUp() {
        appSettingService = mock(AppSettingService.class);
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .maxFileUploadSizeInMb(1)
                .build());
        validator = new CoverUploadValidator(appSettingService);
    }

    @Test
    void acceptsJpegByDetectedContent() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "cover.bin", "application/octet-stream", imageBytes("JPEG"));

        assertDoesNotThrow(() -> validator.validate(file));
    }

    @Test
    void acceptsPngByDetectedContent() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "cover.bin", "application/octet-stream", imageBytes("PNG"));

        assertDoesNotThrow(() -> validator.validate(file));
    }

    @Test
    void rejectsEmptyFile() {
        MultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsNonImageBytes() {
        MultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "not an image".getBytes());

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("JPEG and PNG");
    }

    @Test
    void rejectsFilesLargerThanConfiguredLimit() {
        MultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", new byte[1024 * 1024 + 1]);

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(APIException.class)
                .hasMessageContaining("1 MB");
    }

    private byte[] imageBytes(String format) throws IOException {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
