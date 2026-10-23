package org.testar.evidence.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Owns object wrappers and one independent recorder for each page used through the facade. */
final class FacadeRuntime {
    private final EvidenceOptions options;
    private final Map<Object, Object> wrappers = new IdentityHashMap<>();
    private final Map<Object, Object> callbacks = new IdentityHashMap<>();
    private final Map<Page, PlaywrightEvidence> recorders = new LinkedHashMap<>();
    private final Set<Page> ended = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Thread owner = Thread.currentThread();
    private int depth;

    FacadeRuntime(EvidenceOptions options) {
        this.options = options;
    }

    @SuppressWarnings("unchecked")
    <T> T wrap(T value, Page associatedPage) {
        if (value == null || Proxy.isProxyClass(value.getClass())) {
            return value;
        }
        if (value instanceof List<?> values) {
            return (T) values.stream().map(item -> wrap(item, associatedPage)).toList();
        }
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> contract : type.getInterfaces()) {
                if (Modifier.isPublic(contract.getModifiers())
                    && contract.getName().startsWith("com.microsoft.playwright.")
                    && !contract.getName().startsWith("com.microsoft.playwright.impl.")) {
                    interfaces.add(contract);
                }
            }
        }
        if (interfaces.isEmpty()) {
            return value;
        }
        Object existing = wrappers.get(value);
        if (existing != null) {
            return (T) existing;
        }
        Page page = value instanceof Page source ? source
            : value instanceof Locator locator ? locator.page()
            : value instanceof Frame frame ? frame.page() : associatedPage;
        Object proxy = Proxy.newProxyInstance(EvidencePlaywright.class.getClassLoader(),
            interfaces.toArray(Class<?>[]::new), new BrowserObject(this, value, page));
        wrappers.put(value, proxy);
        if (value instanceof Page source) {
            source.onClose(closed -> closeEvidence(closed, false));
            source.onCrash(crashed -> closeEvidence(crashed, false));
        }
        return (T) proxy;
    }

    static FacadeRuntime runtimeOf(Object value) {
        if (value != null && Proxy.isProxyClass(value.getClass())
            && Proxy.getInvocationHandler(value) instanceof BrowserObject wrapper) {
            return wrapper.runtime;
        }
        return null;
    }

    static Object unwrap(Object value) {
        if (runtimeOf(value) != null) {
            return ((BrowserObject) Proxy.getInvocationHandler(value)).delegate;
        }
        return value;
    }

    PlaywrightEvidence evidence(Page page) {
        requireThread();
        if (ended.contains(page) || page.isClosed()) {
            throw new IllegalStateException("Evidence page has closed");
        }
        return recorders.computeIfAbsent(page, source -> {
            try {
                PlaywrightEvidence recorder = PlaywrightEvidence.start(reserveDirectory(), source,
                    options.capture(), options.origin(), options.testObjective());
                recorder.attachFacade(this);
                return recorder;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        });
    }

    void enter() {
        requireThread();
        depth++;
    }

    void leave() {
        depth--;
    }

    boolean recording() {
        requireThread();
        return depth == 0;
    }

    private void requireThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Use the facade on the thread that created Playwright");
        }
    }

    private Path reserveDirectory() throws IOException {
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

    private void closeEvidence(Page page, boolean capture) {
        if (!ended.add(page)) {
            return;
        }
        PlaywrightEvidence recorder = recorders.get(page);
        if (recorder != null) {
            enter();
            try {
                recorder.closeAvailable(capture && !page.isClosed());
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            } finally {
                leave();
            }
        }
    }

    private Object close(Object delegate, Method method, Object[] arguments) throws Throwable {
        Throwable failure = null;
        for (Page page : new ArrayList<>(recorders.keySet())) {
            boolean belongs = delegate instanceof Playwright || delegate == page
                || delegate == page.context() || delegate == page.context().browser();
            if (belongs) {
                try {
                    closeEvidence(page, true);
                } catch (RuntimeException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
        }
        Object result;
        enter();
        try {
            result = invokeNative(delegate, method, arguments);
        } catch (Throwable error) {
            if (failure != null) {
                error.addSuppressed(failure);
            }
            throw error;
        } finally {
            leave();
            if (delegate instanceof Playwright) {
                wrappers.clear();
                callbacks.clear();
            }
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    private Object argument(Object value, Page page) throws ReflectiveOperationException {
        Object raw = unwrap(value);
        if (raw != value || value == null) {
            return raw;
        }
        if (value instanceof Consumer<?> || value instanceof Predicate<?> || value instanceof Function<?, ?>
            || value instanceof BiConsumer<?, ?> || value instanceof BiFunction<?, ?, ?>) {
            return callbacks.computeIfAbsent(value, callback -> callback(callback, page));
        }
        if (value instanceof List<?> values) {
            List<Object> result = new ArrayList<>();
            for (Object item : values) {
                result.add(argument(item, page));
            }
            return result;
        }
        if (value instanceof Map<?, ?> values) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                result.put(entry.getKey(), argument(entry.getValue(), page));
            }
            return result;
        }
        if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            Object result = Array.newInstance(value.getClass().getComponentType(), Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                Array.set(result, index, argument(Array.get(value, index), page));
            }
            return result;
        }
        if (value.getClass().getName().startsWith("com.microsoft.playwright.")
            && value.getClass().getSimpleName().endsWith("Options") && !value.getClass().isInterface()) {
            Object copy = value.getClass().getConstructor().newInstance();
            for (Field field : value.getClass().getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.set(copy, argument(field.get(value), page));
                }
            }
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Object callback(Object callback, Page page) {
        if (callback instanceof Consumer<?> consumer) {
            return (Consumer<Object>) value -> ((Consumer<Object>) consumer).accept(wrap(value, page));
        }
        if (callback instanceof Predicate<?> predicate) {
            return (Predicate<Object>) value -> ((Predicate<Object>) predicate).test(wrap(value, page));
        }
        if (callback instanceof Function<?, ?> function) {
            return (Function<Object, Object>) value -> unwrap(((Function<Object, Object>) function).apply(wrap(value, page)));
        }
        if (callback instanceof BiConsumer<?, ?> consumer) {
            return (BiConsumer<Object, Object>) (first, second) ->
                ((BiConsumer<Object, Object>) consumer).accept(wrap(first, page), wrap(second, page));
        }
        return (BiFunction<Object, Object, Object>) (first, second) -> unwrap(
            ((BiFunction<Object, Object, Object>) callback).apply(wrap(first, page), wrap(second, page)));
    }

    static Object invokeNative(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private record BrowserObject(FacadeRuntime runtime, Object delegate, Page page) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] supplied) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == supplied[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> delegate.toString();
                };
            }
            runtime.requireThread();
            Object[] arguments = supplied == null ? new Object[0] : supplied.clone();
            for (int index = 0; index < arguments.length; index++) {
                arguments[index] = runtime.argument(arguments[index], page);
            }
            if (method.getName().equals("close") && (delegate instanceof Page || delegate instanceof Browser
                || delegate instanceof BrowserContext || delegate instanceof Playwright)) {
                return runtime.close(delegate, method, arguments);
            }
            Object result;
            if (page != null && runtime.recording() && FacadeActions.supported(delegate, method.getName(), arguments)) {
                PlaywrightEvidence evidence = runtime.evidence(page);
                Locator target = FacadeActions.target(delegate, page, method.getName(), arguments);
                boolean sensitive = FacadeActions.sensitive(target);
                Object[] returned = new Object[1];
                try {
                    evidence.performAutomatic(method.getName(), target,
                        FacadeActions.parameters(delegate, method.getName(), arguments, sensitive), sensitive, () -> {
                            try {
                                returned[0] = invokeNative(delegate, method, arguments);
                            } catch (RuntimeException | Error error) {
                                throw error;
                            } catch (Throwable error) {
                                throw new IllegalStateException(error);
                            }
                        });
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
                result = returned[0];
            } else {
                result = invokeNative(delegate, method, arguments);
            }
            return runtime.wrap(result, page);
        }
    }
}
