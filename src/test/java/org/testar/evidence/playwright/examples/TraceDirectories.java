package org.testar.evidence.playwright.examples;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Reserves a readable directory without replacing an earlier trace. */
final class TraceDirectories {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private TraceDirectories() {
    }

    public static Path create(Path parent, String sut, String objective) throws IOException {
        return create(parent, sut, objective, LocalDateTime.now());
    }

    static Path create(Path parent, String sut, String objective, LocalDateTime time) throws IOException {
        Files.createDirectories(parent);
        String name = label(sut, "sut") + "_" + label(objective, "objective")
            + "_trace-" + TIMESTAMP.format(time);
        long suffix = 1;
        while (true) {
            Path directory = parent.resolve(name + (suffix == 1 ? "" : "-" + suffix));
            try {
                return Files.createDirectory(directory);
            } catch (FileAlreadyExistsException collision) {
                suffix++;
            }
        }
    }

    private static String label(String supplied, String fallback) {
        String cleaned = (supplied == null ? "" : supplied).replaceAll("[^A-Za-z0-9_-]+", "-")
            .replaceAll("^[-_]+|[-_]+$", "");
        cleaned = cleaned.substring(0, Math.min(32, cleaned.length())).replaceAll("[-_]+$", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
