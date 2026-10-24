package org.testar.evidence.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testar.evidence.ObservedValue;
import org.testar.evidence.Oracle;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.ExpectedValue;
import org.testar.evidence.Properties;

/** Runs native retrying assertions once and records their final outcome and a subsequent reading. */
final class FacadeAssertions implements InvocationHandler {
    private final Object delegate;
    private final FacadeRuntime runtime;
    private final Page page;
    private final Locator locator;
    private final boolean negated;

    private FacadeAssertions(Object delegate, FacadeRuntime runtime, Page page, Locator locator, boolean negated) {
        this.delegate = delegate;
        this.runtime = runtime;
        this.page = page;
        this.locator = locator;
        this.negated = negated;
    }

    static <T> T wrap(T assertions, Object source, Page page, Locator locator) {
        FacadeRuntime runtime = FacadeRuntime.runtimeOf(source);
        return runtime == null ? assertions : proxy(assertions, runtime, page, locator, false);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T delegate, FacadeRuntime runtime, Page page, Locator locator, boolean negated) {
        return (T) Proxy.newProxyInstance(EvidenceAssertions.class.getClassLoader(), delegate.getClass().getInterfaces(),
            new FacadeAssertions(delegate, runtime, page, locator, negated));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] supplied) throws Throwable {
        Object[] arguments = supplied == null ? new Object[0] : supplied;
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> delegate.toString();
            };
        }
        if (method.getName().equals("not")) {
            return proxy(FacadeRuntime.invokeNative(delegate, method, arguments), runtime, page, locator, !negated);
        }
        if (!runtime.recording()) {
            return FacadeRuntime.invokeNative(delegate, method, arguments);
        }
        boolean collection = collection(method.getName(), arguments);
        boolean sensitive = sensitive(method.getName(), arguments, collection);
        String property = property(method.getName(), arguments, collection);
        Map<String, Object> descriptions = new LinkedHashMap<>();
        descriptions.put("comparison", "playwright." + method.getName());
        descriptions.put("negated", negated);
        if (!sensitive) {
            descriptions.put("arguments", FacadeValues.describe(arguments));
        } else {
            descriptions.put("redacted", true);
        }
        ExpectedValue expected = sensitive || arguments.length == 0 ? ExpectedValue.unspecified()
            : ExpectedValue.of(FacadeValues.describe(expected(method.getName(), arguments)));
        try {
            runtime.evidence(page).checks().evaluateAutomatic("Playwright " + (negated ? "not " : "") + method.getName(),
                new Oracle("playwright:" + method.getName() + (negated ? ":not" : ""), "1", Properties.of(descriptions)),
                collection ? null : locator, property, expected, () -> {
                    try {
                        FacadeRuntime.invokeNative(delegate, method, arguments);
                    } catch (RuntimeException | Error error) {
                        throw error;
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                }, () -> sensitive ? new ObservedValue.Unknown() : read(method.getName(), arguments), sensitive);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return null;
    }

    private boolean sensitive(String method, Object[] arguments, boolean collection) {
        if (locator == null || collection) {
            return false;
        }
        boolean valueCheck = method.equals("hasValue") || method.equals("hasValues")
            || method.equals("hasJSProperty") || method.equals("hasAttribute")
                && arguments.length > 0 && "value".equals(arguments[0]);
        if (valueCheck) {
            return FacadeActions.sensitive(locator);
        }
        try {
            return locator.count() == 1 && FacadeActions.sensitive(locator);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static Object expected(String method, Object[] arguments) {
        if ((method.equals("hasAttribute") || method.equals("hasCSS") || method.equals("hasJSProperty"))
            && arguments.length > 1) {
            return arguments[1];
        }
        if (method.startsWith("is")) {
            return true;
        }
        return arguments[0];
    }

    private static boolean collection(String method, Object[] arguments) {
        return method.equals("hasCount") || java.util.Set.of("hasText", "containsText", "hasClass", "containsClass")
            .contains(method) && arguments.length > 0 && arguments[0] != null
            && (arguments[0].getClass().isArray() || arguments[0] instanceof java.util.List<?>);
    }

    private String property(String method, Object[] arguments, boolean collection) {
        if (collection) {
            return null;
        }
        return switch (method) {
            case "hasTitle" -> "ui.title";
            case "hasURL" -> "ui.url";
            case "hasText", "containsText" -> FacadeValues.option(arguments, "useInnerText")
                ? "playwright.dom.innerText" : "playwright.dom.textContent";
            case "hasValue" -> "playwright.dom.value";
            case "hasValues" -> "playwright.dom.selectedValues";
            case "hasAttribute" -> "playwright.attribute." + arguments[0];
            case "hasCSS" -> "playwright.style." + arguments[0];
            case "hasJSProperty" -> "playwright.dom." + arguments[0];
            case "hasId" -> "playwright.attribute.id";
            case "hasClass", "containsClass" -> "playwright.attribute.class";
            case "isChecked" -> "playwright.dom.checked";
            case "isDisabled", "isEnabled" -> "ui.enabled";
            case "isHidden", "isVisible" -> "playwright.dom.visible";
            case "isFocused" -> "playwright.dom.focused";
            case "isAttached" -> "playwright.dom.attached";
            case "isEditable" -> "playwright.dom.editable";
            default -> null;
        };
    }

    private ObservedValue read(String method, Object[] arguments) {
        if (locator == null) {
            return switch (method) {
                case "hasTitle" -> value(page.title());
                case "hasURL" -> value(page.url());
                default -> new ObservedValue.Unknown();
            };
        }
        if (method.equals("hasCount")) {
            return value(locator.count());
        }
        if (collection(method, arguments) && (method.equals("hasText") || method.equals("containsText"))) {
            return value(FacadeValues.option(arguments, "useInnerText") ? locator.allInnerTexts() : locator.allTextContents());
        }
        if (method.equals("isAttached")) {
            return value(locator.count() > 0);
        }
        if (locator.count() != 1) {
            return new ObservedValue.Unknown();
        }
        return switch (method) {
            case "hasText", "containsText" -> value(locator.evaluate(
                FacadeValues.option(arguments, "useInnerText") ? "e => e.innerText" : "e => e.textContent",
                null, new Locator.EvaluateOptions().setTimeout(250)));
            case "hasValue" -> value(locator.inputValue(new Locator.InputValueOptions().setTimeout(250)));
            case "hasValues" -> value(locator.evaluate("e => Array.from(e.selectedOptions, o => o.value)", null,
                new Locator.EvaluateOptions().setTimeout(250)));
            case "hasAttribute" -> attribute((String) arguments[0]);
            case "hasCSS" -> value(locator.evaluate("(e, name) => getComputedStyle(e).getPropertyValue(name)",
                arguments[0], new Locator.EvaluateOptions().setTimeout(250)));
            case "hasJSProperty" -> value(locator.evaluate("(e, name) => e[name]", arguments[0],
                new Locator.EvaluateOptions().setTimeout(250)));
            case "hasId" -> attribute("id");
            case "hasClass", "containsClass" -> attribute("class");
            case "isChecked" -> value(locator.isChecked(new Locator.IsCheckedOptions().setTimeout(250)));
            case "isDisabled", "isEnabled" -> value(locator.isEnabled(new Locator.IsEnabledOptions().setTimeout(250)));
            case "isVisible", "isHidden" -> value(locator.isVisible());
            case "isEditable" -> value(locator.isEditable(new Locator.IsEditableOptions().setTimeout(250)));
            case "isFocused" -> value(locator.evaluate("e => e === e.ownerDocument.activeElement", null,
                new Locator.EvaluateOptions().setTimeout(250)));
            default -> new ObservedValue.Unknown();
        };
    }

    private ObservedValue attribute(String name) {
        String actual = locator.getAttribute(name, new Locator.GetAttributeOptions().setTimeout(250));
        return actual == null ? new ObservedValue.Absent() : value(actual);
    }

    private static ObservedValue value(Object value) {
        return new ObservedValue.Value(EvidenceJson.tree(value));
    }
}
