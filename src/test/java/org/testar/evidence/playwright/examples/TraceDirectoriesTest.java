package org.testar.evidence.playwright.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TraceDirectoriesTest {
    @TempDir
    Path temporary;

    @Test
    public void namesIncludeLabelsAndTimestampWithoutReplacingExistingEvidence() throws Exception {
        Path parent = temporary;
        LocalDateTime time = LocalDateTime.of(2026, 10, 22, 15, 30, 12, 123000000);
        Path first = TraceDirectories.create(parent, "parabank", "denied-loan", time);
        Files.writeString(first.resolve("keep.txt"), "existing evidence");
        Path second = TraceDirectories.create(parent, "parabank", "denied-loan", time);
        assertEquals("parabank_denied-loan_trace-2026-10-22_15-30-12-123", first.getFileName().toString());
        assertEquals(first.getFileName() + "-2", second.getFileName().toString());
        assertEquals("existing evidence", Files.readString(first.resolve("keep.txt")));
        assertFalse(Files.exists(second.resolve("keep.txt")));
    }

    @Test
    public void anExplicitOutputPathRemainsTheExactRequestedDirectory() throws Exception {
        Path supplied = temporary.resolve("chosen-directory");
        assertEquals(supplied, ParabankDeniedLoan.outputDirectory(new String[] {"https://example.invalid", supplied.toString()}));
        assertFalse(Files.exists(supplied));
    }

    @Test
    public void unsafeAndLongLabelsStayWithinTheSelectedParent() throws Exception {
        Path parent = temporary;
        Path path = TraceDirectories.create(parent, "../../App: one?", "C:\\work\\<test>");
        assertEquals(parent, path.getParent());
        assertFalse(path.getFileName().toString().matches(".*[<>:\\\"/\\\\|?*].*"));
        Path fallback = TraceDirectories.create(parent, "...", "???");
        assertTrue(fallback.getFileName().toString().startsWith("sut_objective"));
        Path bounded = TraceDirectories.create(parent, "a".repeat(1000), "b".repeat(1000));
        assertTrue(bounded.getFileName().toString().length() < 140);
    }
}
