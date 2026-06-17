package org.example.noneplace.global.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("NonePlace Canvas API")
                .description("Reddit r/place 스타일 협업 픽셀 캔버스 API")
                .version("1.0.0")
        )
}
