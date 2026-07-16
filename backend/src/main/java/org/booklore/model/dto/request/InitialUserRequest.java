package org.booklore.model.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InitialUserRequest {
    @NotBlank
    private String username;

    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 72, message = "Password must be no more than 72 characters long")
    private String password;
}
