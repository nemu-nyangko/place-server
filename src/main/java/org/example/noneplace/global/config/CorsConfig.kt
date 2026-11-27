package org.example.noneplace.global.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.CorsRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer

@ConfigurationProperties(prefix = "cors")
@Configuration
class CorsConfig(
    var allowedOrigins: List<String> = emptyList()
) : WebFluxConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*allowedOrigins.toTypedArray())
            // PUT·DELETE: 관리자 지정/해제 (/api/admin/admins/{id})
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("Content-Type", "Authorization")
            .maxAge(3600)
    }
}
