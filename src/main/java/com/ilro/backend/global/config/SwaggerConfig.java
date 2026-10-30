package com.ilro.backend.global.config;

import com.ilro.backend.global.auth.AuthPrincipal;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Swagger에서 Authorize 버튼으로 Bearer 토큰을 넣어 호출할 수 있게 함
@Configuration
public class SwaggerConfig {

    private static final String BEARER = "bearer";

    static {
        // 컨트롤러의 AuthPrincipal 파라미터는 토큰에서 채우므로 문서의 입력 칸에서 뺌
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthPrincipal.class);
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("일로 API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme(BEARER)))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
