package org.example.noneplace.global.redis.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import redis.embedded.RedisServer
import java.net.ServerSocket

@TestConfiguration
open class TestRedisConfig {

    companion object {
        private val REDIS_PORT: Int = ServerSocket(0).use { it.localPort }

        private val redisServer: RedisServer by lazy {
            RedisServer(REDIS_PORT).also { it.start() }
        }
    }

    @PostConstruct
    fun ensureRedisStarted() {
        redisServer // lazy 초기화 보장 (이미 실행 중이면 no-op)
    }

    @Bean
    @Primary
    fun reactiveRedisConnectionFactory(): ReactiveRedisConnectionFactory {
        return LettuceConnectionFactory("127.0.0.1", REDIS_PORT).apply {
            afterPropertiesSet()
        }
    }
}
