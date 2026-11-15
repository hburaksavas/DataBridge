package com.databridge;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class ApiSecurity implements Filter {
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var req = (HttpServletRequest) request;
    var res = (HttpServletResponse) response;
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'"
            + " data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
    String host = req.getServerName();
    if (!host.equals("127.0.0.1") && !host.equals("localhost")) {
      res.sendError(403);
      return;
    }
    if (req.getRequestURI().startsWith("/api/")) {
      String auth = req.getHeader("Authorization"),
          expected = "Bearer " + System.getProperty("databridge.token", "");
      if (auth == null
          || !MessageDigest.isEqual(
              auth.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
        res.sendError(401);
        return;
      }
      String origin = req.getHeader("Origin");
      if (origin != null
          && !origin.equals("http://127.0.0.1:" + req.getLocalPort())
          && !origin.equals("http://localhost:" + req.getLocalPort())) {
        res.sendError(403);
        return;
      }
      res.setHeader("Cache-Control", "no-store");
    }
    chain.doFilter(request, response);
  }
}
