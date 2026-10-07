package com.dbu.handoff.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns failures into responses the caller can act on.
 *
 * <p>Error bodies carry field names only, never message content, in line with
 * the logging rules in the design.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidationFailure(
            MethodArgumentNotValidException e) {

        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));

        log.warn("rejected request: invalid fields {}", fields.keySet());

        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid request",
                "fields", fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onUnknownJourney(IllegalArgumentException e) {
        log.warn("rejected request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not found"));
    }

    /**
     * A database rule was broken. In practice this is
     * {@code idx_handoff_single_active_customer}: a second escalation arrived
     * for a customer who already has an episode in flight, under a different
     * chat journey.
     *
     * <p>Answered with 409 rather than 500 deliberately. A 500 tells the caller
     * the request failed and should be retried, and ElevenLabs would retry it
     * indefinitely — the state that makes it fail is not transient. A 409 says
     * the request was understood and refused, which is the truth.
     *
     * <p>The constraint message is not returned. It names internal objects, and
     * the caller can do nothing with it.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> onConstraintViolation(
            DataIntegrityViolationException e) {

        log.warn("rejected request: conflicts with existing state", e);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "conflicts with an interaction already in progress"));
    }
}
