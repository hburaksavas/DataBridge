package com.databridge;

import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(SchemaCompatibilityException.class)
  ResponseEntity<Map<String, Object>> schemaCompatibility(SchemaCompatibilityException e) {
    return ResponseEntity.badRequest().body(new ErrorGuidance.Failure("SCHEMA_COMPATIBILITY",
        e.getMessage(), List.of("Review the listed objects, adjust the selection or use a compatible test schema, then run the preview again."), null).body());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, Object>> validation(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(ErrorGuidance.validation(e.getMessage()).body());
  }

  @ExceptionHandler(SQLException.class)
  ResponseEntity<Map<String, Object>> sql(SQLException e) {
    return ResponseEntity.unprocessableEntity().body(ErrorGuidance.sql(e).body());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(new ErrorGuidance.Failure("INVALID_REQUEST",
        "The request or imported JSON does not match the expected format.",
        List.of("Use a scenario exported by DataBridge and check its required fields and value types.",
            "Reload the scenario and try again; keep the original import file unchanged."), null).body());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, Object>> upload(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(413).body(new ErrorGuidance.Failure("FILE_TOO_LARGE",
        "The selected import file exceeds the upload limit.",
        List.of("Choose a smaller file or split the import into smaller groups."), null).body());
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, Object>> storage(DataAccessException e) {
    return ResponseEntity.internalServerError().body(new ErrorGuidance.Failure("LOCAL_STORAGE",
        "The application could not read or save its local data.",
        List.of("Check free disk space and write access to the DataBridge application data directory.",
            "Close duplicate DataBridge instances and retry. Keep a backup before changing local data files."), null).body());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    return ResponseEntity.internalServerError().body(new ErrorGuidance.Failure("UNEXPECTED",
        "The application could not complete the operation.",
        List.of("Note the operation and the selected scenario or connection.",
            "If a transfer was starting or running, check Execution/History and target data before retrying.",
            "If the problem persists, share these steps and the application version with support."), null).body());
  }
}
