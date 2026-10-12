package com.nytryx.burnai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.mybatis.spring.annotation.MapperScan;

@Configuration
@EnableConfigurationProperties({QwenProperties.class, VllmProperties.class})
@MapperScan("com.nytryx.burnai.mapper")
public class BurnAiConfiguration {
}
