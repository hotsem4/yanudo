package com.habit66.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * SQLite 는 DB 파일의 상위 폴더를 만들지 않으므로 DataSource 가 만들어지기 전에 data-dir 과 audio/ 를 만든다.
 * application.yml 이 읽힌 뒤에 돌도록 가장 늦은 순서로 둔다.
 */
public class DataDirEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dir = environment.resolvePlaceholders("${app.data-dir:./data}");
        Path dataDir = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataDir.resolve("audio"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory " + dataDir, e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
