package com.multiagent.discuss.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class DefaultConfigurationTest {
    @Test
    void defaultModelConfigurationIsUnderSpringAi() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertNotNull(properties.getProperty("spring.ai.openai.api-key"));
        assertNotNull(properties.getProperty("spring.ai.openai.chat.options.model"));
        assertNull(properties.getProperty("jasypt.ai.openai.api-key"));
    }
}
