package dev.pisti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ReservationStatusRequest(
        @NotBlank @Pattern(regexp = "PENDING|CONFIRMED|CANCELLED|COMPLETED") String status) {
}