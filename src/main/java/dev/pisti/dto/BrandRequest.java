package dev.pisti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BrandRequest(@NotBlank @Size(max = 100) String name) {
}