package org.booklore.service.koreader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.KoreaderUserMapper;
import org.booklore.model.dto.KoreaderUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.KoreaderUserEntity;
import org.booklore.repository.KoreaderUserRepository;
import org.booklore.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KoreaderUserService {

    private static final String KOREADER_USER_NOT_FOUND_MESSAGE = "Koreader user not found for BookLore user ID: ";

    private final AuthenticationService authService;
    private final UserRepository userRepository;
    private final KoreaderUserRepository koreaderUserRepository;
    private final KoreaderUserMapper koreaderUserMapper;
    private final KoreaderCredentialService koreaderCredentialService;

    @Transactional
    public KoreaderUser upsertUser(String username, String rawPassword) {
        Long ownerId = authService.getAuthenticatedUser().getId();
        BookLoreUserEntity owner = userRepository.findById(ownerId)
                .orElseThrow(() -> ApiError.USER_NOT_FOUND.createException(ownerId));

        Optional<KoreaderUserEntity> existing = koreaderUserRepository.findByBookLoreUserId(ownerId);
        boolean isUpdate = existing.isPresent();
        KoreaderUserEntity user = existing.orElseGet(() -> {
            KoreaderUserEntity u = new KoreaderUserEntity();
            u.setBookLoreUser(owner);
            return u;
        });

        user.setUsername(username);
        user.setPasswordHash(koreaderCredentialService.hashRawPassword(rawPassword));
        KoreaderUserEntity saved = koreaderUserRepository.save(user);

        log.info("upsertUser: {} KoreaderUser [id={}, username='{}'] for BookLoreUser='{}'",
                isUpdate ? "Updated" : "Created",
                saved.getId(), saved.getUsername(),
                authService.getAuthenticatedUser().getUsername());

        return koreaderUserMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public KoreaderUser getUser() {
        Long id = authService.getAuthenticatedUser().getId();
        KoreaderUserEntity user = koreaderUserRepository.findByBookLoreUserId(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException(KOREADER_USER_NOT_FOUND_MESSAGE + id));
        return koreaderUserMapper.toDto(user);
    }

    @Transactional
    public void toggleSync(boolean enabled) {
        Long id = authService.getAuthenticatedUser().getId();
        KoreaderUserEntity user = koreaderUserRepository.findByBookLoreUserId(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException(KOREADER_USER_NOT_FOUND_MESSAGE + id));
        user.setSyncEnabled(enabled);
        koreaderUserRepository.save(user);
    }

    @Transactional
    public void toggleSyncProgressWithWebReader(boolean enabled) {
        Long id = authService.getAuthenticatedUser().getId();
        KoreaderUserEntity user = koreaderUserRepository.findByBookLoreUserId(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException(KOREADER_USER_NOT_FOUND_MESSAGE + id));
        user.setSyncWithWebReader(enabled);
        koreaderUserRepository.save(user);
    }
}
