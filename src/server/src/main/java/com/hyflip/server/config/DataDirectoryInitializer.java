package com.hyflip.server.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Creates the data directory before any bean (including the data source) is created, because
 * SQLite does not create missing parent directories for its database file.
 */
@Component
public class DataDirectoryInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String dataDir = environment.getProperty("hyflip.data-dir", "./data");
        try {
            Files.createDirectories(Path.of(dataDir));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory " + dataDir, e);
        }
    }
}
