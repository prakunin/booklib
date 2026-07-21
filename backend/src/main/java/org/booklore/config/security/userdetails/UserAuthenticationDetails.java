package org.booklore.config.security.userdetails;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.util.Objects;

@Getter
public class UserAuthenticationDetails extends WebAuthenticationDetails {

    private final Long userId;

    public UserAuthenticationDetails(HttpServletRequest request, Long userId) {
        super(request);
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserAuthenticationDetails that) || !super.equals(o)) return false;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), userId);
    }
}
