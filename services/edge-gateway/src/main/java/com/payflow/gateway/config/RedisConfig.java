package com.payflow.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

@Configuration
@ConditionalOnProperty(prefix = "payflow.gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfig {

    @Bean
    DefaultRedisScript<List<Long>> tokenBucketScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setResultType((Class<List<Long>>) (Class<?>) List.class);
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/token_bucket.lua")));
        return script;
    }
}
