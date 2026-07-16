package org.booklore.model.dto.system;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemInfoDto {
    private ApplicationInfo application;
    private RuntimeInfo runtime;
    private OsInfo os;
    private DatabaseInfo database;
    private StorageInfo storage;
    private List<FilesystemInfo> filesystems;
    private List<LibraryPathInfo> libraryPaths;
    private ToolsInfo tools;
}
