package org.testar.evidence.playwright;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Copies literal arguments without serializing browser handles or arbitrary object internals. */
final class FacadeValues {
    private FacadeValues() {
    }

    static Object describe(Object value) {
        return describe(value, new IdentityHashMap<>(), 0);
    }

    private static Object describe(Object value, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue()) ? value : number.toString();
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof Path path) {
            return path.toString();
        }
        if (value instanceof Pattern pattern) {
            return Map.of("pattern", pattern.pattern(), "flags", pattern.flags());
        }
        if (value instanceof byte[] bytes) {
            return Map.of("byte_length", bytes.length);
        }
        if (depth > 8 || seen.put(value, true) != null) {
            return Map.of("description", "Nested or repeated argument");
        }
        try {
            if (value.getClass().isArray()) {
                var values = new ArrayList<>();
                for (int index = 0; index < Array.getLength(value); index++) {
                    values.add(describe(Array.get(value, index), seen, depth + 1));
                }
                return values;
            }
            if (value instanceof Iterable<?> values) {
                var result = new ArrayList<>();
                for (Object item : values) {
                    result.add(describe(item, seen, depth + 1));
                }
                return result;
            }
            if (value instanceof Map<?, ?> values) {
                Map<String, Object> result = new LinkedHashMap<>();
                values.forEach((key, item) -> {
                    if (key instanceof String name) {
                        result.put(name, describe(item, seen, depth + 1));
                    }
                });
                return result;
            }
            if (value.getClass().getName().startsWith("com.microsoft.playwright.")
                && !value.getClass().isInterface()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (Field field : value.getClass().getFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        Object item = field.get(value);
                        if (item != null) {
                            fields.put(field.getName(), describe(item, seen, depth + 1));
                        }
                    }
                }
                if (!fields.isEmpty()) {
                    return fields;
                }
            }
            return Map.of("type", value.getClass().getSimpleName());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot describe Playwright options", error);
        } finally {
            seen.remove(value);
        }
    }

    static boolean option(Object[] arguments, String fieldName) {
        for (Object argument : arguments) {
            if (argument != null) {
                try {
                    if (Boolean.TRUE.equals(argument.getClass().getField(fieldName).get(argument))) {
                        return true;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Most arguments are values rather than an options object.
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException(error);
                }
            }
        }
        return false;
    }
}
