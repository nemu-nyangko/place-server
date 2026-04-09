package org.example.noneplace.global.redis.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.RedisSerializer

@Configuration
open class RedisConfig {

    @Bean
    open fun reactiveRedisTemplate(factory: ReactiveRedisConnectionFactory): ReactiveRedisTemplate<String, ByteArray> {
        val serializer = RedisSerializer.byteArray()
        val context = RedisSerializationContext
            .newSerializationContext<String, ByteArray>(RedisSerializer.string())
            .value(serializer)
            .build()
        return ReactiveRedisTemplate(factory, context)
    }

    @Bean
    open fun reactiveRedisMessageListenerContainer(factory: ReactiveRedisConnectionFactory): ReactiveRedisMessageListenerContainer {
        return ReactiveRedisMessageListenerContainer(factory)
    }
}