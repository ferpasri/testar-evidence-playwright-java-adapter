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
import org.testar.evidence.ActionDescription;
import org.testar.evidence.ActionExecution;
import org.testar.evidence.ActionExecutionResult;
import org.testar.evidence.ActionOutcome;
import org.testar.evidence.ArtifactReference;
import org.testar.evidence.AvailableActionScope;
import org.testar.evidence.CaptureScope;
import org.testar.evidence.Event;
import org.testar.evidence.EvidenceEvent;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.ExpectedValue;
import org.testar.evidence.JsonlStore;
import org.testar.evidence.Manifest;
import org.testar.evidence.Observation;
import org.testar.evidence.ObservedValue;
import org.testar.evidence.Oracle;
import org.testar.evidence.OracleResult;
import org.testar.evidence.OracleSeverity;
import org.testar.evidence.OracleStatus;
import org.testar.evidence.OracleTarget;
import org.testar.evidence.Properties;
import org.testar.evidence.RecordedState;
import org.testar.evidence.ReconstructedEvidence;
import org.testar.evidence.TestRun;
import org.testar.evidence.TestSequence;
import org.testar.evidence.Widget;

/** Records actions and oracle results for one page. The caller owns the native page. */
public final class PlaywrightEvidence implements AutoCloseable {
    public static final String VERSION = "0.3.1";
    public static final String ADAPTER_ID = "testar-evidence-playwright-java-adapter";

    private final Page page;
    private final CaptureOptions options;
    private final JsonlStore store;
    private final Path directory;
    private final String runId;
    private final String sequenceId;
    private final PlaywrightChecks checks;
    private final Map<String, ActionDescription> descriptions = new LinkedHashMap<>();
    private final Thread owner = Thread.currentThread();
    private long events;
    private long observations;
    private long executions;
    private PendingObservation pending;
    private String pendingResult;
    private String selectedExecution;
    private boolean selectedErrorRedacted;
    private String lastObservation;
    private String endingReason = "closed";
    private FacadeRuntime facade;
    private boolean busy;
    private boolean broken;
    private boolean finished;
    private boolean closed;

    private PlaywrightEvidence(Page page, CaptureOptions options, JsonlStore store, String runId, Path directory) {
        this.page = page;
        this.options = options;
        this.store = store;
        this.directory = directory.toAbsolutePath().normalize();
        this.runId = runId;
        this.sequenceId = runId + ":sequence:1";
        this.checks = new PlaywrightChecks(this);
    }

    /** Start a uniquely named trace; the caller retains ownership of the page and browser. */
    public static PlaywrightEvidence startTrace(Page page, EvidenceOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return start(TraceDirectories.reserve(options), page, options.capture(), options.origin(), options.testObjective());
    }

    /** Whether this recorder belongs to the supplied native or facade page. */
    public boolean isFor(Page page) {
        return this.page == FacadeRuntime.unwrap(page);
    }

    /** Snapshot committed evidence without taking another browser capture or reading files. */
    public ReconstructedEvidence recordedEvidence() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Read evidence on its Playwright thread");
        }
        return store.evidence();
    }

    /** Save the buffered capture and any pending transition without capturing the browser again. */
    public String commitCapture() throws IOException {
        requireActive();
        requireNoUnreportedExecution();
        if (pending == null) {
            throw new IllegalStateException("Capture an observation before committing it");
        }
        String[] result = new String[1];
        inScope(() -> result[0] = flush(null, null));
        return result[0];
    }

    /** Select an action bound to the latest committed observation, without recapturing its source state. */
    public String selectRecordedAction(AvailableAction action, String observation, Map<String, ?> parameters) throws IOException {
        requireActive();
        requireNoUnreportedExecution();
        Objects.requireNonNull(action, "action");
        if (pending != null || observation == null || !observation.equals(lastObservation)) {
            throw new IllegalArgumentException("Use the latest committed observation with no buffered capture");
        }
        ActionDescription description = action.description();
        var matches = store.evidence().availableActions(observation).stream()
            .filter(binding -> binding.actionDescription().equals(description.id())).toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("Action must have one available binding in the recorded observation");
        }
        Properties supplied = Properties.of(parameters);
        Map<String, Object> actual = new LinkedHashMap<>();
        supplied.asJson().properties().forEach(entry -> actual.put(entry.getKey(), entry.getValue()));
        List<String> redacted = new ArrayList<>();
        if (supplied.contains("playwright.redacted_parameters")) {
            supplied.get("playwright.redacted_parameters").forEach(value -> redacted.add(value.asText()));
        }
        Locator locator = (Locator) FacadeRuntime.unwrap(action.locator());
        inScope(() -> {
            boolean hasInput = List.of("value", "text", "key", "playwright.positional_arguments", "playwright.keyword_arguments")
                .stream().anyMatch(actual::containsKey);
            if (hasInput && (locator == null || FacadeActions.sensitive(locator))) {
                for (String key : List.of("value", "text", "key", "playwright.positional_arguments", "playwright.keyword_arguments")) {
                    if (actual.remove(key) != null && !redacted.contains(key)) {
                        redacted.add(key);
                    }
                }
            }
        });
        if (!redacted.isEmpty()) {
            actual.put("playwright.redacted_parameters", List.copyOf(redacted));
        }
        String execution = sequenceId + ":execution:" + (++executions);
        emit(new Event.ActionSelected(new ActionExecution(execution, sequenceId, observation, description.id(),
            matches.get(0).targetWidget(), Properties.of(actual))));
        selectedExecution = execution;
        selectedErrorRedacted = !redacted.isEmpty();
        return execution;
    }

    /** Execute the already selected action while suppressing nested facade records. */
    public void executeSelected(Runnable operation) throws IOException {
        requireActive();
        if (selectedExecution == null) {
            throw new IllegalStateException("Select an action before executing it");
        }
        Objects.requireNonNull(operation, "operation");
        inScope(() -> executeSelectedInternal(operation, false));
    }

    public static PlaywrightEvidence start(Path directory, Page page) throws IOException {
        return start(directory, page, CaptureOptions.defaults());
    }

    public static PlaywrightEvidence start(Path directory, Page page, CaptureOptions options) throws IOException {
        return start(directory, page, options, null, null);
    }

    public static PlaywrightEvidence start(Path directory, Page page, String origin, String testObjective) throws IOException {
        return start(directory, page, CaptureOptions.defaults(), origin, testObjective);
    }

    public static PlaywrightEvidence start(Path directory, Page page, CaptureOptions options,
                                           String origin, String testObjective) throws IOException {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(options, "options");
        FacadeRuntime facade = FacadeRuntime.runtimeOf(page);
        page = (Page) FacadeRuntime.unwrap(page);
        Map<String, Object> properties = new LinkedHashMap<>();
        addDescription(properties, "evidence.origin", origin);
        addDescription(properties, "evidence.test_objective", testObjective);
        properties.put("evidence.adapter", ADAPTER_ID);
        properties.put("evidence.adapter_version", VERSION);
        properties.put("playwright.initial_url", page.url());
        String identity = UUID.randomUUID().toString();
        JsonlStore store = JsonlStore.create(directory, new Manifest("playwright-java:evidence:" + identity));
        PlaywrightEvidence evidence = new PlaywrightEvidence(page, options, store, "playwright-java:run:" + identity, directory);
        evidence.facade = facade;
        try {
            evidence.emit(new Event.TestRunStarted(new TestRun(evidence.runId, "playwright-java", identity,
                Properties.of(properties))));
            evidence.emit(new Event.TestSequenceStarted(new TestSequence(evidence.sequenceId, evidence.runId, 1, "1", Properties.empty())));
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

    private static void addDescription(Map<String, Object> properties, String key, String value) {
        if (value != null) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Run description must be nonblank: " + key);
            }
            properties.put(key, value);
        }
    }

    public String runId() {
        return runId;
    }

    public String sequenceId() {
        return sequenceId;
    }

    public Path directory() {
        return directory;
    }

    public PlaywrightChecks checks() {
        return checks;
    }

    public void navigate(String url) throws IOException {
        Objects.requireNonNull(url, "url");
        perform("navigate", "Navigate to " + url, null, Map.of("url", url), () -> page.navigate(url));
    }

    public void click(Locator locator) throws IOException {
        perform("click", "Click " + locator, Objects.requireNonNull(locator, "locator"), Map.of(), locator::click);
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

    /** Record the choices reported by a caller, including an explicitly empty list. */
    public String recordAvailableActions(List<AvailableAction> actions, AvailableActionScope scope) throws IOException {
        return recordAvailableActions(actions, scope, null, null);
    }

    /** Attach choices to this buffered observation. Null paths request native locator resolution. */
    public String recordAvailableActions(List<AvailableAction> actions, AvailableActionScope scope,
                                         String observation, List<String> targetPaths) throws IOException {
        requireActive();
        requireNoUnreportedExecution();
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(scope, "scope");
        if (observation != null && (pending == null || !observation.equals(pending.id))) {
            throw new IllegalArgumentException("Observation must be the current buffered capture");
        }
        if (targetPaths != null && targetPaths.size() != actions.size()) {
            throw new IllegalArgumentException("Supply one target path per available action");
        }
        List<ActionDescription> supplied = actions.stream().map(AvailableAction::description).toList();
        final String[] result = new String[1];
        inScope(() -> {
            if (observation == null && pending != null) {
                flush(null, null);
            }
            ensurePending();
            List<org.testar.evidence.AvailableAction> bindings = new ArrayList<>();
            for (int index = 0; index < actions.size(); index++) {
                Locator locator = (Locator) FacadeRuntime.unwrap(actions.get(index).locator());
                Widget widget = pending.snapshot.widgetAt(targetPaths == null ? DomCapture.targetPath(locator) : targetPaths.get(index));
                org.testar.evidence.AvailableAction binding = new org.testar.evidence.AvailableAction(
                    supplied.get(index).id(), widget == null ? null : widget.id());
                if (!bindings.contains(binding)) {
                    bindings.add(binding);
                }
            }
            for (ActionDescription description : supplied) {
                describeAction(description);
            }
            pending.availableActions = List.copyOf(bindings);
            pending.availableActionScope = scope;
            result[0] = pending.id;
        });
        return result[0];
    }

    public String selectAction(String actionType, String name, Locator locator, Map<String, ?> parameters) throws IOException {
        return selectAction(actionType, name, locator, parameters, null);
    }

    /** Record selection before the caller executes its native operation. */
    public String selectAction(String actionType, String name, Locator locator, Map<String, ?> parameters,
                               AvailableAction availableAction) throws IOException {
        requireActive();
        requireNoUnreportedExecution();
        Objects.requireNonNull(name, "name");
        Locator nativeLocator = (Locator) FacadeRuntime.unwrap(locator);
        Properties supplied = Properties.of(parameters);
        String targetDescription = locator == null ? name : locator.toString();
        ActionDescription description = new ActionDescription("playwright:action:" + DomCapture.identity(
            EvidenceJson.write(EvidenceJson.tree(List.of(actionType, name, targetDescription)))), actionType,
            Properties.of(Map.of("playwright.description", name, "playwright.locator", targetDescription)));
        if (availableAction != null) {
            String reportedTarget = availableAction.locator() == null ? availableAction.name() : availableAction.locator().toString();
            if (!actionType.equals(availableAction.actionType()) || !name.equals(availableAction.name())
                || !targetDescription.equals(reportedTarget)) {
                throw new IllegalArgumentException("Selected action must match its available-action description");
            }
            description = availableAction.description();
        }
        ActionDescription selected = description;
        inScope(() -> {
            ensurePending();
            Widget widget = pending.snapshot.widgetAt(DomCapture.targetPath(nativeLocator));
            if (availableAction != null && pending.availableActions != null) {
                org.testar.evidence.AvailableAction binding = pending.availableActions.stream()
                    .filter(action -> action.actionDescription().equals(selected.id())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selected action was not reported for this observation"));
                widget = pending.snapshot.widgets().stream()
                    .filter(item -> item.id().equals(binding.targetWidget())).findFirst().orElse(null);
            }
            Map<String, Object> actual = new LinkedHashMap<>();
            supplied.asJson().properties().forEach(entry -> actual.put(entry.getKey(), entry.getValue()));
            if (nativeLocator != null) {
                actual.put("playwright.locator", targetDescription);
                actual.put("playwright.target_resolution", widget == null ? "not_captured" : "captured_dom_path");
            }
            List<String> redacted = new ArrayList<>();
            if (supplied.contains("playwright.redacted_parameters")) {
                supplied.get("playwright.redacted_parameters").forEach(value -> redacted.add(value.asText()));
            }
            if (nativeLocator == null || FacadeActions.sensitive(nativeLocator)) {
                for (String key : List.of("value", "text", "key", "playwright.positional_arguments", "playwright.keyword_arguments")) {
                    if (actual.remove(key) != null && !redacted.contains(key)) {
                        redacted.add(key);
                    }
                }
            }
            if (!redacted.isEmpty()) {
                actual.put("playwright.redacted_parameters", List.copyOf(redacted));
            }
            String source = flush(selected, widget == null ? null : widget.id());
            String execution = sequenceId + ":execution:" + (++executions);
            emit(new Event.ActionSelected(new ActionExecution(execution, sequenceId, source, selected.id(),
                widget == null ? null : widget.id(), Properties.of(actual))));
            selectedExecution = execution;
            selectedErrorRedacted = !redacted.isEmpty();
        });
        return selectedExecution;
    }

    public void reportActionResult(ActionOutcome outcome) throws IOException {
        reportActionResult(outcome, null);
    }

    public void reportActionResult(ActionOutcome outcome, String error) throws IOException {
        requireActive();
        reportResult(outcome, error);
    }

    private void reportResult(ActionOutcome outcome, String error) throws IOException {
        if (selectedExecution == null) {
            throw new IllegalStateException("No selected action is awaiting an outcome");
        }
        ActionExecutionResult result = new ActionExecutionResult(selectedExecution, outcome, error);
        emit(new Event.ActionExecutionReported(result));
        if (outcome == ActionOutcome.RETURNED) {
            pendingResult = selectedExecution;
        } else {
            endingReason = "action_failed";
        }
        selectedExecution = null;
        selectedErrorRedacted = false;
    }

    public void perform(String actionType, String name, Locator locator, Map<String, ?> parameters,
                        Runnable operation) throws IOException {
        perform(actionType, name, locator, parameters, operation, null);
    }

    /** Several native operations inside the callback form one compound action. */
    public void perform(String actionType, String name, Locator locator, Map<String, ?> parameters,
                        Runnable operation, AvailableAction availableAction) throws IOException {
        inScope(() -> performInternal(actionType, name, locator, parameters, operation, availableAction, false));
    }

    void performAutomatic(String actionType, Locator locator, Map<String, ?> parameters, boolean redactError,
                          Runnable operation) throws IOException {
        inScope(() -> {
            performInternal(actionType, "Playwright " + actionType, locator, parameters, operation, null, redactError);
            ensurePending();
        });
    }

    private void performInternal(String actionType, String name, Locator locator, Map<String, ?> parameters,
                                 Runnable operation, AvailableAction availableAction, boolean redactError) throws IOException {
        Objects.requireNonNull(operation, "operation");
        selectAction(actionType, name, locator, parameters, availableAction);
        executeSelectedInternal(operation, redactError);
    }

    private void executeSelectedInternal(Runnable operation, boolean redactError) throws IOException {
        busy = true;
        try {
            try {
                operation.run();
            } catch (RuntimeException | Error error) {
                endingReason = "action_failed";
                try {
                    reportResult(ActionOutcome.ERROR, redactError || selectedErrorRedacted
                        ? "Playwright action failed; details withheld for a sensitive or unresolved target" : error.toString());
                } catch (IOException | RuntimeException recording) {
                    error.addSuppressed(recording);
                }
                throw error;
            }
            reportResult(ActionOutcome.RETURNED, null);
        } finally {
            busy = false;
        }
    }

    public String capture() throws IOException {
        requireActive();
        requireNoUnreportedExecution();
        final String[] observation = new String[1];
        inScope(() -> {
            if (pending != null) {
                flush(null, null);
            }
            ensurePending();
            observation[0] = pending.id;
        });
        return observation[0];
    }

    public void observe() throws IOException {
        capture();
        inScope(() -> flush(null, null));
    }

    public void finish(String reason) throws IOException {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Ending reason is blank");
        }
        if (finished) {
            return;
        }
        requireActive();
        inScope(() -> {
            if (pending == null && selectedExecution == null && (pendingResult != null || lastObservation == null)) {
                ensurePending();
            }
            finishCaptured(reason);
        });
    }

    private void finishCaptured(String reason) throws IOException {
        if (pending != null) {
            flush(null, null);
        }
        emit(new Event.TestSequenceFinished(sequenceId, reason));
        emit(new Event.TestRunFinished(runId, reason));
        finished = true;
    }

    void finishAvailable(boolean capture) throws IOException {
        if (finished || closed || broken) {
            return;
        }
        if (capture) {
            finish(endingReason);
        } else {
            finishCaptured(endingReason);
        }
    }

    void recordOracleResult(String name, Oracle oracle, Locator locator, String property, ExpectedValue expected,
                            OracleStatus status, ObservedValue actual, Map<String, ?> details,
                            OracleSeverity severity, boolean reuseCapture) throws IOException {
        requireActive();
        if (pending != null && !reuseCapture) {
            flush(null, null);
        }
        ensurePending();
        Locator nativeLocator = (Locator) FacadeRuntime.unwrap(locator);
        Widget widget = pending.snapshot.widgetAt(DomCapture.targetPath(nativeLocator));
        Map<String, Object> context = new LinkedHashMap<>(details);
        OracleTarget target;
        if (locator == null) {
            target = property == null ? new OracleTarget.Observation(pending.id) : new OracleTarget.StateProperty(pending.id, property);
        } else if (widget != null) {
            target = property == null ? new OracleTarget.Widget(pending.id, widget.id())
                : new OracleTarget.WidgetProperty(pending.id, widget.id(), property);
        } else {
            target = new OracleTarget.Observation(pending.id);
            context.put("playwright.requested_target", Map.of("locator", locator.toString(), "property", property == null ? "" : property));
            context.put("playwright.target_resolution", "The locator did not identify a captured top-document widget.");
        }
        pending.oracleResults.add(new OracleResult(runId, sequenceId, name, oracle, target, status,
            expected, actual, severity, Properties.of(context)));
        if (status == OracleStatus.FAILED || status == OracleStatus.ERROR) {
            endingReason = "check_failed";
        }
    }

    private void ensurePending() {
        if (pending == null) {
            pending = new PendingObservation(sequenceId + ":observation:" + (++observations), observations, DomCapture.capture(page, options));
        }
    }

    private String flush(ActionDescription selected, String targetWidget) throws IOException {
        PendingObservation observation = pending;
        List<org.testar.evidence.AvailableAction> actions = observation.availableActions == null
            ? new ArrayList<>() : new ArrayList<>(observation.availableActions);
        if (selected != null) {
            describeAction(selected);
            org.testar.evidence.AvailableAction binding = new org.testar.evidence.AvailableAction(selected.id(), targetWidget);
            if (!actions.contains(binding)) {
                actions.add(binding);
            }
        }
        List<ArtifactReference> artifacts = observation.snapshot.screenshot() == null ? List.of()
            : List.of(store.putArtifact(observation.snapshot.screenshot(), "image/png", null, null));
        RecordedState state = new RecordedState(observation.id + ":state", observation.snapshot.properties(),
            observation.snapshot.widgets(), CaptureScope.FILTERED);
        emit(new Event.ObservationRecorded(new Observation(observation.id, sequenceId, observation.observationIndex,
            observation.snapshot.timestampMicros(), state, actions, observation.availableActionScope, artifacts)));
        lastObservation = observation.id;
        pending = null;
        if (pendingResult != null) {
            emit(new Event.TransitionRecorded(pendingResult, observation.id));
            pendingResult = null;
        }
        for (OracleResult result : observation.oracleResults) {
            emit(new Event.OracleResultRecorded(result));
        }
        return observation.id;
    }

    private void describeAction(ActionDescription description) throws IOException {
        ActionDescription previous = descriptions.get(description.id());
        if (previous != null && !previous.equals(description)) {
            throw new IllegalArgumentException("Conflicting action description: " + description.id());
        }
        if (previous == null) {
            emit(new Event.ActionDescribed(description));
            descriptions.put(description.id(), description);
        }
    }

    private void emit(Event event) throws IOException {
        try {
            if (!store.record(new EvidenceEvent(sequenceId + ":event:" + (events + 1), event))) {
                throw new IllegalStateException("Duplicate recorder event");
            }
            events++;
        } catch (IOException | RuntimeException error) {
            broken = true;
            throw error;
        }
    }

    void requireActive() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Use the evidence recorder on its Playwright thread");
        }
        if (closed || finished || broken || busy) {
            throw new IllegalStateException("Evidence recorder has finished, its writer failed, or an operation is already running");
        }
    }

    private void requireNoUnreportedExecution() {
        if (selectedExecution != null) {
            throw new IllegalStateException("Report the selected action outcome before continuing");
        }
    }

    void attachFacade(FacadeRuntime runtime) {
        facade = runtime;
    }

    void inScope(IoOperation operation) throws IOException {
        if (facade != null) {
            facade.enter();
        }
        try {
            operation.run();
        } finally {
            if (facade != null) {
                facade.leave();
            }
        }
    }

    @Override
    public void close() throws IOException {
        closeAvailable(!page.isClosed());
    }

    void closeAvailable(boolean capture) throws IOException {
        if (!closed) {
            try (store) {
                if (!finished && !broken) {
                    finishAvailable(capture);
                }
            } finally {
                closed = true;
            }
        }
    }

    @FunctionalInterface
    interface IoOperation {
        void run() throws IOException;
    }

    private static final class PendingObservation {
        private final String id;
        private final long observationIndex;
        private final BrowserSnapshot snapshot;
        private final List<OracleResult> oracleResults = new ArrayList<>();
        private List<org.testar.evidence.AvailableAction> availableActions;
        private AvailableActionScope availableActionScope = AvailableActionScope.UNKNOWN;

        private PendingObservation(String id, long observationIndex, BrowserSnapshot snapshot) {
            this.id = id;
            this.observationIndex = observationIndex;
            this.snapshot = snapshot;
        }
    }
}
