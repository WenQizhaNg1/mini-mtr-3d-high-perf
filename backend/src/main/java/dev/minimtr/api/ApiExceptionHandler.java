package dev.minimtr.api;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalidParameter(MethodArgumentTypeMismatchException error) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid " + error.getName() + " timestamp"));
    }

    @ExceptionHandler({org.jooq.exception.DataAccessException.class, org.springframework.dao.DataAccessException.class})
    public ResponseEntity<Map<String, String>> databaseError(Exception error) {
        log.error("Database request failed", error);
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
    }
}
