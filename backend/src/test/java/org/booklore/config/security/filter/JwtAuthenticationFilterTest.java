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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private BookLoreUserTransformer bookLoreUserTransformer;
    @Mock private JwtUtils jwtUtils;
    @Mock private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_authenticatesValidAccessToken() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(bookLoreUserTransformer, jwtUtils, userRepository);
        MockHttpServletRequest request = request("access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};
        BookLoreUserEntity entity = BookLoreUserEntity.builder().id(7L).username("reader").build();
        BookLoreUser user = BookLoreUser.builder().id(7L).build();
        when(jwtUtils.validateAccessToken("access-token")).thenReturn(true);
        when(jwtUtils.extractUserId("access-token")).thenReturn(7L);
        when(userRepository.findByIdWithDetails(7L)).thenReturn(Optional.of(entity));
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(user);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
    }

    @Test
    void doFilter_rejectsMediaTokenInBearerHeaderWithoutAuthenticating() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(bookLoreUserTransformer, jwtUtils, userRepository);
        MockHttpServletRequest request = request("media-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};
        when(jwtUtils.validateAccessToken("media-token")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository, never()).findByIdWithDetails(7L);
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/books");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
