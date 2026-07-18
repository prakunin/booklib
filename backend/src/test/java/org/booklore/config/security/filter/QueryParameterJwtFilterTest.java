package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import org.booklore.config.security.JwtUtils;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryParameterJwtFilterTest {

    @Mock private JwtUtils jwtUtils;
    @Mock private UserRepository userRepository;
    @Mock private BookLoreUserTransformer bookLoreUserTransformer;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_authenticatesValidMediaToken() throws Exception {
        QueryParameterJwtFilter filter = new QueryParameterJwtFilter(jwtUtils, userRepository, bookLoreUserTransformer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/books/1/download");
        request.addParameter("token", "media-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);
        BookLoreUserEntity entity = BookLoreUserEntity.builder().id(7L).username("reader").build();
        BookLoreUser user = BookLoreUser.builder().id(7L).build();
        when(jwtUtils.validateMediaToken("media-token")).thenReturn(true);
        when(jwtUtils.extractUserId("media-token")).thenReturn(7L);
        when(userRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(entity));
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
    }

    @Test
    void doFilter_rejectsNonMediaTokenWithoutAuthenticating() throws Exception {
        QueryParameterJwtFilter filter = new QueryParameterJwtFilter(jwtUtils, userRepository, bookLoreUserTransformer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/books/1/download");
        request.addParameter("token", "access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};
        when(jwtUtils.validateMediaToken("access-token")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository, never()).findByIdWithDetails(7L);
    }
}
