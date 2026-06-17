package org.example.noneplace;

import org.example.noneplace.global.redis.config.TestRedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestRedisConfig.class)
class NonePlaceApplicationTests {

    @Test
    void contextLoads() {
    }

}
