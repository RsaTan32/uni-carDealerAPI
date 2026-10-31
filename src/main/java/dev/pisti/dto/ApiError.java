package dev.pisti.dto;

import java.util.Map;

import jakarta.ws.rs.core.Response;

public record ApiError(int status, String code, String message, Map<String, String> errors) {
    public static ApiError of(int status, String message) {
        String code = switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
        return new ApiError(status, code, message, Map.of());
    }

    public static String defaultMessage(int status) {
        if (status >= 500) {
            return "Internal server error";
        }
        Response.Status known = Response.Status.fromStatusCode(status);
        return known == null ? "Request failed" : known.getReasonPhrase();
    }
}