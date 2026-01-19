package com.example.app.config;

// import io.github.bucket4j.Bandwidth;
// import io.github.bucket4j.Bucket;
// import io.github.bucket4j.Refill;
// import jakarta.servlet.FilterChain;
// import jakarta.servlet.ServletException;
// import jakarta.servlet.http.HttpServletRequest;
// import jakarta.servlet.http.HttpServletResponse;
// import java.io.IOException;
// import java.time.Duration;
// import java.util.Map;
// import java.util.concurrent.ConcurrentHashMap;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.stereotype.Component;
// import org.springframework.web.filter.OncePerRequestFilter;

// @Component
// public class RateLimitFilter extends OncePerRequestFilter {
//   private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
//   private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

//   @Override
//   protected void doFilterInternal(
//       HttpServletRequest request,
//       HttpServletResponse response,
//       FilterChain filterChain) throws ServletException, IOException {
//     try {
//       String ip = request.getRemoteAddr();
//       Bucket bucket = buckets.computeIfAbsent(ip, this::newBucket);

//       if (bucket.tryConsume(1)) {
//         filterChain.doFilter(request, response);
//         return;
//       }

//       response.setStatus(429);
//       response.setContentType("application/json");
//       response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁\"}");
//     } catch (Exception error) {
//       log.error("Rate limit filter failed", error);
//       response.setStatus(500);
//     }
//   }

//   private Bucket newBucket(String key) {
//     Refill refill = Refill.intervally(60, Duration.ofMinutes(1));
//     Bandwidth limit = Bandwidth.classic(60, refill);
//     return Bucket.builder().addLimit(limit).build();
//   }
// }
