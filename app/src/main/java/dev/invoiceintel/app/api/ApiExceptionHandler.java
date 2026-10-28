package dev.invoiceintel.app.api;

import dev.invoiceintel.ingest.UnsupportedFileTypeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem details for every API error (architecture.md §8). Details carry codes, file
 * positions and detected types only, never file content (PII-8).
 */
@RestControllerAdvice(basePackages = "dev.invoiceintel.app.api")
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler
  ProblemDetail unsupportedFileType(UnsupportedFileTypeException e) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Only PDF, JPEG and PNG files are accepted; file "
                + e.fileIndex()
                + " is "
                + e.detectedType()
                + ".");
    problem.setProperty("fileIndex", e.fileIndex());
    problem.setProperty("detectedType", e.detectedType());
    return problem;
  }
}
