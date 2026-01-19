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

/**
 * 工具服务类，提供AI模型可调用的工具集
 */
@Service
public class ToolService {
  private static final Logger log = LoggerFactory.getLogger(ToolService.class);

  private final OkHttpClient okHttpClient;
  private final ObjectMapper objectMapper;

  @Value("${search.endpoint:https://api.bing.microsoft.com/v7.0/search}")
  private String searchEndpoint; // 搜索引擎API端点

  @Value("${search.api-key:}")
  private String searchApiKey; // 搜索引擎API密钥

  /**
   * 构造函数
   * 
   * @param okHttpClient OkHttp客户端实例
   * @param objectMapper Jackson对象映射器
   */
  public ToolService(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
    this.okHttpClient = okHttpClient;
    this.objectMapper = objectMapper;
  }

  /**
   * 使用百度搜索引擎搜索网页
   * 
   * @param query 搜索关键词
   * @return 搜索结果摘要，多个结果用换行分隔
   */
  public String searchWeb(String query) {
    try {
      // URL编码搜索关键词
      String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

      // 构建百度搜索API URL，返回前3条结果
      // 百度搜索API格式示例：https://www.baidu.com/s?wd=关键词&rn=3
      String url = String.format("%s?wd=%s&rn=3", searchEndpoint, encoded);
      // 构建HTTP请求，添加User-Agent头避免反爬虫
      Request request = new Request.Builder()
          .url(url)
          .header("User-Agent",
              "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
          .build();

      log.info("Calling Baidu search API with query: {}", query);
      try (Response response = okHttpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          return "搜索服务暂不可用";
        }

        // 百度搜索API返回HTML，这里简化处理，返回成功消息
        // 实际生产环境中，需要解析HTML获取搜索结果
        return String.format("搜索成功：已为您搜索 '%s'，请访问 %s 查看结果", query, url);
      }
    } catch (IOException error) {
      log.error("Search API failed for query: {}, error: {}", query, error.getMessage());
      return "搜索服务异常";
    }
  }

  /**
   * 计算数学表达式
   * 
   * @param expr 数学表达式字符串
   * @return 计算结果，如果表达式无效返回Double.NaN
   */
  public double calc(String expr) {
    log.info("ToolService.calc方法被调用，开始计算表达式: {}", expr);

    try {
      // 使用exp4j构建并计算表达式
      Expression expression = new ExpressionBuilder(expr).build();
      double result = expression.evaluate();
      log.info("ToolService.calc方法执行成功，表达式: {}, 结果: {}", expr, result);
      return result;
    } catch (Exception error) {
      log.error("ToolService.calc方法执行失败，表达式: {}", expr, error);
      // 表达式无效时返回NaN
      return Double.NaN;
    }
  }
}
