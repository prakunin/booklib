package org.booklore.nativelib;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.github.gotson.nightcompress.Archive;
import org.grimmory.pdfium4j.PdfiumLibrary;

/**
 * JVM-wide single source of truth for native-library availability.
 *
 * <p>Initialization is intentionally serialized via the holder idiom so native
 * library loading is forced exactly once under the JVM class-init lock.
 */
@Slf4j
@SuppressWarnings("java:S6548") // intentional holder-idiom singleton for one-time native library probing; documented above
public final class NativeLibraries {

    public enum Library {
        PDFIUM,
        LIBARCHIVE,
        EPUB4J_NATIVE
    }

    private static final Map<Library, Probe> PROBES;

    static {
        Map<Library, Probe> probes = new LinkedHashMap<>();

        probes.put(Library.PDFIUM, new Probe("PDFium", () -> {
            Optional<Boolean> clean = tryInvokeStaticBoolean("org.grimmory.pdfium4j.PdfiumLibrary");
            if (clean.isPresent()) {
                return clean.get();
            }
            PdfiumLibrary.initialize();
            return true;
        }));

        probes.put(Library.LIBARCHIVE, new Probe("libarchive", Archive::isAvailable));

        probes.put(Library.EPUB4J_NATIVE, new Probe("epub4j-native", () -> {
            Optional<Boolean> clean = tryInvokeStaticBoolean(
                    "org.grimmory.epub4j.native_parsing.EpubNativeLibrary"
            );
            if (clean.isPresent()) {
                return clean.get();
            }
            Class.forName(
                    "org.grimmory.epub4j.native_parsing.PanamaConstants",
                    true,
                    NativeLibraries.class.getClassLoader()
            );
            return true;
        }));

        PROBES = Collections.unmodifiableMap(probes);
    }

    private final Map<Library, Boolean> status;

    private NativeLibraries() {
        Map<Library, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<Library, Probe> entry : PROBES.entrySet()) {
            result.put(entry.getKey(), runProbe(entry.getValue()));
        }
        this.status = Collections.unmodifiableMap(result);
        logSummary();
    }

    private static boolean runProbe(Probe probe) {
        try {
            return probe.fn.get();
        } catch (Throwable t) {
            log.warn("{} native library unavailable: {}", probe.name, t.toString());
            return false;
        }
    }

    // Rethrows InvocationTargetException's cause transparently (any Throwable, including Errors like
    // UnsatisfiedLinkError from native init) so runProbe's catch (Throwable t) logs the true failure;
    // wrapping in a narrower type would change the logged exception text and could swallow an Error.
    @SuppressWarnings("java:S112")
    private static Optional<Boolean> tryInvokeStaticBoolean(String fqcn) throws Throwable {
        Class<?> cls;
        try {
            cls = Class.forName(fqcn, false, NativeLibraries.class.getClassLoader());
        } catch (ClassNotFoundException _) {
            return Optional.empty();
        }

        Method method;
        try {
            method = cls.getMethod("isAvailable");
        } catch (NoSuchMethodException _) {
            return Optional.empty();
        }

        if (method.getReturnType() != boolean.class) {
            return Optional.empty();
        }

        Class.forName(fqcn, true, NativeLibraries.class.getClassLoader());
        try {
            return Optional.of((Boolean) method.invoke(null));
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            throw cause != null ? cause : ite;
        }
    }

    private void logSummary() {
        StringBuilder sb = new StringBuilder("Native libraries,");
        boolean first = true;
        for (Map.Entry<Library, Boolean> entry : status.entrySet()) {
            sb.append(first ? " " : ", ")
                    .append(PROBES.get(entry.getKey()).name)
                    .append(": ")
                    .append(Boolean.TRUE.equals(entry.getValue()) ? "loaded" : "NOT available");
            first = false;
        }
        log.info(sb.toString());
    }

    private static final class Holder {
        static final NativeLibraries INSTANCE = new NativeLibraries();
    }

    public static NativeLibraries get() {
        return Holder.INSTANCE;
    }

    public static void ensureInitialized() {
        NativeLibraries _ = Holder.INSTANCE;
    }

    public boolean isAvailable(Library library) {
        return Boolean.TRUE.equals(status.get(library));
    }

    public boolean isPdfiumAvailable() {
        return isAvailable(Library.PDFIUM);
    }

    public boolean isLibArchiveAvailable() {
        return isAvailable(Library.LIBARCHIVE);
    }

    public boolean isEpubNativeAvailable() {
        return isAvailable(Library.EPUB4J_NATIVE);
    }

    private record Probe(String name, CheckedBooleanSupplier fn) {}

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        // Must stay Throwable: probes can propagate Errors (e.g. UnsatisfiedLinkError) from native
        // library init, which runProbe deliberately catches via `catch (Throwable t)`.
        @SuppressWarnings("java:S112")
        boolean get() throws Throwable;
    }
}
