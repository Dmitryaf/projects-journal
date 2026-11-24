package io.github.dmitryaf.controlcenter;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Map<String, String>> taskError(ApiFailure failure) {
        return error(failure.status(), failure.getMessage());
    }

    @ExceptionHandler({SQLException.class, IOException.class})
    ResponseEntity<Map<String, String>> operationError(Exception failure) {
        log.warn("Local backend operation failed ({})", failure.getClass().getSimpleName());
        return error(500, "Операция не выполнена. Проверьте доступ к файлам и повторите.");
    }

    static ResponseEntity<Map<String, String>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
