package org.testar.evidence.playwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaptureOptionsTest {
    @Test
    void rejects_capture_limits_that_cannot_bound_the_output() {
        assertThrows(IllegalArgumentException.class, () -> options(0, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> options(5_001, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CaptureOptions(1, 0, 1, 1, List.of(), List.of(), false));
    }

    @Test
    void rejects_ambiguous_or_invalid_property_selection() {
        assertThrows(IllegalArgumentException.class, () -> options(1, List.of("color", "color"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> options(1, List.of("font size"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> options(1, List.of(), List.of("bad\nattribute")));
    }

    @Test
    void capture_selection_is_independent_of_later_caller_changes() {
        List<String> styles = new ArrayList<>(List.of("color"));
        CaptureOptions options = options(1, styles, List.of());
        styles.add("display");
        assertEquals(List.of("color"), options.computedStyles());
        assertThrows(UnsupportedOperationException.class, () -> options.computedStyles().add("display"));
    }

    private static CaptureOptions options(int widgets, List<String> styles, List<String> excluded) {
        return new CaptureOptions(widgets, 32, 8_192, 32, styles, excluded, false);
    }
}
