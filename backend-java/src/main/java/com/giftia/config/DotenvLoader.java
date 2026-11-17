package com.giftia.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads existing credentials without printing them or changing process environment. */
public final class DotenvLoader {
    private DotenvLoader() {}

    public static Map<String, Object> read(Path file) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return values;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip().replaceFirst("^\\uFEFF", "");
                if (line.startsWith("export ")) line = line.substring(7).stripLeading();
                int separator = line.indexOf('=');
                if (separator < 1 || line.startsWith("#")) continue;
                String name = line.substring(0, separator).strip();
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                String value = line.substring(separator + 1).strip();
                if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
                    char quote = value.charAt(0);
                    int end = value.lastIndexOf(quote);
                    if (end > 0) value = value.substring(1, end);
                } else {
                    int comment = value.indexOf(" #");
                    if (comment >= 0) value = value.substring(0, comment).stripTrailing();
                }
                values.put(name, value);
            }
            return values;
        } catch (IOException error) {
            throw new IllegalStateException("无法读取项目环境配置文件", error);
        }
    }
}
