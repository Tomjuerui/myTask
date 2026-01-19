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

/**
 * 问答控制器，提供AI问答相关的RESTful接口
 */
@RestController
public class AskController {
  private static final Logger log = LoggerFactory.getLogger(AskController.class);
  private final LLMService llmService;

  /**
   * 构造函数
   * 
   * @param llmService LLM服务实例，用于处理AI问答逻辑
   */
  public AskController(LLMService llmService) {
    this.llmService = llmService;
  }

  /**
   * AI问答接口，返回SSE流式响应
   * 
   * @param request 包含问题和会话ID的请求体
   * @return SseEmitter 用于发送流式响应
   */
  @PostMapping(path = "/api/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter ask(@RequestBody AskRequest request) {
    // 创建SSE发射器，设置超时时间为0（永不超时）
    SseEmitter emitter = new SseEmitter(0L);
    try {
      log.info("Incoming ask request {}", request.getSessionId());
      // 调用LLM服务处理问答请求
      llmService.streamAnswer(request.getQuestion(), request.getSessionId(), emitter);
    } catch (Exception error) {
      log.error("Ask failed", error);
      try {
        // 发送错误结果
        emitter.send(Result.error("请求失败"));
      } catch (Exception inner) {
        log.error("Emitter send error", inner);
      }
      // 标记发射器完成并附带错误信息
      emitter.completeWithError(error);
    }
    return emitter;
  }

  /**
   * 健康检查接口
   * 
   * @return Result<String> 包含健康状态的结果对象
   */
  @GetMapping("/api/health")
  public Result<String> health() {
    // 返回健康状态为ok
    return Result.ok("ok");
  }
}
