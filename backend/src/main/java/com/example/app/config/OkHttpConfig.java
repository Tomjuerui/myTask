package com.example.app.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OkHttpConfig {
  @Value("${ai.connect-timeout:15}")
  private int connectTimeout;

  @Value("${ai.read-timeout:60}")
  private int readTimeout;

  @Bean
  public OkHttpClient okHttpClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(connectTimeout, TimeUnit.SECONDS)
        .readTimeout(readTimeout, TimeUnit.SECONDS)
        .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build();
  }
}
