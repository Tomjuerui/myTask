package com.example.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ToolService {
  private static final Logger log = LoggerFactory.getLogger(ToolService.class);

  private final OkHttpClient okHttpClient;
  private final ObjectMapper objectMapper;

  @Value("${search.endpoint:https://api.bing.microsoft.com/v7.0/search}")
  private String searchEndpoint;

  @Value("${search.api-key:}")
  private String searchApiKey;

  public ToolService(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
    this.okHttpClient = okHttpClient;
    this.objectMapper = objectMapper;
  }

  public String searchWeb(String query) {
    try {
      String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
      String url = String.format("%s?q=%s&count=3", searchEndpoint, encoded);
      Request request = new Request.Builder()
          .url(url)
          .addHeader("Ocp-Apim-Subscription-Key", searchApiKey)
          .build();

      log.info("Calling search API", query);
      try (Response response = okHttpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          return "搜索服务暂不可用";
        }

        JsonNode root = objectMapper.readTree(response.body().string());
        JsonNode items = root.path("webPages").path("value");
        List<String> summaries = new ArrayList<>();
        if (items.isArray()) {
          for (JsonNode item : items) {
            String name = item.path("name").asText();
            String snippet = item.path("snippet").asText();
            String link = item.path("url").asText();
            summaries.add(String.format("%s - %s (%s)", name, snippet, link));
          }
        }

        if (summaries.isEmpty()) {
          return "未检索到结果";
        }

        return String.join("\n", summaries);
      }
    } catch (IOException error) {
      log.error("Search API failed", error);
      return "搜索服务异常";
    }
  }

  public double calc(String expr) {
    try {
      Expression expression = new ExpressionBuilder(expr).build();
      return expression.evaluate();
    } catch (Exception error) {
      log.error("Calc failed", error);
      return Double.NaN;
    }
  }
}
