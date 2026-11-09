package org.testar.evidence.playwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Page;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testar.evidence.ActionExecutionStatus;
import org.testar.evidence.ActionOutcome;
import org.testar.evidence.AvailableActionScope;
import org.testar.evidence.Event;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.EvidenceReader;
import org.testar.evidence.ExpectedValue;
import org.testar.evidence.ObservedValue;
import org.testar.evidence.Oracle;
import org.testar.evidence.OracleSeverity;
import org.testar.evidence.OracleStatus;
import org.testar.evidence.OracleTarget;
import org.testar.evidence.Properties;

class RecordingApiTest {
    @TempDir
    Path directory;

    private PlaywrightEvidence start() throws Exception {
        Page page = (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[]{Page.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "url" -> "about:blank";
                case "isClosed" -> false;
                case "evaluate" -> EvidenceJson.parse("{\"properties\":{\"ui.title\":\"Fixture\",\"ui.status\":\"Denied\"},"
                    + "\"nodes\":[{\"path\":\"#document\",\"parent\":null,\"properties\":{}}]}");
                case "toString" -> "FixturePage";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        CaptureOptions defaults = CaptureOptions.defaults();
        CaptureOptions capture = new CaptureOptions(defaults.maxWidgets(), defaults.maxValueCharacters(),
            defaults.maxTotalCharacters(), defaults.maxAttributesPerWidget(), defaults.computedStyles(),
            defaults.excludedAttributes(), false);
        return PlaywrightEvidence.start(directory, page, capture);
    }

    @Test
    void committed_actions_reuse_the_source_state_and_reject_stale_observations() throws Exception {
        AvailableAction action = new AvailableAction("custom", "Choose option", null);
        AtomicInteger operations = new AtomicInteger();
        try (PlaywrightEvidence recorder = start()) {
            String first = recorder.capture();
            assertThrows(IllegalArgumentException.class, () ->
                recorder.recordAvailableActions(List.of(action), AvailableActionScope.ELIGIBLE, "unknown", null));
            recorder.recordAvailableActions(List.of(action), AvailableActionScope.ELIGIBLE, first, null);
            assertEquals(first, recorder.commitCapture());
            assertThrows(IllegalArgumentException.class, () -> recorder.selectRecordedAction(
                new AvailableAction("custom", "Not reported", null), first, Map.of()));
            recorder.selectRecordedAction(action, first, Map.of("input", 42));
            recorder.executeSelected(operations::incrementAndGet);
            String second = recorder.capture();
            recorder.recordAvailableActions(List.of(), AvailableActionScope.ELIGIBLE, second, List.of());
            recorder.commitCapture();
            assertThrows(IllegalArgumentException.class, () -> recorder.selectRecordedAction(action, first, Map.of()));
            assertThrows(IllegalStateException.class, recorder::commitCapture);
            var trace = recorder.recordedEvidence();
            assertEquals(2, trace.observations().size());
            assertEquals(1, trace.actionExecutions().size());
            assertEquals(first, trace.actionExecutions().get(0).actionExecution().sourceObservation());
            assertEquals(1, trace.recordedTransitions().size());
        }
        assertEquals(1, operations.get());
        assertEquals(2, EvidenceReader.read(directory).observations().size());
    }

    @Test
    void histories_start_and_finish_in_order_using_schema_three() throws Exception {
        try (PlaywrightEvidence recorder = start()) {
            recorder.observe();
        }
        var evidence = EvidenceReader.read(directory);
        assertEquals(1, evidence.testRuns().size());
        assertEquals(1, evidence.testSequences().size());
        assertTrue(evidence.testRuns().get(0).finished());
        assertTrue(evidence.testSequences().get(0).finished());
        assertEquals(evidence.testSequences().get(0).testSequence().id(), evidence.observations().get(0).testSequence());
        var events = Files.readAllLines(directory.resolve("evidence.jsonl")).stream().map(EvidenceJson::readEvent).toList();
        assertTrue(events.get(0).event() instanceof Event.TestRunStarted);
        assertTrue(events.get(1).event() instanceof Event.TestSequenceStarted);
        assertTrue(events.get(events.size() - 2).event() instanceof Event.TestSequenceFinished);
        assertTrue(events.get(events.size() - 1).event() instanceof Event.TestRunFinished);
        assertTrue(events.stream().allMatch(event -> event.schemaVersion() == 3));
    }

    @Test
    void available_actions_preserve_unexecuted_choices_and_link_the_selected_description() throws Exception {
        AvailableAction first = new AvailableAction("custom", "Choose option", null, Map.of("strategy", "first", "input", Map.of("value", "planned")));
        AvailableAction second = new AvailableAction("custom", "Choose option", null, Map.of("strategy", "second"));
        AtomicInteger operations = new AtomicInteger();
        String source;
        try (PlaywrightEvidence recorder = start()) {
            source = recorder.recordAvailableActions(List.of(first, second), AvailableActionScope.ELIGIBLE);
            recorder.perform(first.actionType(), first.name(), null, Map.of("generated_input", 42, "input", Map.of("value", "actual")), () -> {
                operations.incrementAndGet();
                operations.incrementAndGet();
            }, first);
            recorder.capture();
        }
        var evidence = EvidenceReader.read(directory);
        assertEquals(2, operations.get());
        assertEquals(2, evidence.availableActions(source).size());
        assertEquals(AvailableActionScope.ELIGIBLE, evidence.observation(source).orElseThrow().availableActionScope());
        assertEquals(1, evidence.actionExecutions().size());
        var execution = evidence.actionExecutions().get(0);
        assertEquals(first.description().id(), execution.actionExecution().actionDescription());
        assertEquals(source, execution.actionExecution().sourceObservation());
        assertEquals(42, execution.actionExecution().parameters().get("generated_input").asInt());
        var description = evidence.actionDescription(execution.actionExecution().actionDescription()).orElseThrow();
        assertEquals("planned", description.properties().get("playwright.available_parameters").get("input").get("value").asText());
        assertEquals("actual", execution.actionExecution().parameters().get("input").get("value").asText());
        assertFalse(execution.actionExecution().parameters().contains("strategy"));
        assertEquals(ActionExecutionStatus.COMPLETED, execution.status());
        assertEquals(1, evidence.recordedTransitions().size());
    }

    @Test
    void an_unreported_choice_is_rejected_before_the_operation_runs() throws Exception {
        AvailableAction reported = new AvailableAction("click", "First", null);
        AvailableAction other = new AvailableAction("click", "Other", null);
        AtomicInteger operations = new AtomicInteger();
        try (PlaywrightEvidence recorder = start()) {
            recorder.recordAvailableActions(List.of(reported), AvailableActionScope.GENERATED);
            assertThrows(IllegalArgumentException.class, () -> recorder.perform(other.actionType(), other.name(),
                null, Map.of(), operations::incrementAndGet, other));
        }
        assertEquals(0, operations.get());
        assertTrue(EvidenceReader.read(directory).actionExecutions().isEmpty());
    }

    @Test
    void an_execution_error_keeps_its_message_without_inventing_a_transition() throws Exception {
        try (PlaywrightEvidence recorder = start()) {
            recorder.capture();
            recorder.selectAction("click", "Native operation", null, Map.of());
            recorder.reportActionResult(ActionOutcome.ERROR, "Native runner could not click");
            recorder.capture();
        }
        var evidence = EvidenceReader.read(directory);
        assertEquals(ActionExecutionStatus.FAILED, evidence.actionExecutions().get(0).status());
        assertEquals("Native runner could not click", evidence.actionExecutions().get(0).executionResult().error());
        assertEquals(2, evidence.observations().size());
        assertTrue(evidence.recordedTransitions().isEmpty());
    }

    @Test
    void manual_results_preserve_oracle_details_targets_severity_and_null_expectations() throws Exception {
        Oracle oracle = new Oracle("fixture:status", "1", Properties.empty());
        OracleSeverity severity = new OracleSeverity("  Important  ", new BigDecimal("25.125"));
        try (PlaywrightEvidence recorder = start()) {
            recorder.capture();
            recorder.checks().recordResult(oracle, "Status was checked", OracleStatus.PASSED, null, "ui.status",
                ExpectedValue.of(null), new ObservedValue.Value(EvidenceJson.tree("Denied")), null,
                Properties.of(Map.of("caller.attempt", 3)), severity);
            recorder.checks().recordResult(oracle, "Another result", OracleStatus.UNKNOWN);
        }
        var evidence = EvidenceReader.read(directory);
        var result = evidence.oracleResults().get(0);
        assertEquals(OracleStatus.PASSED, result.oracleStatus());
        assertEquals(severity, result.oracleSeverity());
        assertEquals(3, result.oracleDetails().get("caller.attempt").asInt());
        assertTrue(result.expectedValue().supplied());
        assertTrue(result.expectedValue().value().isNull());
        assertTrue(result.oracleTarget() instanceof OracleTarget.StateProperty);
        assertEquals(null, evidence.oracleResults().get(1).oracleSeverity());
        assertEquals(evidence.oracleResults().get(0).oracleTarget().getClass(), OracleTarget.StateProperty.class);
        assertEquals(1, evidence.observations().size());
        assertTrue(evidence.actionExecutions().isEmpty());
    }

    @Test
    void malformed_severity_is_rejected_before_running_an_oracle() throws Exception {
        AtomicInteger evaluations = new AtomicInteger();
        try (PlaywrightEvidence recorder = start()) {
            assertThrows(IllegalArgumentException.class, () -> recorder.checks().evaluate("Check", new Oracle("fixture", null, null),
                null, null, ExpectedValue.unspecified(), new OracleSeverity(" ", null),
                evaluations::incrementAndGet, ObservedValue.Unknown::new));
        }
        assertEquals(0, evaluations.get());
        assertTrue(EvidenceReader.read(directory).oracleResults().isEmpty());
    }

    @Test
    void an_empty_eligible_list_and_an_unreported_execution_keep_their_meanings() throws Exception {
        String source;
        try (PlaywrightEvidence recorder = start()) {
            source = recorder.recordAvailableActions(List.of(), AvailableActionScope.ELIGIBLE);
            recorder.selectAction("custom", "Caller operation", null, Map.of());
        }
        var evidence = EvidenceReader.read(directory);
        assertEquals(AvailableActionScope.ELIGIBLE, evidence.observation(source).orElseThrow().availableActionScope());
        assertEquals(ActionExecutionStatus.INCOMPLETE, evidence.actionExecutions().get(0).status());
        assertTrue(evidence.recordedTransitions().isEmpty());
    }
}
