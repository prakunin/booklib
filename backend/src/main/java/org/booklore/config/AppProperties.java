package org.booklore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {
    private String pathConfig;
    private String bookdropFolder;

    /**
     * Directories the library path picker may browse.
     * <p>
     * Separate from {@link #pathConfig}, which is the application's own data directory — icons,
     * fonts, caches, bookdrop temporaries — and is not where anyone's books live. Every shipped
     * deployment mounts the library at {@code /books}: the Unraid template, the Helm chart, the
     * compose file and the podman quadlet all agree, so that is the default. An operator mounting
     * elsewhere overrides it with {@code APP_LIBRARY_ROOTS}.
     */
    private List<String> libraryRoots = List.of("/books");
    private String version;
    private RemoteAuth remoteAuth;
    private Boolean forceDisableOidc = false;

    /**
     * Type of disk storage where library files are stored.
     * Defaults to LOCAL. Set to NETWORK if using NFS, SMB/CIFS, or other network-mounted storage.
     * Some features like file move/reorganization are disabled on network storage due to
     * unreliable atomic operations that can cause data corruption or loss.
     */
    private String diskType = "LOCAL";

    public boolean isLocalStorage() {
        return "LOCAL".equalsIgnoreCase(diskType);
    }

    @Getter
    @Setter
    public static class RemoteAuth {
        private boolean enabled;
        private boolean createNewUsers;
        private String headerName;
        private String headerUser;
        private String headerEmail;
        private String headerGroups;
        private String adminGroup;
        private String groupsDelimiter = "\\s+";  // Default to whitespace for backward compatibility
    }
}
