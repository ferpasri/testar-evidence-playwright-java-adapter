package org.testar.evidence.playwright;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Reserve a fresh trace folder for either a facade or an explicit recorder. */
final class TraceDirectories {
    private TraceDirectories() {
    }

    static Path reserve(EvidenceOptions options) throws IOException {
        Files.createDirectories(options.outputDirectory());
        String objective = options.testObjective() == null ? "" : "_" + label(options.testObjective());
        String prefix = label(options.sut()) + objective + "_trace-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
        for (int collision = 0; ; collision++) {
            Path path = options.outputDirectory().resolve(prefix + (collision == 0 ? "" : "-" + collision));
            try {
                return Files.createDirectory(path);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Reserve the directory atomically so parallel sessions keep their own traces.
            }
        }
    }

    private static String label(String value) {
        String label = value.replaceAll("[^a-zA-Z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return label.isEmpty() ? "application" : label.substring(0, Math.min(40, label.length()));
    }

}
