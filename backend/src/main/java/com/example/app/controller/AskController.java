package com.example.app.controller;

import com.example.app.model.AskRequest;
import com.example.app.model.Result;
import com.example.app.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AskController {
  private static final Logger log = LoggerFactory.getLogger(AskController.class);
  private final LLMService llmService;

  public AskController(LLMService llmService) {
    this.llmService = llmService;
  }

  @PostMapping(path = "/api/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter ask(@RequestBody AskRequest request) {
    SseEmitter emitter = new SseEmitter(0L);
    try {
      log.info("Incoming ask request {}", request.getSessionId());
      llmService.streamAnswer(request.getQuestion(), request.getSessionId(), emitter);
    } catch (Exception error) {
      log.error("Ask failed", error);
      try {
        emitter.send(Result.error("请求失败"));
      } catch (Exception inner) {
        log.error("Emitter send error", inner);
      }
      emitter.completeWithError(error);
    }
    return emitter;
  }

  @GetMapping("/api/health")
  public Result<String> health() {
    return Result.ok("ok");
  }
}
