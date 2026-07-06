package com.kite.trading.exception;

import com.kite.trading.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler for REST controllers.
 * 
 * This class handles all exceptions thrown by controllers and provides
 * consistent error response format across the application.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles KiteAuthenticationException.
     *
     * @param ex The authentication exception
     * @return ResponseEntity with 401 Unauthorized status
     */
    @ExceptionHandler(KiteAuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            final KiteAuthenticationException ex) {
        logger.error("Authentication error: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("AUTHENTICATION_ERROR", ex.getMessage()));
    }

    /**
     * Handles KiteApiException.
     *
     * @param ex The API exception
     * @return ResponseEntity with 502 Bad Gateway status
     */
    @ExceptionHandler(KiteApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
            final KiteApiException ex) {
        logger.error("API error: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("API_ERROR", ex.getMessage()));
    }

    /**
     * Handles static resource not found (e.g. missing favicon, unknown paths).
     * Logged at DEBUG level since these are typically harmless browser requests.
     *
     * @param ex The resource not found exception
     * @return ResponseEntity with 404 Not Found status
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(
            final NoResourceFoundException ex) {
        logger.debug("Resource not found: {}", ex.getMessage());
        return ResponseEntity.notFound().build();
    }

    /**
     * Handles generic exceptions.
     *
     * @param ex The generic exception
     * @return ResponseEntity with 500 Internal Server Error status
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            final Exception ex) {
        logger.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
