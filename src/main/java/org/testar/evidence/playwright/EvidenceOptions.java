package org.testar.evidence.playwright;

import java.nio.file.Path;
import java.util.Objects;

/** Optional trace location, capture settings, and descriptions shared by this Playwright session. */
public record EvidenceOptions(Path outputDirectory, String sut, String origin, String testObjective,
                              CaptureOptions capture) {
    public EvidenceOptions {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(capture, "capture");
        if (sut == null || sut.isBlank()) {
            throw new IllegalArgumentException("SUT label must be nonblank");
        }
        if (origin != null && origin.isBlank() || testObjective != null && testObjective.isBlank()) {
            throw new IllegalArgumentException("Supplied run descriptions must be nonblank");
        }
    }

    public static EvidenceOptions defaults() {
        return new EvidenceOptions(Path.of("target", "traces"), "application", null, null,
            CaptureOptions.defaults());
    }
}
