package com.port.berth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.port.berth.domain.Cat;
import com.port.berth.repo.CatalogDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**除 /api/auth/login 外，所有 /api/** 都需要 Bearer Token*/
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER = "currentUser";

    private final CatalogDao catalog;
    private final ObjectMapper json;

    public AuthInterceptor(CatalogDao catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        }
        Cat.AppUser user = token == null ? null
                : catalog.findUserByToken(token).orElse(null);
        if (user == null) {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(json.writeValueAsString(Map.of(
                    "code", "UNAUTHORIZED",
                    "message", "请先登录并在 Authorization 头携带 Bearer Token")));
            return false;
        }
        request.setAttribute(CURRENT_USER, user);
        return true;
    }
}
