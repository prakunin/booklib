package org.booklore.service.kobo;

import org.booklore.util.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class KepubConversionService {

    private static final Duration KEPUBIFY_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration PROCESS_DESTROY_GRACE = Duration.ofSeconds(1);

    private final FileService fileService;

    public File convertEpubToKepub(File epubFile, File tempDir, boolean forceEnableHyphenation) throws IOException, InterruptedException {
        validateInputs(epubFile);

        Path kepubifyBinary = fileService.findSystemFile("kepubify");

        if (kepubifyBinary == null) {
            throw new IOException("Kepubify conversion failed: could not find kepubify binary");
        }

        File outputFile = executeKepubifyConversion(epubFile, tempDir, kepubifyBinary, forceEnableHyphenation);

        log.info("Successfully converted {} to {} (size: {} bytes)", epubFile.getName(), outputFile.getName(), outputFile.length());
        return outputFile;
    }

    private void validateInputs(File epubFile) {
        if (epubFile == null || !epubFile.isFile() || !epubFile.getName().endsWith(".epub")) {
            throw new IllegalArgumentException("Invalid EPUB file: " + epubFile);
        }
    }

    private File executeKepubifyConversion(File epubFile, File tempDir, Path kepubifyBinary, boolean forceEnableHyphenation) throws IOException, InterruptedException {
        ProcessBuilder pb;

        if (forceEnableHyphenation)
            pb = new ProcessBuilder(kepubifyBinary.toAbsolutePath().toString(), "--hyphenate", "-o", tempDir.getAbsolutePath(), epubFile.getAbsolutePath());
        else
            pb = new ProcessBuilder(kepubifyBinary.toAbsolutePath().toString(), "-o", tempDir.getAbsolutePath(), epubFile.getAbsolutePath());

        pb.directory(tempDir);
        pb.redirectErrorStream(true);

        log.info("Starting kepubify conversion for {} -> output dir: {}", epubFile.getAbsolutePath(), tempDir.getAbsolutePath());

        Process process = null;
        Thread outputReader = null;
        StringBuilder output = new StringBuilder();
        AtomicReference<IOException> outputReadFailure = new AtomicReference<>();

        try {
            process = pb.start();
            Process kepubifyProcess = process;
            outputReader = startOutputReader(kepubifyProcess.getInputStream(), output, outputReadFailure);

            if (!process.waitFor(KEPUBIFY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Kepubify conversion timed out after " + KEPUBIFY_TIMEOUT.toMinutes() + " minutes");
            }

            outputReader.join(PROCESS_DESTROY_GRACE.toMillis());
            if (outputReader.isAlive()) {
                throw new IOException("Kepubify conversion output reader did not finish");
            }

            if (outputReadFailure.get() != null) {
                throw new IOException("Failed to read kepubify output", outputReadFailure.get());
            }

            int exitCode = process.exitValue();
            String processOutput = output.toString();
            logProcessResults(exitCode, processOutput);

            if (exitCode != 0) {
                throw new IOException(String.format("Kepubify conversion failed with exit code: %d. Output: %s", exitCode, processOutput));
            }

            return findOutputFile(tempDir);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (outputReader != null && outputReader.isAlive()) {
                outputReader.interrupt();
            }
            stopProcess(process);
        }
    }

    private Thread startOutputReader(InputStream inputStream, StringBuilder output, AtomicReference<IOException> outputReadFailure) {
        return Thread.ofVirtual().name("kepubify-output-reader-", 0).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException e) {
                outputReadFailure.set(e);
            }
        });
    }

    private void stopProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }

        process.destroy();
        try {
            if (!process.waitFor(PROCESS_DESTROY_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void logProcessResults(int exitCode, String output) {
        log.debug("Kepubify process exited with code {}", exitCode);
        if (!output.isEmpty()) {
            log.debug("Kepubify output: {}", output);
        }
    }

    private File findOutputFile(File tempDir) throws IOException {
        File[] kepubFiles = tempDir.listFiles((dir, name) -> name.endsWith(".kepub.epub"));
        if (kepubFiles == null || kepubFiles.length == 0) {
            throw new IOException("Kepubify conversion completed but no .kepub.epub file was created in: " + tempDir.getAbsolutePath());
        }
        return kepubFiles[0];
    }
}
