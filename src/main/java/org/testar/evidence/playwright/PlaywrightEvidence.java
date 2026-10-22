package org.testar.evidence.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.testar.evidence.ActionCandidate;
import org.testar.evidence.ActionDefinition;
import org.testar.evidence.ArtifactReference;
import org.testar.evidence.CandidateScope;
import org.testar.evidence.CaptureScope;
import org.testar.evidence.CheckReading;
import org.testar.evidence.CheckResult;
import org.testar.evidence.CheckRule;
import org.testar.evidence.CheckStatus;
import org.testar.evidence.CheckTarget;
import org.testar.evidence.ConcreteState;
import org.testar.evidence.Event;
import org.testar.evidence.EventEnvelope;
import org.testar.evidence.Execution;
import org.testar.evidence.ExpectedValue;
import org.testar.evidence.JsonlStore;
import org.testar.evidence.Manifest;
import org.testar.evidence.Observation;
import org.testar.evidence.Properties;
import org.testar.evidence.Run;
import org.testar.evidence.Widget;

/** Records actions and checks explicitly submitted through this session. The caller owns the page. */
public final class PlaywrightEvidence implements AutoCloseable {
    public static final String VERSION = "0.2.1";

    private final Page page;
    private final CaptureOptions options;
    private final JsonlStore store;
    private final String runId;
    private final PlaywrightChecks checks;
    private final Map<String, ActionDefinition> definitions = new LinkedHashMap<>();
    private long events;
    private long observations;
    private long executions;
    private PendingObservation pending;
    private String pendingResult;
    private String lastObservation;
    private String endingReason = "closed";
    private boolean finished;
    private boolean closed;

    private PlaywrightEvidence(Page page, CaptureOptions options, JsonlStore store, String runId) {
        this.page = page;
        this.options = options;
        this.store = store;
        this.runId = runId;
        this.checks = new PlaywrightChecks(this);
    }

    public static PlaywrightEvidence start(Path directory, Page page) throws IOException {
        return start(directory, page, CaptureOptions.defaults());
    }

    public static PlaywrightEvidence start(Path directory, Page page, CaptureOptions options) throws IOException {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(options, "options");
        String identity = UUID.randomUUID().toString();
        JsonlStore store = JsonlStore.create(directory, new Manifest("playwright-java:model:" + identity));
        PlaywrightEvidence evidence = new PlaywrightEvidence(page, options, store, "playwright-java:run:" + identity);
        try {
            evidence.emit(new Event.RunStarted(new Run(evidence.runId, "playwright-java", identity,
                Properties.of(Map.of("playwright.adapter_version", VERSION, "playwright.initial_url", page.url())))));
            return evidence;
        } catch (IOException | RuntimeException error) {
            try {
                store.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    public PlaywrightChecks checks() {
        return checks;
    }

    public void navigate(String url) throws IOException {
        Objects.requireNonNull(url, "url");
        perform("navigate", "Navigate to " + url, null, Map.of("url", url), () -> page.navigate(url));
    }

    public void click(Locator locator) throws IOException {
        perform("click", "Click " + locator, Objects.requireNonNull(locator, "locator"),
            Map.of(), locator::click);
    }

    public void fill(Locator locator, String value) throws IOException {
        Objects.requireNonNull(value, "value");
        perform("fill", "Fill " + locator, Objects.requireNonNull(locator, "locator"),
            Map.of("value", value), () -> locator.fill(value));
    }

    public void fillSecret(Locator locator, String value) throws IOException {
        Objects.requireNonNull(value, "value");
        perform("fill", "Fill " + locator, Objects.requireNonNull(locator, "locator"),
            Map.of("playwright.redacted_parameters", List.of("value")), () -> locator.fill(value));
    }

    /** Supplies the test's action boundary and concrete arguments, including for custom actions. */
    public void perform(String kind, String name, Locator locator, Map<String, ?> parameters,
                        Runnable operation) throws IOException {
        requireActive();
        Objects.requireNonNull(operation, "operation");
        Properties supplied = Properties.of(parameters);
        String description = locator == null ? name : locator.toString();
        ActionDefinition definition = new ActionDefinition("playwright:action:" + DomCapture.identity(kind + "\n" + description),
            kind, Properties.of(Map.of("playwright.description", name, "playwright.locator", description)));
        ensurePending();
        String path = DomCapture.targetPath(locator);
        Widget target = pending.snapshot.widgetAt(path);
        Map<String, Object> actual = new LinkedHashMap<>();
        supplied.asJson().properties().forEach(entry -> actual.put(entry.getKey(), entry.getValue()));
        actual.put("playwright.origin", "scripted");
        if (locator != null) {
            actual.put("playwright.locator", description);
            actual.put("playwright.target_resolution", target == null ? "not_captured" : "captured_dom_path");
        }
        if (target != null && target.properties().contains("playwright.attribute.type")
            && "password".equalsIgnoreCase(target.properties().get("playwright.attribute.type").asText())) {
            actual.remove("value");
            actual.put("playwright.redacted_parameters", List.of("value"));
        }
        Properties executionParameters = Properties.of(actual);
        String source = flush(definition, target == null ? null : target.id());
        String execution = runId + ":execution:" + (++executions);
        emit(new Event.ActionSelected(new Execution(execution, runId, source, definition.id(),
            target == null ? null : target.id(), executionParameters)));
        try {
            operation.run();
        } catch (RuntimeException | AssertionError error) {
            endingReason = "action_failed";
            try {
                emit(new Event.ActionSubmitted(execution, false, error.toString()));
            } catch (IOException | RuntimeException recording) {
                error.addSuppressed(recording);
            }
            throw error;
        }
        emit(new Event.ActionSubmitted(execution, true, null));
        pendingResult = execution;
    }

    /** Records an additional visit without selecting an action. */
    public void observe() throws IOException {
        requireActive();
        if (pending != null) {
            flush(null, null);
        }
        ensurePending();
        flush(null, null);
    }

    public void finish(String reason) throws IOException {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Run-ending reason is blank");
        }
        if (finished) {
            return;
        }
        requireActive();
        if (pending != null || pendingResult != null || lastObservation == null) {
            ensurePending();
            flush(null, null);
        }
        emit(new Event.RunFinished(runId, reason));
        finished = true;
    }

    void recordCheck(String name, CheckRule rule, Locator locator, String property, ExpectedValue expected,
                     CheckStatus status, CheckReading actual, Map<String, ?> details) throws IOException {
        requireActive();
        // A second check gets its own captured visit rather than rewriting the first reading's snapshot.
        if (pending != null) {
            flush(null, null);
        }
        ensurePending();
        String path = DomCapture.targetPath(locator);
        Widget widget = pending.snapshot.widgetAt(path);
        Map<String, Object> context = new LinkedHashMap<>(details);
        CheckTarget target;
        if (locator == null) {
            target = property == null ? new CheckTarget.Observation(pending.id)
                : new CheckTarget.StateProperty(pending.id, property);
        } else if (widget != null) {
            target = property == null ? new CheckTarget.Widget(pending.id, widget.id())
                : new CheckTarget.WidgetProperty(pending.id, widget.id(), property);
        } else {
            target = new CheckTarget.Observation(pending.id);
            context.put("playwright.requested_target", Map.of("locator", locator.toString(),
                "property", property == null ? "" : property));
            context.put("playwright.target_resolution", "The locator did not identify a captured top-document widget.");
        }
        pending.checks.add(new CheckResult(runId, name, rule, target, status, expected, actual, Properties.of(context)));
        if (status == CheckStatus.FAILED || status == CheckStatus.ERROR) {
            endingReason = "check_failed";
        }
    }

    private void ensurePending() {
        if (pending == null) {
            pending = new PendingObservation(runId + ":observation:" + (++observations), observations,
                DomCapture.capture(page, options));
        }
    }

    private String flush(ActionDefinition nextAction, String target) throws IOException {
        PendingObservation observation = pending;
        List<ActionCandidate> candidates = List.of();
        if (nextAction != null) {
            ActionDefinition previous = definitions.get(nextAction.id());
            if (previous != null && !previous.equals(nextAction)) {
                throw new IllegalArgumentException("Conflicting action definition: " + nextAction.id());
            }
            if (previous == null) {
                emit(new Event.ActionDefined(nextAction));
                definitions.put(nextAction.id(), nextAction);
            }
            candidates = List.of(new ActionCandidate(nextAction.id(), target));
        }
        List<ArtifactReference> artifacts = observation.snapshot.screenshot() == null ? List.of()
            : List.of(store.putArtifact(observation.snapshot.screenshot(), "image/png", null, null));
        ConcreteState state = new ConcreteState(observation.id + ":snapshot", observation.snapshot.properties(),
            observation.snapshot.widgets(), CaptureScope.FILTERED);
        emit(new Event.Observed(new Observation(observation.id, runId, observation.sequence,
            observation.snapshot.timestampMicros(), state, candidates, CandidateScope.UNKNOWN, artifacts)));
        lastObservation = observation.id;
        pending = null;
        if (pendingResult != null) {
            String execution = pendingResult;
            emit(new Event.ActionResult(execution, observation.id));
            pendingResult = null;
        }
        for (CheckResult check : observation.checks) {
            emit(new Event.Checked(check));
        }
        return observation.id;
    }

    private void emit(Event event) throws IOException {
        long next = events + 1;
        if (!store.record(new EventEnvelope(runId + ":event:" + next, event))) {
            throw new IllegalStateException("Duplicate recorder event");
        }
        events = next;
    }

    private void requireActive() {
        if (closed || finished) {
            throw new IllegalStateException("Evidence session has finished");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            try (store) {
                if (!finished) {
                    finish(endingReason);
                }
            } finally {
                closed = true;
            }
        }
    }

    private static final class PendingObservation {
        private final String id;
        private final long sequence;
        private final BrowserSnapshot snapshot;
        private final List<CheckResult> checks = new ArrayList<>();

        private PendingObservation(String id, long sequence, BrowserSnapshot snapshot) {
            this.id = id;
            this.sequence = sequence;
            this.snapshot = snapshot;
        }
    }
}
