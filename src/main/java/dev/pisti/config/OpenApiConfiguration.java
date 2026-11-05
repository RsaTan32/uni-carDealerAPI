package dev.pisti.config;

import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import jakarta.ws.rs.core.Application;

@OpenAPIDefinition(info = @Info(
        title = "Car Rental API",
        version = "1.0.0",
        description = "API for car rental inventory, customers and reservations."))
@SecurityScheme(
        securitySchemeName = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfiguration extends Application {
}