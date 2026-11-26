package com.port.berth.web;

import com.port.berth.config.WebAuth;
import com.port.berth.domain.Cat;
import com.port.berth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null) {
            throw ApiException.badRequest("BAD_REQUEST", "请输入用户名和密码");
        }
        return authService.login(req.username().trim(), req.password());
    }

    @GetMapping("/me")
    public Cat.AppUser me(HttpServletRequest request) {
        return WebAuth.user(request);
    }
}
