package dev.pisti.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Past LocalDate dateOfBirth,
        @NotBlank @Size(max = 100) String drivingLicenseNumber,
        LocalDate drivingLicenseExpiry,
        @Size(max = 255) String address) {
}