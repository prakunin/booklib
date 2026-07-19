package org.booklore.service.komga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.komga.KomgaMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.MagicShelf;
import org.booklore.model.dto.komga.*;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.MagicShelfService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.reader.CbxReaderService;
import org.booklore.service.reader.PdfReaderService;
import org.booklore.service.restriction.ContentRestrictionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KomgaService {

    private static final Semaphore PNG_CONVERSION_PERMITS = new Semaphore(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2)
    );
    private final BookRepository bookRepository;
    private final LibraryRepository libraryRepository;
    private final KomgaMapper komgaMapper;
    private final MagicShelfService magicShelfService;
    private final CbxReaderService cbxReaderService;
    private final PdfReaderService pdfReaderService;
    private final AppSettingService appSettingService;
    private final ContentRestrictionService contentRestrictionService;

    private record ParsedSeriesId(Long libraryId, String seriesSlug) {
    }


    public boolean validateBookContentAccess(BookLoreUser user, Long bookId) {
        if (user == null) {
            return false;
        }

        if (user.getPermissions() != null && user.getPermissions().isAdmin()) {
            return true;
        }

        BookEntity book = bookRepository.findById(bookId)
                .orElse(null);

        if (book == null) {
            return false;
        }

        boolean hasLibraryAccess = user.getAssignedLibraries() != null && user.getAssignedLibraries().stream()
                .anyMatch(library -> library.getId().equals(book.getLibrary().getId()));

        if (!hasLibraryAccess) {
            return false;
        }

        List<BookEntity> filtered = contentRestrictionService.applyRestrictions(List.of(book), user.getId());
        if (filtered.isEmpty()) {
            return false;
        }

        return true;
    }


    public List<KomgaLibraryDto> getAllLibraries() {
        return libraryRepository.findAll().stream()
                .map(komgaMapper::toKomgaLibraryDto)
                .toList();
    }

    public KomgaLibraryDto getLibraryById(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> new RuntimeException("Library not found"));
        return komgaMapper.toKomgaLibraryDto(library);
    }

    public KomgaPageableDto<KomgaSeriesDto> getAllSeries(Long libraryId, int page, int size, boolean unpaged) {
        log.debug("Getting all series for libraryId: {}, page: {}, size: {}", libraryId, page, size);
        
        // Check if we should group unknown series
        boolean groupUnknown = appSettingService.getAppSettings().isKomgaGroupUnknown();
        String unknownSeriesName = komgaMapper.getUnknownSeriesName();
        
        // Get distinct series names directly from database (MUCH faster than loading all books)
        List<String> sortedSeriesNames;
        if (groupUnknown) {
            // Use optimized query that groups books without series as "Unknown Series"
            if (libraryId != null) {
                sortedSeriesNames = bookRepository.findDistinctSeriesNamesGroupedByLibraryId(
                    libraryId, unknownSeriesName);
            } else {
                sortedSeriesNames = bookRepository.findDistinctSeriesNamesGrouped(
                    unknownSeriesName);
            }
        } else {
            // Use query that gives each book without series its own entry
            if (libraryId != null) {
                sortedSeriesNames = bookRepository.findDistinctSeriesNamesUngroupedByLibraryId(libraryId);
            } else {
                sortedSeriesNames = bookRepository.findDistinctSeriesNamesUngrouped();
            }
        }
        
        log.debug("Found {} distinct series names from database (optimized)", sortedSeriesNames.size());
        
        // Calculate pagination
        int totalElements = sortedSeriesNames.size();
        List<String> pageSeriesNames;
        int actualPage;
        int actualSize;
        int totalPages;
        
        if (unpaged) {
            pageSeriesNames = sortedSeriesNames;
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            
            pageSeriesNames = sortedSeriesNames.subList(fromIndex, toIndex);
            actualPage = page;
            actualSize = size;
        }
        
        Map<String, List<BookEntity>> booksBySeriesName = groupBooksBySeriesName(
                findBooksForSeriesNames(pageSeriesNames, libraryId, groupUnknown, unknownSeriesName));
        List<KomgaSeriesDto> content = new ArrayList<>();
        for (String seriesName : pageSeriesNames) {
            try {
                List<BookEntity> seriesBooks = booksBySeriesName.getOrDefault(seriesName, List.of());
                if (!seriesBooks.isEmpty()) {
                    Long libId = seriesBooks.getFirst().getLibrary().getId();
                    KomgaSeriesDto seriesDto = komgaMapper.toKomgaSeriesDto(seriesName, libId, seriesBooks);
                    if (seriesDto != null) {
                        content.add(seriesDto);
                    }
                }
            } catch (Exception e) {
                log.error("Error mapping series: {}", seriesName, e);
            }
        }
        
        log.debug("Mapped {} series DTOs for this page", content.size());
        
        return KomgaPageableDto.<KomgaSeriesDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }

    private List<BookEntity> findBooksForSeriesNames(
            List<String> seriesNames, Long libraryId, boolean groupUnknown, String unknownSeriesName) {
        if (seriesNames.isEmpty()) {
            return List.of();
        }
        if (libraryId != null) {
            return groupUnknown
                    ? bookRepository.findBooksBySeriesNamesGroupedByLibraryId(
                            seriesNames, libraryId, unknownSeriesName, seriesNames.contains(unknownSeriesName))
                    : bookRepository.findBooksBySeriesNamesUngroupedByLibraryId(seriesNames, libraryId);
        }
        return groupUnknown
                ? bookRepository.findBooksBySeriesNamesGrouped(
                        seriesNames, unknownSeriesName, seriesNames.contains(unknownSeriesName))
                : bookRepository.findBooksBySeriesNamesUngrouped(seriesNames);
    }

    private Map<String, List<BookEntity>> groupBooksBySeriesName(List<BookEntity> books) {
        Map<String, List<BookEntity>> booksBySeriesName = new LinkedHashMap<>();
        for (BookEntity book : books) {
            String seriesName = komgaMapper.getBookSeriesName(book);
            if (seriesName != null) {
                booksBySeriesName.computeIfAbsent(seriesName, ignored -> new ArrayList<>()).add(book);
            }
        }
        return booksBySeriesName;
    }

    public KomgaSeriesDto getSeriesById(String seriesId) {
        ParsedSeriesId parsedSeriesId = parseSeriesId(seriesId);
        boolean groupUnknown = appSettingService.getAppSettings().isKomgaGroupUnknown();
        String unknownSeriesName = komgaMapper.getUnknownSeriesName();
        String matchedSeriesName = resolveSeriesName(parsedSeriesId, groupUnknown, unknownSeriesName)
                .orElseThrow(() -> new RuntimeException("Series not found"));

        Page<BookEntity> seriesBooksPage = findSeriesBooksPage(
                matchedSeriesName, parsedSeriesId.libraryId(), groupUnknown, unknownSeriesName, PageRequest.of(0, 1));
        if (seriesBooksPage.isEmpty()) {
            throw new RuntimeException("Series not found");
        }

        KomgaSeriesDto seriesDto = komgaMapper.toKomgaSeriesDto(
                matchedSeriesName, parsedSeriesId.libraryId(), seriesBooksPage.getContent());
        int booksCount = safeBookCount(seriesBooksPage.getTotalElements());
        seriesDto.setBooksCount(booksCount);
        seriesDto.setBooksUnreadCount(booksCount);
        seriesDto.setOneshot(booksCount == 1);
        if (seriesDto.getMetadata() != null) {
            seriesDto.getMetadata().setTotalBookCount(booksCount);
        }
        return seriesDto;
    }

    public KomgaPageableDto<KomgaBookDto> getBooksBySeries(String seriesId, int page, int size, boolean unpaged) {
        ParsedSeriesId parsedSeriesId = parseSeriesId(seriesId);
        boolean groupUnknown = appSettingService.getAppSettings().isKomgaGroupUnknown();
        String unknownSeriesName = komgaMapper.getUnknownSeriesName();
        Pageable pageable = unpaged ? Pageable.unpaged() : PageRequest.of(page, size);

        Page<BookEntity> seriesBooksPage = resolveSeriesName(parsedSeriesId, groupUnknown, unknownSeriesName)
                .map(seriesName -> findSeriesBooksPage(
                        seriesName, parsedSeriesId.libraryId(), groupUnknown, unknownSeriesName, pageable))
                .orElse(Page.empty(pageable));

        int totalElements = safeBookCount(seriesBooksPage.getTotalElements());
        List<KomgaBookDto> content = seriesBooksPage.getContent().stream()
                .map(komgaMapper::toKomgaBookDto)
                .toList();
        int actualPage;
        int actualSize;

        if (unpaged) {
            actualPage = 0;
            actualSize = totalElements;
        } else {
            actualPage = page;
            actualSize = size;
        }

        return KomgaPageableDto.<KomgaBookDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(unpaged ? (totalElements > 0 ? 1 : 0) : seriesBooksPage.getTotalPages())
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= (unpaged ? 0 : seriesBooksPage.getTotalPages() - 1))
                .empty(content.isEmpty())
                .build();
    }

    private ParsedSeriesId parseSeriesId(String seriesId) {
        String[] parts = seriesId.split("-", 2);
        if (parts.length < 2) {
            throw new RuntimeException("Invalid series ID");
        }
        return new ParsedSeriesId(Long.parseLong(parts[0]), parts[1]);
    }

    private Optional<String> resolveSeriesName(ParsedSeriesId seriesId, boolean groupUnknown, String unknownSeriesName) {
        Pageable firstMatch = PageRequest.of(0, 1);
        List<String> seriesNames = groupUnknown
                ? bookRepository.findGroupedSeriesNameByLibraryIdAndSlug(
                        seriesId.libraryId(), unknownSeriesName, seriesId.seriesSlug(), firstMatch)
                : bookRepository.findUngroupedSeriesNameByLibraryIdAndSlug(
                        seriesId.libraryId(), seriesId.seriesSlug(), firstMatch);
        return seriesNames.stream().findFirst();
    }

    private Page<BookEntity> findSeriesBooksPage(
            String seriesName, Long libraryId, boolean groupUnknown, String unknownSeriesName, Pageable pageable) {
        return groupUnknown
                ? bookRepository.findBooksPageBySeriesNameGroupedByLibraryId(
                        seriesName, libraryId, unknownSeriesName, pageable)
                : bookRepository.findBooksPageBySeriesNameUngroupedByLibraryId(seriesName, libraryId, pageable);
    }

    private int safeBookCount(long count) {
        return Math.toIntExact(Math.min(count, Integer.MAX_VALUE));
    }

    public KomgaPageableDto<KomgaBookDto> getAllBooks(Long libraryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<BookEntity> bookPage;
        
        if (libraryId != null) {
            bookPage = bookRepository.findAllWithMetadataByLibraryIdPaged(libraryId, pageable);
        } else {
            bookPage = bookRepository.findAllWithMetadataPaged(pageable);
        }
        
        List<KomgaBookDto> content = bookPage.getContent().stream()
                .map(komgaMapper::toKomgaBookDto)
                .toList();
        
        return KomgaPageableDto.<KomgaBookDto>builder()
                .content(content)
                .number(page)
                .size(size)
                .numberOfElements(content.size())
                .totalElements((int) bookPage.getTotalElements())
                .totalPages(bookPage.getTotalPages())
                .first(page == 0)
                .last(page >= bookPage.getTotalPages() - 1)
                .empty(content.isEmpty())
                .build();
    }

    public KomgaBookDto getBookById(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> new RuntimeException("Book not found"));
        return komgaMapper.toKomgaBookDto(book);
    }

    public List<KomgaPageDto> getBookPages(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> new RuntimeException("Book not found"));
        
        BookMetadataEntity metadata = book.getMetadata();
        Integer pageCount = metadata != null && metadata.getPageCount() != null ? metadata.getPageCount() : 0;
        
        List<KomgaPageDto> pages = new ArrayList<>();
        if (pageCount > 0) {
            for (int i = 1; i <= pageCount; i++) {
                pages.add(KomgaPageDto.builder()
                        .number(i)
                        .fileName("page-" + i)
                        .mediaType("image/jpeg")
                        .build());
            }
        }
        
        return pages;
    }

    private Map<String, List<BookEntity>> groupBooksBySeries(List<BookEntity> books) {
        Map<String, List<BookEntity>> seriesMap = new HashMap<>();
        
        for (BookEntity book : books) {
            String seriesName = komgaMapper.getBookSeriesName(book);
            seriesMap.computeIfAbsent(seriesName, k -> new ArrayList<>()).add(book);
        }
        
        return seriesMap;
    }
    
    public KomgaPageableDto<KomgaCollectionDto> getCollections(int page, int size, boolean unpaged) {
        log.debug("Getting collections, page: {}, size: {}, unpaged: {}", page, size, unpaged);
        
        List<MagicShelf> magicShelves = magicShelfService.getUserShelves();
        log.debug("Found {} magic shelves", magicShelves.size());
        
        // Convert to collection DTOs - for now, series count is 0 since we don't have 
        // the series filter implementation
        List<KomgaCollectionDto> allCollections = magicShelves.stream()
                .map(shelf -> komgaMapper.toKomgaCollectionDto(shelf, 0))
                .sorted(Comparator.comparing(KomgaCollectionDto::getName))
                .toList();
        
        log.debug("Mapped to {} collection DTOs", allCollections.size());
        
        // Handle unpaged mode
        int totalElements = allCollections.size();
        List<KomgaCollectionDto> content;
        int actualPage;
        int actualSize;
        int totalPages;
        
        if (unpaged) {
            content = allCollections;
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            // Paginate
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            
            content = allCollections.subList(fromIndex, toIndex);
            actualPage = page;
            actualSize = size;
        }
        
        return KomgaPageableDto.<KomgaCollectionDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }
    
    public void streamBookPageImage(Long bookId, Integer pageNumber, OutputStream outputStream) throws IOException {
        log.debug("Streaming page {} from book {}", pageNumber, bookId);

        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> new RuntimeException("Book not found: " + bookId));

        boolean isPDF = book.getPrimaryBookFile().getBookType() == BookFileType.PDF;
        if (isPDF) {
            pdfReaderService.streamPageImage(bookId, pageNumber, outputStream);
        } else {
            cbxReaderService.streamPageImage(bookId, pageNumber, outputStream);
        }
    }

    public void streamBookPageImageAsPng(Long bookId, Integer pageNumber, OutputStream outputStream) throws IOException {
        log.debug("Streaming page {} from book {} as PNG", pageNumber, bookId);

        Path sourceImage = Files.createTempFile("booklore-komga-page-", ".img");
        try {
            try (OutputStream sourceOutputStream = Files.newOutputStream(sourceImage)) {
                streamBookPageImage(bookId, pageNumber, sourceOutputStream);
            }
            convertImageToPng(sourceImage, outputStream);
        } finally {
            try {
                Files.deleteIfExists(sourceImage);
            } catch (IOException e) {
                log.warn("Failed to delete temporary Komga page image {}", sourceImage, e);
            }
        }
    }

    private void convertImageToPng(Path sourceImage, OutputStream outputStream) throws IOException {
        try {
            PNG_CONVERSION_PERMITS.acquire();
            try (InputStream inputStream = Files.newInputStream(sourceImage)) {
                BufferedImage image = ImageIO.read(inputStream);
                if (image == null) {
                    throw new IOException("Failed to read image data");
                }

                if (!ImageIO.write(image, "png", outputStream)) {
                    throw new IOException("Failed to write PNG image data");
                }
            } finally {
                PNG_CONVERSION_PERMITS.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to convert image to PNG", e);
        }
    }
}
