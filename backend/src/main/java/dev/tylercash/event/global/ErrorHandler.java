package dev.tylercash.event.global;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@ControllerAdvice
public class ErrorHandler {

    @ExceptionHandler({
        NoHandlerFoundException.class,
        HttpClientErrorException.NotFound.class,
        NoResourceFoundException.class
    })
    @ResponseBody
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleError404(HttpServletRequest request, Exception e) {
        return Map.of(
                "error", "Not found",
                "traceId", Optional.ofNullable(MDC.get("traceId")).orElse("unknown"),
                "timestamp", Instant.now().toString());
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        if (ex.getStatusCode().is4xxClientError()) {
            log.warn("Client error {}: {}", ex.getStatusCode().value(), ex.getReason());
        }
        return new ResponseEntity<>(
                Map.of(
                        "error", Optional.ofNullable(ex.getReason()).orElse("Request failed"),
                        "traceId", Optional.ofNullable(MDC.get("traceId")).orElse("unknown"),
                        "timestamp", Instant.now().toString()),
                ex.getStatusCode());
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> handleAllExceptions(Exception ex, WebRequest request) {
        log.error("Exception occurred: {}, Request Details: {}", ex.getMessage(), request.getDescription(false), ex);
        return new ResponseEntity<>(
                Map.of(
                        "error", "An internal error occurred. Please try again later.",
                        "traceId", Optional.ofNullable(MDC.get("traceId")).orElse("unknown"),
                        "timestamp", Instant.now().toString()),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ResponseStatus(BAD_REQUEST)
    @ResponseBody
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Error methodArgumentNotValidException(MethodArgumentNotValidException ex) {
        BindingResult result = ex.getBindingResult();
        List<FieldError> fieldErrors = result.getFieldErrors();
        log.warn(
                "Validation failed with {} error(s): {}",
                fieldErrors.size(),
                fieldErrors.stream()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage())
                        .toList());
        return processFieldErrors(fieldErrors);
    }

    private Error processFieldErrors(List<org.springframework.validation.FieldError> fieldErrors) {
        Error error = new Error(BAD_REQUEST.value(), "validation error");
        for (org.springframework.validation.FieldError fieldError : fieldErrors) {
            error.addFieldError(fieldError.getObjectName(), fieldError.getField(), fieldError.getDefaultMessage());
        }
        return error;
    }

    @Getter
    static class Error {
        private final int status;
        private final String message;
        private final String traceId;
        private final List<FieldError> fieldErrors = new ArrayList<>();

        Error(int status, String message) {
            this.status = status;
            this.message = message;
            this.traceId = Optional.ofNullable(MDC.get("traceId")).orElse("unknown");
        }

        public void addFieldError(String objectName, String path, String message) {
            FieldError error = new FieldError(objectName, path, message);
            fieldErrors.add(error);
        }
    }
}
