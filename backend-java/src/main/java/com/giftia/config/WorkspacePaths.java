package com.giftia.config;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspacePaths {
    private WorkspacePaths() {}

    public static Path root() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("frontend")) && Files.isDirectory(candidate.resolve("backend"))) return candidate;
        }
        return current;
    }
}
