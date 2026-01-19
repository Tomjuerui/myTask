package com.example.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LLMService {
  private static final Logger log = LoggerFactory.getLogger(LLMService.class);
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private final OkHttpClient okHttpClient;
  private final ObjectMapper objectMapper;
  private final ToolService toolService;

  @Value("${ai.base-url:https://api.openai.com}")
  private String baseUrl;

  @Value("${ai.model:gpt-4o-mini}")
  private String model;

  @Value("${ai.api-key:}")
  private String apiKey;

  @Value("${ai.system-prompt:请优先调用工具获取实时信息，再给出回答}")
  private String systemPrompt;

  public LLMService(OkHttpClient okHttpClient, ObjectMapper objectMapper, ToolService toolService) {
    this.okHttpClient = okHttpClient;
    this.objectMapper = objectMapper;
    this.toolService = toolService;
  }

  public void streamAnswer(String question, String sessionId, SseEmitter emitter) {
    new Thread(() -> {
      try {
        log.info("LLM request start {}", sessionId);
        JsonNode firstResponse = callWithTools(question);
        if (hasToolCalls(firstResponse)) {
          Map<String, Object> streamPayload = buildToolFollowup(firstResponse, question);
          streamFinalAnswer(streamPayload, emitter);
        } else {
          String content = firstResponse.path("choices").path(0).path("message").path("content").asText();
          sendChunk(emitter, content, true);
        }
      } catch (Exception error) {
        log.error("LLM streaming failed", error);
        sendChunk(emitter, "服务异常", true);
      }
    }).start();
  }

  private JsonNode callWithTools(String question) throws IOException {
    Map<String, Object> payload = new HashMap<>();
    payload.put("model", model);
    payload.put("messages", List.of(
        Map.of("role", "system", "content", systemPrompt),
        Map.of("role", "user", "content", question)
    ));
    payload.put("tools", buildTools());
    payload.put("tool_choice", "auto");

    return postJson(payload);
  }

  private boolean hasToolCalls(JsonNode response) {
    JsonNode toolCalls = response.path("choices").path(0).path("message").path("tool_calls");
    return toolCalls.isArray() && !toolCalls.isEmpty();
  }

  private Map<String, Object> buildToolFollowup(JsonNode response, String question) throws IOException {
    JsonNode toolCalls = response.path("choices").path(0).path("message").path("tool_calls");

    log.info("Tool calls detected {}", toolCalls.size());

    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt));
    messages.add(Map.of("role", "user", "content", question));

    List<Map<String, Object>> toolResults = new ArrayList<>();
    for (JsonNode toolCall : toolCalls) {
      String toolName = toolCall.path("function").path("name").asText();
      String arguments = toolCall.path("function").path("arguments").asText();
      Map<String, Object> args = objectMapper.readValue(arguments, new TypeReference<>() {});
      String result = executeTool(toolName, args);
      Map<String, Object> toolMessage = new HashMap<>();
      toolMessage.put("role", "tool");
      toolMessage.put("tool_call_id", toolCall.path("id").asText());
      toolMessage.put("content", result);
      toolResults.add(toolMessage);
    }

    messages.addAll(toolResults);

    Map<String, Object> payload = new HashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("stream", true);

    return payload;
  }

  private void streamFinalAnswer(Map<String, Object> payload, SseEmitter emitter) throws IOException {
    Request request = buildRequest(payload, true);
    try (Response response = okHttpClient.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        sendChunk(emitter, "模型响应失败", true);
        return;
      }

      BufferedReader reader = new BufferedReader(
          new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith("data:")) {
          continue;
        }
        String data = line.replace("data:", "").trim();
        if ("[DONE]".equals(data)) {
          break;
        }
        JsonNode chunk = objectMapper.readTree(data);
        JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
        if (!delta.isMissingNode()) {
          sendChunk(emitter, delta.asText(), false);
        }
      }
      sendChunk(emitter, "", true);
    }
  }

  private String executeTool(String toolName, Map<String, Object> args) {
    if ("search_web".equals(toolName)) {
      String query = String.valueOf(args.getOrDefault("query", ""));
      return toolService.searchWeb(query);
    }
    if ("calc".equals(toolName)) {
      String expr = String.valueOf(args.getOrDefault("expr", ""));
      return String.valueOf(toolService.calc(expr));
    }
    return "未知工具";
  }

  private List<Map<String, Object>> buildTools() {
    List<Map<String, Object>> tools = new ArrayList<>();
    tools.add(Map.of(
        "type", "function",
        "function", Map.of(
            "name", "search_web",
            "description", "使用搜索引擎获取实时信息",
            "parameters", Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "搜索关键词")
                ),
                "required", List.of("query")
            )
        )
    ));
    tools.add(Map.of(
        "type", "function",
        "function", Map.of(
            "name", "calc",
            "description", "计算数学表达式",
            "parameters", Map.of(
                "type", "object",
                "properties", Map.of(
                    "expr", Map.of("type", "string", "description", "数学表达式")
                ),
                "required", List.of("expr")
            )
        )
    ));
    return tools;
  }

  private JsonNode postJson(Map<String, Object> payload) throws IOException {
    Map<String, Object> bodyMap = new HashMap<>(payload);
    bodyMap.put("stream", false);

    Request request = buildRequest(bodyMap, false);
    try (Response response = okHttpClient.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        throw new IOException("LLM response invalid");
      }
      return objectMapper.readTree(response.body().string());
    }
  }

  private Request buildRequest(Map<String, Object> payload, boolean stream) throws IOException {
    String url = baseUrl + "/v1/chat/completions";
    String json = objectMapper.writeValueAsString(payload);
    RequestBody body = RequestBody.create(json, JSON);
    return new Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer " + apiKey)
        .post(body)
        .build();
  }

  private void sendChunk(SseEmitter emitter, String delta, boolean finish) {
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("delta", delta);
      payload.put("finish", finish);
      emitter.send(payload);
      if (finish) {
        emitter.complete();
      }
    } catch (IOException error) {
      log.error("Send chunk failed", error);
      emitter.completeWithError(error);
    }
  }
}
