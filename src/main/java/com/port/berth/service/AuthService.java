package com.port.berth.service;

import com.port.berth.domain.Cat;
import com.port.berth.repo.CatalogDao;
import com.port.berth.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**极轻量账号/Token 登录（演示用，测试账号由 SeedService 播种）*/
@Service
public class AuthService {

    private final CatalogDao catalog;

    public AuthService(CatalogDao catalog) {
        this.catalog = catalog;
    }

    public Map<String, Object> login(String username, String password) {
        CatalogDao.UserRow u = catalog.findUserByUsername(username)
                .orElseThrow(() -> ApiException.badRequest("BAD_CREDENTIALS", "用户名或密码错误"));
        if (!u.password().equals(password)) {
            throw ApiException.badRequest("BAD_CREDENTIALS", "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        catalog.insertToken(token, u.id());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("user", new Cat.AppUser(u.id(), u.username(), u.displayName(), u.role()));
        return result;
    }
}
