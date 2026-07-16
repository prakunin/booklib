package org.booklore.service.library;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.mapper.BookMapper;
import org.booklore.mapper.LibraryMapper;
import org.booklore.service.inpx.InpxScanControl;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.LibraryPath;
import org.booklore.model.dto.request.CreateLibraryRequest;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.LibrarySourceType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.monitoring.LibraryWatchService;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryServiceInpxTest {

    @Mock
    private LibraryRepository libraryRepository;
    @Mock
    private LibraryPathRepository libraryPathRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private BookMapper bookMapper;
    @Mock
    private LibraryMapper libraryMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FileService fileService;
    @Mock
    private LibraryWatchService libraryWatchService;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private LibraryProcessingService libraryProcessingService;
    @Mock
    private Executor taskExecutor;
    @Mock
    private InpxScanControl inpxScanControl;

    @InjectMocks
    private LibraryService libraryService;

    private BookLoreUser user;
    private BookLoreUserEntity userEntity;

    @BeforeEach
    void setUp() {
        user = BookLoreUser.builder().id(1L).isDefaultPassword(false).build();
        userEntity = BookLoreUserEntity.builder().id(1L).username("testuser").build();
    }

    private CreateLibraryRequest.CreateLibraryRequestBuilder inpxRequest(Path archiveDir) {
        return CreateLibraryRequest.builder()
                .name("Flibusta")
                .sourceType(LibrarySourceType.INPX)
                .paths(List.of(LibraryPath.builder().path(archiveDir.toString()).build()))
                .inpxArchivePath(archiveDir.toString());
    }

    @Test
    void createLibrary_withoutIndex_savesLibraryWithNullInpxPath(@TempDir Path archiveDir) {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));
        when(libraryRepository.save(any(LibraryEntity.class))).thenAnswer(invocation -> {
            LibraryEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        when(libraryMapper.toLibrary(any(LibraryEntity.class))).thenReturn(Library.builder().name("Flibusta").build());

        libraryService.createLibrary(inpxRequest(archiveDir).inpxPath(null).build());

        ArgumentCaptor<LibraryEntity> captor = ArgumentCaptor.forClass(LibraryEntity.class);
        verify(libraryRepository).save(captor.capture());
        LibraryEntity saved = captor.getValue();
        assertThat(saved.getSourceType()).isEqualTo(LibrarySourceType.INPX);
        assertThat(saved.getInpxPath()).isNull();
        assertThat(saved.getInpxArchivePath()).isEqualTo(archiveDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void createLibrary_withBlankIndex_savesLibraryWithNullInpxPath(@TempDir Path archiveDir) {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));
        when(libraryRepository.save(any(LibraryEntity.class))).thenAnswer(invocation -> {
            LibraryEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        when(libraryMapper.toLibrary(any(LibraryEntity.class))).thenReturn(Library.builder().name("Flibusta").build());

        libraryService.createLibrary(inpxRequest(archiveDir).inpxPath("   ").build());

        ArgumentCaptor<LibraryEntity> captor = ArgumentCaptor.forClass(LibraryEntity.class);
        verify(libraryRepository).save(captor.capture());
        assertThat(captor.getValue().getInpxPath()).isNull();
    }

    @Test
    void createLibrary_withoutArchiveDirectory_isRejected(@TempDir Path archiveDir) {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));

        CreateLibraryRequest request = inpxRequest(archiveDir)
                .inpxArchivePath(null)
                .build();

        assertThatThrownBy(() -> libraryService.createLibrary(request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void createLibrary_withNonInpxIndexFile_isRejected(@TempDir Path archiveDir) throws Exception {
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(userRepository.findById(1L)).thenReturn(Optional.of(userEntity));

        Path notAnIndex = archiveDir.resolve("catalog.txt");
        java.nio.file.Files.writeString(notAnIndex, "nope");

        CreateLibraryRequest request = inpxRequest(archiveDir).inpxPath(notAnIndex.toString()).build();

        assertThatThrownBy(() -> libraryService.createLibrary(request))
                .isInstanceOf(APIException.class);
    }

    @Test
    void updateLibrary_withBlankInpxPath_savesLibraryWithNullInpxPath(@TempDir Path archiveDir) {
        LibraryEntity existing = LibraryEntity.builder()
                .id(1L)
                .name("Flibusta")
                .sourceType(LibrarySourceType.INPX)
                .inpxArchivePath(archiveDir.toAbsolutePath().normalize().toString())
                .inpxPath("/some/old/path.inpx")
                .libraryPaths(new java.util.ArrayList<>())
                .watch(false)
                .build();

        CreateLibraryRequest request = inpxRequest(archiveDir)
                .inpxPath("   ")
                .build();

        when(libraryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(libraryRepository.save(any(LibraryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(libraryMapper.toLibrary(any(LibraryEntity.class))).thenReturn(Library.builder().name("Flibusta").build());

        libraryService.updateLibrary(request, 1L);

        ArgumentCaptor<LibraryEntity> captor = ArgumentCaptor.forClass(LibraryEntity.class);
        verify(libraryRepository).save(captor.capture());
        assertThat(captor.getValue().getInpxPath()).isNull();
    }

    @Test
    void cancelScan_doesNothingWhenTheLibraryIsNotCurrentlyScanning() {
        // A cancel that arrives when no scan is running (e.g. the click races the terminal
        // COMPLETED event) must not set a flag that nothing will ever clear - that flag would
        // otherwise poison the *next* scan of this library, which would abort at its first
        // batch boundary and report CANCELLED despite never having been asked to cancel.
        libraryService.cancelScan(7L);

        org.mockito.Mockito.verifyNoInteractions(inpxScanControl);
    }

    @Test
    void cancelScan_forwardsTheCancellationWhileTheLibraryIsActuallyScanning() throws Exception {
        long libraryId = 7L;
        LibraryEntity library = LibraryEntity.builder().id(libraryId).name("Flibusta").build();
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

        org.mockito.ArgumentCaptor<Runnable> taskCaptor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doNothing().when(taskExecutor).execute(taskCaptor.capture());

        // While rescanLibrary's background task is running, the library is in
        // scanningLibraries, so a cancel for it must be forwarded to InpxScanControl.
        org.mockito.Mockito.doAnswer(invocation -> {
            libraryService.cancelScan(libraryId);
            org.mockito.Mockito.verify(inpxScanControl).requestCancel(libraryId);
            return null;
        }).when(libraryProcessingService).rescanLibrary(any());

        libraryService.rescanLibrary(libraryId);
        taskCaptor.getValue().run();
    }
}
