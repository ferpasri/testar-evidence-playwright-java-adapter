package org.testar.evidence.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Touchscreen;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Identifies browser interactions, separately from reads, waits, and script evaluation. */
final class FacadeActions {
    private static final Set<String> ELEMENT = Set.of("blur", "check", "clear", "click", "dblclick",
        "dispatchEvent", "dragTo", "drop", "fill", "focus", "hover", "press", "pressSequentially",
        "scrollIntoViewIfNeeded", "selectOption", "selectText", "setChecked", "setInputFiles", "tap",
        "type", "uncheck");
    private static final Set<String> PAGE = Set.of("navigate", "goBack", "goForward", "reload",
        "setContent", "setViewportSize", "emulateMedia", "dragAndDrop");
    private static final Set<String> TEXT = Set.of("fill", "type", "pressSequentially", "insertText",
        "press", "down", "up", "dispatchEvent", "drop", "setInputFiles");

    private FacadeActions() {
    }

    static boolean supported(Object subject, String method, Object[] arguments) {
        if (FacadeValues.option(arguments, "trial")) {
            return false;
        }
        if (subject instanceof Locator || subject instanceof ElementHandle) {
            return ELEMENT.contains(method);
        }
        if (subject instanceof Page) {
            return ELEMENT.contains(method) || PAGE.contains(method);
        }
        if (subject instanceof Frame) {
            return ELEMENT.contains(method) || Set.of("navigate", "setContent", "dragAndDrop").contains(method);
        }
        if (subject instanceof Keyboard) {
            return Set.of("down", "insertText", "press", "type", "up").contains(method);
        }
        if (subject instanceof Mouse) {
            return Set.of("click", "dblclick", "down", "move", "up", "wheel").contains(method);
        }
        return subject instanceof Touchscreen && method.equals("tap");
    }

    static Locator target(Object subject, Page page, String method, Object[] arguments) {
        if (subject instanceof Locator locator) {
            return locator;
        }
        if (subject instanceof Keyboard) {
            return page.locator(":focus");
        }
        if (ELEMENT.contains(method) || method.equals("dragAndDrop")) {
            if (arguments.length > 0 && arguments[0] instanceof String selector) {
                if (subject instanceof Page source) {
                    return source.locator(selector);
                }
                if (subject instanceof Frame frame) {
                    return frame.locator(selector);
                }
            }
        }
        return null;
    }

    static boolean sensitive(Locator locator) {
        if (locator == null) {
            return true;
        }
        try {
            if (locator.count() != 1) {
                return true;
            }
            return Boolean.TRUE.equals(locator.evaluate("e => e.matches('input[type=password]')", null,
                new Locator.EvaluateOptions().setTimeout(250)));
        } catch (RuntimeException error) {
            return true;
        }
    }

    static Map<String, Object> parameters(Object subject, String method, Object[] arguments, boolean sensitive) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("playwright.method", method);
        if (sensitive && TEXT.contains(method) && !(subject instanceof Mouse)) {
            result.put("playwright.redacted_parameters", List.of("arguments"));
        } else {
            result.put("playwright.arguments", FacadeValues.describe(arguments));
            int offset = subject instanceof Page || subject instanceof Frame ? 1 : 0;
            if (Set.of("fill", "type", "pressSequentially", "insertText").contains(method)
                && arguments.length > offset && arguments[offset] instanceof String text) {
                result.put("value", text);
            }
        }
        return result;
    }
}
