package com.port.berth.config;

import com.port.berth.domain.Cat;
import com.port.berth.domain.Role;
import com.port.berth.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/**控制器取当前用户与角色校验的小工具*/
public final class WebAuth {

    private WebAuth() {}

    public static Cat.AppUser user(HttpServletRequest req) {
        Cat.AppUser u = (Cat.AppUser) req.getAttribute(AuthInterceptor.CURRENT_USER);
        if (u == null) throw new ApiException(401, "UNAUTHORIZED", "未登录");
        return u;
    }

    public static void requireRole(HttpServletRequest req, Role... roles) {
        Cat.AppUser u = user(req);
        for (Role r : roles) {
            if (r.name().equals(u.role())) return;
        }
        // ADMIN 拥有全部权限
        if (Role.ADMIN.name().equals(u.role())) return;
        throw new ApiException(403, "FORBIDDEN",
                "当前角色 " + u.role() + " 无权执行该操作，需要角色："
                        + java.util.Arrays.toString(roles));
    }
}
