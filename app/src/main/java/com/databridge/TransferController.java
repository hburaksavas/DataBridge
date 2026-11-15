package com.databridge;

import java.sql.SQLException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
  private final TransferService service;

  public TransferController(TransferService service) {
    this.service = service;
  }

  @PostMapping("/preview")
  public TransferService.Preview preview(@RequestBody TransferService.Request request)
      throws SQLException {
    return service.preview(request);
  }

  @DeleteMapping("/preview/{token}")
  public void discard(@PathVariable String token) {
    service.discard(token);
  }

  @PostMapping
  public Map<String, Object> start(@RequestBody TransferService.RunRequest request) {
    return service.start(request.token());
  }

  @GetMapping
  public List<Map<String, Object>> history() {
    return service.history();
  }

  @GetMapping("/{id}")
  public Map<String, Object> job(@PathVariable String id) {
    return service.job(id);
  }

  @PostMapping("/{id}/cancel")
  public void cancel(@PathVariable String id) {
    service.cancel(id);
  }

}
