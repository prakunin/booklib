package org.booklore.service.reader;

import org.booklore.model.dto.response.CbxPageDimension;
import org.booklore.model.dto.response.CbxPageInfo;
import org.booklore.model.enums.BookFileType;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * A book the page reader can show: something that has a number of pages, knows how big each one is,
 * and can hand one over as an image.
 * <p>
 * The reader in the browser is the same for every implementation - it asks for page counts,
 * dimensions and JPEGs and does not care whether they come out of a comic archive or a DjVu
 * decoder. This interface is where that indifference is made real, so a new page-based format costs
 * one implementation and one line in {@link PageImageSourceResolver} rather than a branch in every
 * endpoint.
 */
public interface PageImageSource {

    /** The book file type this source serves. Used by the resolver to pick between implementations. */
    BookFileType supportedType();

    List<Integer> getAvailablePages(Long bookId, String bookType);

    List<CbxPageInfo> getPageInfo(Long bookId, String bookType);

    List<CbxPageDimension> getPageDimensions(Long bookId, String bookType);

    void streamPageImage(Long bookId, String bookType, int page, OutputStream outputStream) throws IOException;
}
