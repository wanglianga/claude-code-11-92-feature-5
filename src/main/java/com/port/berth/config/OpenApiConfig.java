package com.port.berth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI portOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("港口靠泊协同服务 API")
                        .version("1.0.0")
                        .description("船代申报 ETA、调度候选比较/暂存/确认、资源原子锁定、"
                                + "班组执行记录、潮汐与岸桥扰动重排、三顺序回放；"
                                + "滞期争议：双方回执 + 合同规则 → 可质证区段与金额、"
                                + "任意时刻暂计回放、迟到回执更正留痕、完工定稿。"
                                + "先调用 /api/auth/login 获取 token，再点 Authorize 输入。"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("UUID")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
