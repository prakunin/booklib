package org.booklore.service.oidc;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.OidcGroupMappingMapper;
import org.booklore.model.dto.OidcGroupMapping;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.OidcGroupMappingEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.OidcGroupMappingRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

@Slf4j
@Service
@AllArgsConstructor
public class OidcGroupMappingService {

    private final OidcGroupMappingRepository repository;
    private final OidcGroupMappingMapper mapper;
    private final AuditService auditService;
    private final AppSettingService appSettingService;
    private final LibraryRepository libraryRepository;
    private final UserRepository userRepository;

    public List<OidcGroupMapping> getAll() {
        return mapper.toDtoList(repository.findAll());
    }

    public OidcGroupMapping create(OidcGroupMapping dto) {
        OidcGroupMappingEntity entity = mapper.toEntity(dto);
        entity.setId(null);
        OidcGroupMappingEntity saved = repository.save(entity);
        auditService.log(AuditAction.OIDC_GROUP_MAPPING_CREATED,
                "Created OIDC group mapping: " + saved.getOidcGroupClaim());
        return mapper.toDto(saved);
    }

    public OidcGroupMapping update(Long id, OidcGroupMapping dto) {
        OidcGroupMappingEntity newEntity = mapper.toEntity(dto);

        OidcGroupMappingEntity existing = repository.findById(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("OIDC group mapping not found"));
        existing.setOidcGroupClaim(newEntity.getOidcGroupClaim());
        existing.setAdmin(newEntity.isAdmin());
        existing.setPermissions(newEntity.getPermissions());
        existing.setLibraryIds(newEntity.getLibraryIds());
        existing.setDescription(newEntity.getDescription());
        OidcGroupMappingEntity saved = repository.save(existing);
        auditService.log(AuditAction.OIDC_GROUP_MAPPING_UPDATED,
                "Updated OIDC group mapping: " + saved.getOidcGroupClaim());
        return mapper.toDto(saved);
    }

    public void delete(Long id) {
        OidcGroupMappingEntity existing = repository.findById(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("OIDC group mapping not found"));
        repository.delete(existing);
        auditService.log(AuditAction.OIDC_GROUP_MAPPING_DELETED,
                "Deleted OIDC group mapping: " + existing.getOidcGroupClaim());
    }

    @Transactional
    public void syncUserGroups(BookLoreUserEntity user, List<String> groups) {
        if (groups == null || groups.isEmpty()) return;

        String syncMode = appSettingService.getAppSettings().getOidcGroupSyncMode();
        if (syncMode == null || "DISABLED".equals(syncMode)) return;

        List<OidcGroupMappingEntity> matchingMappings = repository.findByOidcGroupClaimIn(groups);
        if (matchingMappings.isEmpty()) return;

        MergedGroupSettings merged = mergeMappings(matchingMappings);
        UserPermissionsEntity perms = ensureUserPermissions(user);

        switch (syncMode) {
            case "ON_LOGIN" -> applyOnLoginMode(user, perms, merged);
            case "ON_LOGIN_ADDITIVE" -> applyOnLoginAdditiveMode(user, perms, merged);
            default -> {
                return;
            }
        }

        userRepository.save(user);
        log.info("Synced OIDC group permissions for user '{}' (mode: {})", user.getUsername(), syncMode);
    }

    private MergedGroupSettings mergeMappings(List<OidcGroupMappingEntity> matchingMappings) {
        boolean mergedAdmin = false;
        Set<String> mergedPermissions = new HashSet<>();
        Set<Long> mergedLibraryIds = new HashSet<>();

        for (OidcGroupMappingEntity mapping : matchingMappings) {
            if (mapping.isAdmin()) mergedAdmin = true;

            var mappingDto = mapper.toDto(mapping);
            if (mappingDto != null) {
                mergedPermissions.addAll(mappingDto.permissions());
                mergedLibraryIds.addAll(mappingDto.libraryIds());
            }
        }
        return new MergedGroupSettings(mergedAdmin, mergedPermissions, mergedLibraryIds);
    }

    private UserPermissionsEntity ensureUserPermissions(BookLoreUserEntity user) {
        UserPermissionsEntity perms = user.getPermissions();
        if (perms == null) {
            perms = new UserPermissionsEntity();
            perms.setUser(user);
            user.setPermissions(perms);
        } else if (perms.getUser() == null) {
            perms.setUser(user);
        }
        return perms;
    }

    private void applyOnLoginMode(BookLoreUserEntity user, UserPermissionsEntity perms, MergedGroupSettings merged) {
        applyPermissions(perms, merged.permissions(), merged.admin(), false);
        List<LibraryEntity> libraries = libraryRepository.findAllById(merged.libraryIds());
        user.setLibraries(new HashSet<>(libraries));
    }

    private void applyOnLoginAdditiveMode(BookLoreUserEntity user, UserPermissionsEntity perms, MergedGroupSettings merged) {
        applyPermissions(perms, merged.permissions(), merged.admin(), true);
        Set<Long> existingLibIds = new HashSet<>();
        if (user.getLibraries() != null) {
            user.getLibraries().forEach(lib -> existingLibIds.add(lib.getId()));
        }
        Set<Long> allLibIds = new HashSet<>(existingLibIds);
        allLibIds.addAll(merged.libraryIds());
        if (!allLibIds.equals(existingLibIds)) {
            List<LibraryEntity> libraries = libraryRepository.findAllById(allLibIds);
            user.setLibraries(new HashSet<>(libraries));
        }
    }

    private record MergedGroupSettings(boolean admin, Set<String> permissions, Set<Long> libraryIds) {
    }

    private void applyPermissions(UserPermissionsEntity perms, Set<String> permissions, boolean isAdmin, boolean additive) {
        if (additive) {
            applyPermissionsAdditive(perms, permissions, isAdmin);
        } else {
            applyPermissionsReplace(perms, permissions, isAdmin);
        }
    }

    private void applyPermissionsReplace(UserPermissionsEntity perms, Set<String> permissions, boolean isAdmin) {
        perms.setPermissionAdmin(isAdmin);
        for (PermissionBinding binding : PERMISSION_BINDINGS) {
            binding.setter().accept(perms, permissions.contains(binding.key()));
        }
    }

    private void applyPermissionsAdditive(UserPermissionsEntity perms, Set<String> permissions, boolean isAdmin) {
        if (isAdmin) perms.setPermissionAdmin(true);
        for (PermissionBinding binding : PERMISSION_BINDINGS) {
            if (permissions.contains(binding.key())) binding.setter().accept(perms, true);
        }
    }

    private record PermissionBinding(String key, BiConsumer<UserPermissionsEntity, Boolean> setter) {
    }

    private static final List<PermissionBinding> PERMISSION_BINDINGS = List.of(
            new PermissionBinding("permissionUpload", UserPermissionsEntity::setPermissionUpload),
            new PermissionBinding("permissionDownload", UserPermissionsEntity::setPermissionDownload),
            new PermissionBinding("permissionEditMetadata", UserPermissionsEntity::setPermissionEditMetadata),
            new PermissionBinding("permissionManageLibrary", UserPermissionsEntity::setPermissionManageLibrary),
            new PermissionBinding("permissionEmailBook", UserPermissionsEntity::setPermissionEmailBook),
            new PermissionBinding("permissionDeleteBook", UserPermissionsEntity::setPermissionDeleteBook),
            new PermissionBinding("permissionAccessOpds", UserPermissionsEntity::setPermissionAccessOpds),
            new PermissionBinding("permissionSyncKoreader", UserPermissionsEntity::setPermissionSyncKoreader),
            new PermissionBinding("permissionSyncKobo", UserPermissionsEntity::setPermissionSyncKobo),
            new PermissionBinding("permissionManageMetadataConfig", UserPermissionsEntity::setPermissionManageMetadataConfig),
            new PermissionBinding("permissionAccessBookdrop", UserPermissionsEntity::setPermissionAccessBookdrop),
            new PermissionBinding("permissionAccessLibraryStats", UserPermissionsEntity::setPermissionAccessLibraryStats),
            new PermissionBinding("permissionAccessUserStats", UserPermissionsEntity::setPermissionAccessUserStats),
            new PermissionBinding("permissionAccessTaskManager", UserPermissionsEntity::setPermissionAccessTaskManager),
            new PermissionBinding("permissionManageGlobalPreferences", UserPermissionsEntity::setPermissionManageGlobalPreferences),
            new PermissionBinding("permissionManageIcons", UserPermissionsEntity::setPermissionManageIcons),
            new PermissionBinding("permissionManageFonts", UserPermissionsEntity::setPermissionManageFonts),
            new PermissionBinding("permissionBulkAutoFetchMetadata", UserPermissionsEntity::setPermissionBulkAutoFetchMetadata),
            new PermissionBinding("permissionBulkCustomFetchMetadata", UserPermissionsEntity::setPermissionBulkCustomFetchMetadata),
            new PermissionBinding("permissionBulkEditMetadata", UserPermissionsEntity::setPermissionBulkEditMetadata),
            new PermissionBinding("permissionBulkRegenerateCover", UserPermissionsEntity::setPermissionBulkRegenerateCover),
            new PermissionBinding("permissionMoveOrganizeFiles", UserPermissionsEntity::setPermissionMoveOrganizeFiles),
            new PermissionBinding("permissionBulkLockUnlockMetadata", UserPermissionsEntity::setPermissionBulkLockUnlockMetadata),
            new PermissionBinding("permissionBulkResetBookloreReadProgress", UserPermissionsEntity::setPermissionBulkResetBookloreReadProgress),
            new PermissionBinding("permissionBulkResetKoReaderReadProgress", UserPermissionsEntity::setPermissionBulkResetKoReaderReadProgress),
            new PermissionBinding("permissionBulkResetBookReadStatus", UserPermissionsEntity::setPermissionBulkResetBookReadStatus)
    );
}
