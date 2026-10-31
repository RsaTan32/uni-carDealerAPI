package dev.pisti.dto;

import java.time.LocalDate;

import dev.pisti.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class AuthRequests {
    private AuthRequests() {
    }

    public record Register(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 100) String drivingLicenseNumber,
            @Size(max = 50) String phone,
            @Past LocalDate dateOfBirth,
            LocalDate drivingLicenseExpiry,
            @Size(max = 255) String address) {
    }

    public record Login(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    public record CreateAccount(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotNull Role role,
            @Positive Long customerId) {
    }

    public record Access(@NotNull Role role, @NotNull Boolean enabled) {
    }
}