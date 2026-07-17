package org.booklore.model.dto.inpx;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class InpxBookDto {
    private String id;
    private List<String> authors;
    private List<String> genres;
    private String title;
    private String series;
    private String seriesNumber;
    private String fileName;
    private String extension;
    private String libraryId;
    private String date;
    private String language;
    private Double rating;
    private String archiveName;
    private Long fileSizeKb;
}
