package com.sportcourt.reliability;

import cn.hutool.json.JSONUtil;
import com.sportcourt.dto.Result;
import com.sportcourt.service.IOperationAuditService;
import com.sportcourt.utils.UserHolder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AccessGuard implements HandlerInterceptor {

  @Resource
  private IOperationAuditService operationAuditService;

  private final RateLimiter limits;

  @Value("${sportcourt.catalog-admin-ids:}")
  private String admins;

  @Value("${sportcourt.monitor-token:}")
  private String monitorToken;

  public AccessGuard(RateLimiter limits) {
    this.limits = limits;
  }

  public boolean isAdmin() {
    return UserHolder.getUser() != null
        && Arrays.stream(admins.split(","))
            .map(String::trim)
            .anyMatch(id -> id.equals(UserHolder.getUser().getId().toString()));
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
      throws Exception {
    String path = req.getRequestURI();
    String method = req.getMethod();
    if (path.equals("/actuator/prometheus")
        && "GET".equals(method)
        && monitorToken != null
        && !monitorToken.isEmpty()) {
      String authorization = req.getHeader("Authorization");
      if (authorization != null
          && MessageDigest.isEqual(
              ("Bearer " + monitorToken).getBytes(StandardCharsets.UTF_8),
              authorization.getBytes(StandardCharsets.UTF_8))) {
        return true;
      }
    }
    boolean read = "GET".equals(method) || "HEAD".equals(method);
    boolean experiencePublic =
        (read
                && (path.equals("/experience/capabilities")
                    || path.equals("/experience/cache")
                    || path.equals("/experience/visits")))
            || ("POST".equals(method) && path.equals("/experience/visits"));
    if (path.startsWith("/experience/")
        && !limits.allow("experience-ip:" + req.getRemoteAddr(), 60, 60000)) {
      return reject(res, 429, "体验请求较多，请稍后重试");
    }
    boolean publicRead =
        read
            && (path.startsWith("/venue/")
                || path.startsWith("/venue-type/")
                || path.startsWith("/bookingPass/")
                || path.equals("/post/hot")
                || path.equals("/post/of/venue")
                || path.equals("/post/search")
                || path.equals("/actuator/health"));
    boolean login = path.equals("/user/code") || path.equals("/user/login");
    if (!publicRead && !experiencePublic && !login && UserHolder.getUser() == null) {
      return reject(res, 401, "请先登录");
    }
    if ((path.startsWith("/ops/")
            || path.equals("/actuator/prometheus")
            || (!read && (path.equals("/venue") || path.startsWith("/venue/"))))
        && !isAdmin()) {
      return reject(res, 403, "当前账号没有管理权限");
    }
    if (login && !limits.allow("auth-ip:" + req.getRemoteAddr(), 30, 60000)) {
      return reject(res, 429, "请求过于频繁");
    }
    if (path.startsWith("/bookingPass-order/goldenSlot/") && !read) {
      if (!limits.allow("claim-user:" + UserHolder.getUser().getId(), 10, 1000)
          || !limits.allow("claim-global", 1000, 1000)) {
        return reject(res, 429, "抢购繁忙，请稍后重试");
      }
    }
    if (read
        && path.startsWith("/bookingPass-order/")
        && !limits.allow("order-read:" + UserHolder.getUser().getId(), 30, 1000)) {
      return reject(res, 429, "查询过于频繁");
    }
    return true;
  }

  private boolean reject(HttpServletResponse res, int status, String text) throws Exception {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(JSONUtil.toJsonStr(Result.fail(text)));
    return false;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
    if (!(req.getRequestURI().startsWith("/ops/") || req.getRequestURI().equals("/venue"))
        || "GET".equals(req.getMethod())) {
      return;
    }
    // No request body, authentication token or personal text is stored in the audit.
    operationAuditService.recordAudit(
        UserHolder.getUser() == null ? null : UserHolder.getUser().getId(),
        req.getMethod(),
        req.getRequestURI(),
        res.getStatus());
  }
}
