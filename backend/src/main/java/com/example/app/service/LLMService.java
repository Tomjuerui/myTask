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

/**
 * LLM服务类，负责与AI模型交互，处理工具调用，并返回流式响应
 */
@Service
public class LLMService {
  private static final Logger log = LoggerFactory.getLogger(LLMService.class);
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private final OkHttpClient okHttpClient;
  private final ObjectMapper objectMapper;
  private final ToolService toolService;

  @Value("${ai.base-url:https://api.openai.com}")
  private String baseUrl; // AI模型API基础URL

  @Value("${ai.model:gpt-4o-mini}")
  private String model; // AI模型名称

  @Value("${ai.api-key:}")
  private String apiKey; // AI模型API密钥

  @Value("${ai.system-prompt:请优先调用工具获取实时信息，再给出回答}")
  private String systemPrompt; // 系统提示词

  /**
   * 构造函数
   * 
   * @param okHttpClient OkHttp客户端实例
   * @param objectMapper Jackson对象映射器
   * @param toolService  工具服务实例
   */
  public LLMService(OkHttpClient okHttpClient, ObjectMapper objectMapper, ToolService toolService) {
    this.okHttpClient = okHttpClient;
    this.objectMapper = objectMapper;
    this.toolService = toolService;
  }

  /**
   * 流式回答用户问题
   * 
   * @param question  用户问题
   * @param sessionId 会话ID
   * @param emitter   SSE发射器，用于向客户端发送流式响应
   */
  public void streamAnswer(String question, String sessionId, SseEmitter emitter) {
    // 在新线程中处理，避免阻塞主线程
    new Thread(() -> {
      try {
        log.info("LLM request start {}", sessionId);

        // 特殊处理：直接处理数学计算请求
        if (question.startsWith("计算：")) {
          // 提取数学表达式
          String expr = question.substring(3).trim();
          log.info("直接处理数学计算: {}", expr);

          // 直接调用calc工具
          String result = executeTool("calc", Map.of("expr", expr));
          log.info("计算结果: {}", result);

          // 直接返回计算结果
          sendChunk(emitter, result, true);
          return;
        }

        // 正常处理流程
        // 第一次调用AI模型，携带工具定义
        JsonNode firstResponse = callWithTools(question);
        // 检查是否需要调用工具
        if (hasToolCalls(firstResponse)) {
          // 构建工具调用后续请求
          Map<String, Object> streamPayload = buildToolFollowup(firstResponse, question);
          // 流式返回最终答案
          streamFinalAnswer(streamPayload, emitter);
        } else {
          // 直接返回答案
          String content = firstResponse.path("choices").path(0).path("message").path("content").asText();
          sendChunk(emitter, content, true);
        }
      } catch (Exception error) {
        log.error("LLM streaming failed", error);
        sendChunk(emitter, "服务异常", true);
      }
    }).start();
  }

  /**
   * 调用AI模型，携带工具定义
   * 
   * @param question 用户问题
   * @return AI模型响应
   * @throws IOException IO异常
   */
  private JsonNode callWithTools(String question) throws IOException {
    Map<String, Object> payload = new HashMap<>();
    payload.put("model", model);
    // 构建消息列表
    payload.put("messages", List.of(
        Map.of("role", "system", "content", systemPrompt),
        Map.of("role", "user", "content", question)));
    // 添加工具定义
    payload.put("tools", buildTools());
    payload.put("tool_choice", "auto"); // 自动选择是否调用工具

    // 针对DeepSeek API，调整参数设置
    if (baseUrl.contains("deepseek")) {
      // 移除dsml参数，避免返回DSML格式
      payload.put("dsml", "false");
      // 不使用json_object格式，避免API错误
      payload.remove("response_format");
    } else {
      // 其他API不强制使用json_object格式，避免API错误
      payload.remove("response_format");
    }

    return postJson(payload);
  }

  /**
   * 检查AI响应是否包含工具调用
   * 
   * @param response AI模型响应
   * @return 是否包含工具调用
   */
  private boolean hasToolCalls(JsonNode response) {
    // 检查标准格式的tool_calls
    JsonNode toolCalls = response.path("choices").path(0).path("message").path("tool_calls");
    if (toolCalls.isArray() && !toolCalls.isEmpty()) {
      return true;
    }

    // 检查DSML格式的function_calls
    JsonNode content = response.path("choices").path(0).path("message").path("content");
    if (content.isTextual()) {
      String contentText = content.asText();
      // 检查是否包含DSML格式的function_calls或invoke标签
      if (contentText.contains("function_calls") || contentText.contains("<｜DSML｜invoke")) {
        return true;
      }
    }

    return false;
  }

  /**
   * 构建工具调用后续请求
   * 
   * @param response 包含工具调用的AI响应
   * @param question 用户原始问题
   * @return 构建好的请求体
   * @throws IOException IO异常
   */
  private Map<String, Object> buildToolFollowup(JsonNode response, String question) throws IOException {
    List<Map<String, Object>> messages = new ArrayList<>();
    // 添加系统提示和用户问题
    messages.add(Map.of("role", "system", "content", systemPrompt));
    messages.add(Map.of("role", "user", "content", question));

    List<Map<String, Object>> toolResults = new ArrayList<>();

    // 检查是否为DSML格式响应
    JsonNode content = response.path("choices").path(0).path("message").path("content");
    if (content.isTextual()) {
      String contentText = content.asText();
      if (contentText.contains("function_calls") || contentText.contains("<｜DSML｜invoke")) {
        // 处理DSML格式的响应
        log.info("DSML format detected");
        String dsmlContent = content.asText();

        // 解析DSML格式，提取工具调用信息
        List<Map<String, Object>> dsmlToolCalls = parseDSMLToolCalls(dsmlContent);
        log.info("DSML tool calls parsed: {}", dsmlToolCalls.size());

        // 添加AI的工具调用消息（包含DSML格式）
        Map<String, Object> aiMessageMap = new HashMap<>();
        aiMessageMap.put("role", "assistant");
        aiMessageMap.put("content", dsmlContent);
        messages.add(aiMessageMap);

        // 执行每个工具调用
        for (int i = 0; i < dsmlToolCalls.size(); i++) {
          Map<String, Object> toolCall = dsmlToolCalls.get(i);
          String toolName = (String) toolCall.get("name");
          Map<String, Object> args = (Map<String, Object>) toolCall.get("arguments");
          // 执行工具
          String result = executeTool(toolName, args);
          log.info("Tool execution result for {}: {}", toolName, result);
          // 构建工具结果消息
          Map<String, Object> toolMessage = new HashMap<>();
          toolMessage.put("role", "tool");
          toolMessage.put("tool_call_id", "dsml-tool-call-" + i); // 添加唯一ID
          toolMessage.put("name", toolName);
          toolMessage.put("content", result);
          messages.add(toolMessage);
          // 保存工具结果，用于构建最终请求
          toolResults.add(Map.of(
              "role", "tool",
              "name", toolName,
              "content", result));
        }
      }
    } else {
      // 处理标准格式的响应
      JsonNode toolCalls = response.path("choices").path(0).path("message").path("tool_calls");
      log.info("Tool calls detected {}", toolCalls.size());

      // 添加AI的工具调用消息（完整的AI回复）
      JsonNode aiMessage = response.path("choices").path(0).path("message");
      Map<String, Object> aiMessageMap = new HashMap<>();
      aiMessageMap.put("role", aiMessage.path("role").asText());
      aiMessageMap.put("tool_calls",
          objectMapper.readValue(aiMessage.path("tool_calls").toString(),
              new TypeReference<List<Map<String, Object>>>() {
              }));
      messages.add(aiMessageMap);

      // 执行每个工具调用
      for (JsonNode toolCall : toolCalls) {
        String toolName = toolCall.path("function").path("name").asText();
        String arguments = toolCall.path("function").path("arguments").asText();
        // 解析工具参数
        Map<String, Object> args = objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {
        });
        // 执行工具
        String result = executeTool(toolName, args);
        log.info("Tool execution result for {}: {}", toolName, result);
        // 构建工具结果消息
        Map<String, Object> toolMessage = new HashMap<>();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", toolCall.path("id").asText());
        toolMessage.put("name", toolName);
        toolMessage.put("content", result);
        messages.add(toolMessage);
        // 保存工具结果，用于构建最终请求
        toolResults.add(Map.of(
            "role", "tool",
            "name", toolName,
            "content", result));
      }
    }

    // 构建最终请求体
    Map<String, Object> payload = new HashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("stream", true); // 启用流式响应

    // 针对DeepSeek API，添加参数确保返回自然语言回答
    if (baseUrl.contains("deepseek")) {
      // 禁用DSML格式，返回普通JSON
      payload.put("dsml", "false");
      // 确保返回自然语言，不要返回DSML格式
      payload.put("response_format", Map.of("type", "text"));
    }

    return payload;
  }

  /**
   * 解析DSML格式的工具调用
   * 
   * @param dsmlContent DSML格式的内容
   * @return 解析后的工具调用列表
   */
  private List<Map<String, Object>> parseDSMLToolCalls(String dsmlContent) {
    List<Map<String, Object>> toolCalls = new ArrayList<>();
    
    log.info("Parsing DSML content: {}", dsmlContent);
    
    // 先移除外层的function_calls标签
    String cleanedContent = dsmlContent.replaceAll("<｜DSML｜function_calls>\s*", "").replaceAll("\s*</｜DSML｜function_calls>", "");
    log.info("Cleaned DSML content: {}", cleanedContent);
    
    // 简单的DSML解析，提取工具调用信息
    // 匹配 <｜DSML｜invoke name="tool_name">...</｜DSML｜invoke>
    java.util.regex.Pattern invokePattern = java.util.regex.Pattern.compile(
        "<｜DSML｜invoke\s+name=\"([^\"]+)\"[^>]*>(.*?)</｜DSML｜invoke>",
        java.util.regex.Pattern.DOTALL
    );
    java.util.regex.Matcher invokeMatcher = invokePattern.matcher(cleanedContent);
    
    while (invokeMatcher.find()) {
      String toolName = invokeMatcher.group(1);
      String invokeContent = invokeMatcher.group(2);
      
      log.info("Found invoke: {}, content: {}", toolName, invokeContent);
      
      // 提取参数
      java.util.regex.Pattern paramPattern = java.util.regex.Pattern.compile(
          "<｜DSML｜parameter\s+name=\"([^\"]+)\"[^>]*>(.*?)</｜DSML｜parameter>",
          java.util.regex.Pattern.DOTALL
      );
      java.util.regex.Matcher paramMatcher = paramPattern.matcher(invokeContent);
      
      Map<String, Object> args = new HashMap<>();
      while (paramMatcher.find()) {
        String paramName = paramMatcher.group(1);
        String paramValue = paramMatcher.group(2);
        // 清除可能的string属性
        paramValue = paramValue.replaceAll("string=\"true\"\s*", "");
        // 去除首尾空格
        paramValue = paramValue.trim();
        log.info("Found parameter: {}, value: {}", paramName, paramValue);
        args.put(paramName, paramValue);
      }
      
      // 构建工具调用
      Map<String, Object> toolCall = new HashMap<>();
      toolCall.put("name", toolName);
      toolCall.put("arguments", args);
      toolCalls.add(toolCall);
      log.info("Added tool call: {}", toolCall);
    }
    
    return toolCalls;
  }

  /**
   * 流式获取最终答案
   * 
   * @param payload 请求体
   * @param emitter SSE发射器
   * @throws IOException IO异常
   */
  private void streamFinalAnswer(Map<String, Object> payload, SseEmitter emitter) throws IOException {
    Request request = buildRequest(payload, true);
    log.info("Final answer request URL: {}", request.url());
    log.info("Final answer request body: {}", objectMapper.writeValueAsString(payload));

    try (Response response = okHttpClient.newCall(request).execute()) {
      log.info("Final answer response status: {}", response.code());

      if (!response.isSuccessful() || response.body() == null) {
        String errorMsg = String.format("模型响应失败: %d", response.code());
        log.error(errorMsg);
        sendChunk(emitter, errorMsg, true);
        return;
      }

      // 读取流式响应
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        log.debug("Raw SSE line: {}", line);
        if (!line.startsWith("data:")) {
          continue;
        }
        String data = line.replace("data:", "").trim();
        if ("[DONE]".equals(data)) {
          log.info("Received [DONE] signal");
          break;
        }
        try {
          // 解析SSE数据
          JsonNode chunk = objectMapper.readTree(data);
          log.debug("Parsed SSE chunk: {}", chunk);

          // 检查是否有错误
          JsonNode error = chunk.path("error");
          if (!error.isMissingNode()) {
            String errorMsg = String.format("模型错误: %s", error.path("message").asText());
            log.error(errorMsg);
            sendChunk(emitter, errorMsg, true);
            return;
          }

          // 处理流式内容
          JsonNode choices = chunk.path("choices");
          if (choices.isArray() && choices.size() > 0) {
            JsonNode delta = choices.path(0).path("delta");
            JsonNode content = delta.path("content");
            if (!content.isMissingNode()) {
              // 发送内容更新
              log.debug("Sending delta: {}", content.asText());
              sendChunk(emitter, content.asText(), false);
            }
            // 检查是否完成
            JsonNode finishReason = choices.path(0).path("finish_reason");
            if (!finishReason.isMissingNode() && "stop".equals(finishReason.asText())) {
              log.info("Received finish signal");
              break;
            }
          }
        } catch (Exception e) {
          log.error("Error processing SSE chunk: {}", e.getMessage());
        }
      }
      // 发送结束标记
      log.info("Sending final chunk");
      sendChunk(emitter, "", true);
    } catch (Exception e) {
      log.error("Error in streamFinalAnswer: {}", e.getMessage(), e);
      sendChunk(emitter, "服务异常", true);
    }
  }

  /**
   * 执行工具调用
   * 
   * @param toolName 工具名称
   * @param args     工具参数
   * @return 工具执行结果
   */
  private String executeTool(String toolName, Map<String, Object> args) {
    log.info("执行工具调用: {}, 参数: {}", toolName, args);

    if ("search_web".equals(toolName)) {
      // 执行网络搜索
      String query = String.valueOf(args.getOrDefault("query", ""));
      log.info("调用search_web工具，搜索关键词: {}", query);
      String result = toolService.searchWeb(query);
      log.info("search_web工具执行结果: {}", result);
      return result;
    }

    if ("calc".equals(toolName)) {
      // 执行数学计算
      String expr = String.valueOf(args.getOrDefault("expr", ""));
      log.info("调用自定义calc工具，计算表达式: {}", expr);
      Object result = toolService.calc(expr);
      log.info("calc工具执行结果: {}", result);
      return String.valueOf(result);
    }

    log.warn("未知工具: {}", toolName);
    return "未知工具";
  }

  /**
   * 构建工具定义列表
   * 
   * @return 工具定义列表
   */
  private List<Map<String, Object>> buildTools() {
    List<Map<String, Object>> tools = new ArrayList<>();
    // 定义search_web工具
    tools.add(Map.of(
        "type", "function",
        "function", Map.of(
            "name", "search_web",
            "description", "使用搜索引擎获取实时信息",
            "parameters", Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "搜索关键词")),
                "required", List.of("query")))));
    // 定义calc工具
    tools.add(Map.of(
        "type", "function",
        "function", Map.of(
            "name", "calc",
            "description", "计算数学表达式",
            "parameters", Map.of(
                "type", "object",
                "properties", Map.of(
                    "expr", Map.of("type", "string", "description", "数学表达式")),
                "required", List.of("expr")))));
    return tools;
  }

  /**
   * 发送非流式POST请求
   * 
   * @param payload 请求体
   * @return AI模型响应
   * @throws IOException IO异常
   */
  private JsonNode postJson(Map<String, Object> payload) throws IOException {
    Map<String, Object> bodyMap = new HashMap<>(payload);
    bodyMap.put("stream", false); // 禁用流式响应

    // 针对DeepSeek API，调整参数设置
    if (baseUrl.contains("deepseek")) {
      // 移除可能导致问题的response_format参数
      bodyMap.remove("response_format");
      // 确保使用dsml=false避免DSML格式
      bodyMap.put("dsml", "false");
    } else {
      // 其他API也不强制使用json_object格式
      bodyMap.remove("response_format");
    }

    Request request = buildRequest(bodyMap, false);
    log.info("AI API Request URL: {}", request.url());
    log.info("AI API Request Body: {}", objectMapper.writeValueAsString(bodyMap));

    try (Response response = okHttpClient.newCall(request).execute()) {
      log.info("AI API Response Status: {}", response.code());
      log.info("AI API Response Headers: {}", response.headers());

      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "No body";
        log.error("AI API Response Error: {} - {}", response.code(), errorBody);
        throw new IOException("LLM response invalid: " + response.code() + " - " + errorBody);
      }

      if (response.body() == null) {
        log.error("AI API Response Body is null");
        throw new IOException("LLM response invalid: Body is null");
      }

      String responseBody = response.body().string();
      log.info("AI API Response Body: {}", responseBody);

      // 解析JSON响应
      return objectMapper.readTree(responseBody);
    }
  }

  /**
   * 构建HTTP请求
   * 
   * @param payload 请求体
   * @param stream  是否启用流式响应
   * @return HTTP请求实例
   * @throws IOException IO异常
   */
  private Request buildRequest(Map<String, Object> payload, boolean stream) throws IOException {
    // 根据baseUrl自动调整API路径，兼容不同AI服务
    String path = "/v1/chat/completions";
    if (baseUrl.contains("deepseek")) {
      path = "/chat/completions";
    }
    String url = baseUrl + path;
    String json = objectMapper.writeValueAsString(payload);
    RequestBody body = RequestBody.create(json, JSON);
    return new Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer " + apiKey) // 添加认证头
        .post(body)
        .build();
  }

  /**
   * 向客户端发送SSE数据块
   * 
   * @param emitter SSE发射器
   * @param delta   内容增量
   * @param finish  是否结束
   */
  private void sendChunk(SseEmitter emitter, String delta, boolean finish) {
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("delta", delta); // 内容增量
      payload.put("finish", finish); // 是否结束标记
      emitter.send(payload);
      if (finish) {
        emitter.complete(); // 结束SSE连接
      }
    } catch (IOException error) {
      log.error("Send chunk failed", error);
      emitter.completeWithError(error); // 发送错误并结束连接
    }
  }
}
