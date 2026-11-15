package com.habit66.config;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 파일 이름이 내용(sha1)에서 나오므로 한 번 받은 클립은 바뀌지 않는다.
        String audioLocation = props.audioPath().toUri().toString();
        if (!audioLocation.endsWith("/")) {
            audioLocation += "/";
        }
        registry.addResourceHandler("/api/v1/audio/**")
                .addResourceLocations(audioLocation)
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());

        // Vite 산출물은 파일 이름에 해시가 붙는다.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }
}
