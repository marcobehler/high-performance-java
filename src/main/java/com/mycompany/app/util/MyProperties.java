package com.mycompany.app.util;

import com.mycompany.app.PerformanceTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public enum MyProperties {

    INSTANCE;

    private Properties properties = new Properties();

    MyProperties() {
        final Path propsFile = Paths.get("./application.properties");
        System.out.println("Curren Directory = " + Paths.get(".").normalize().toAbsolutePath().toString());

        if (Files.exists(propsFile)) {
            try (InputStream is = Files.newInputStream(propsFile)) {
                properties.load(is);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try (InputStream is = PerformanceTest.class.getResourceAsStream("/application.properties")) {
                properties.load(is);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public Properties getProperties() {
        return properties;
    }

    public String getProperty(String key) {
        return (String) properties.get(key);
    }
}