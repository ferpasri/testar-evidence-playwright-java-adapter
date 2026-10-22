package org.testar.statemodel.playwright;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record CaptureOptions(int maxWidgets, int maxValueCharacters, int maxTotalCharacters,
                             int maxAttributesPerWidget, List<String> computedStyles,
                             List<String> excludedAttributes, boolean screenshots) {
    public CaptureOptions {
        requireBound(maxWidgets, 5_000, "maxWidgets");
        requireBound(maxValueCharacters, 65_536, "maxValueCharacters");
        requireBound(maxTotalCharacters, 8_388_608, "maxTotalCharacters");
        requireBound(maxAttributesPerWidget, 1_024, "maxAttributesPerWidget");
        computedStyles = names(computedStyles, 64, "computedStyles");
        excludedAttributes = names(excludedAttributes, 256, "excludedAttributes");
    }

    public static CaptureOptions defaults() {
        return new CaptureOptions(2_000, 8_192, 4_194_304, 256,
            List.of("display", "visibility", "font-size", "font-family", "color", "background-color"),
            List.of(), true);
    }

    private static void requireBound(int value, int maximum, String name) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
    }

    private static List<String> names(List<String> values, int maximum, String name) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() > maximum || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " has too many or repeated entries");
        }
        for (String value : copy) {
            if (value.isBlank() || value.length() > 256
                || value.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
                throw new IllegalArgumentException(name + " entries must be names of at most 256 characters");
            }
        }
        return copy;
    }
}
