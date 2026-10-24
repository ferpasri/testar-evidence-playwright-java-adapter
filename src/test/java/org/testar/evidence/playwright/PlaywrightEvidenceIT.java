package org.testar.evidence.playwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testar.evidence.AvailableActionScope;
import org.testar.evidence.ObservedValue;
import org.testar.evidence.Oracle;
import org.testar.evidence.OracleSeverity;
import org.testar.evidence.OracleStatus;
import org.testar.evidence.OracleTarget;
import org.testar.evidence.EvidenceReader;
import org.testar.evidence.ActionExecutionStatus;
import org.testar.evidence.ExpectedValue;
import org.testar.evidence.Observation;
import org.testar.evidence.ReconstructedEvidence;
import org.testar.evidence.Widget;
import org.testar.evidence.playwright.examples.ParabankDeniedLoan;

class PlaywrightEvidenceIT {
    private static Playwright playwright;
    private static Browser browser;
    private Page page;
    @TempDir
    Path directory;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create(new Playwright.CreateOptions()
            .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("playwright.channel");
        if (channel != null && !channel.isBlank()) {
            options.setChannel(channel);
        }
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void createPage() {
        page = browser.newPage();
        page.setDefaultTimeout(5_000);
    }

    @AfterEach
    void closePage() {
        page.context().close();
    }

    @Test
    void run_descriptions_preserve_source_names_and_objectives_without_classifying_actions() throws Exception {
        page.setContent("<button>Continue</button>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page,
                "  QA équipe / custom CI  ", "  Request loan — prêt  ")) {
            evidence.click(page.locator("button"));
        }
        ReconstructedEvidence evidence = EvidenceReader.read(directory);
        var properties = evidence.testRuns().get(0).testRun().properties();
        assertEquals("  QA équipe / custom CI  ", properties.get("evidence.origin").asText());
        assertEquals("  Request loan — prêt  ", properties.get("evidence.test_objective").asText());
        assertEquals(PlaywrightEvidence.ADAPTER_ID, properties.get("evidence.adapter").asText());
        assertEquals(PlaywrightEvidence.VERSION, properties.get("evidence.adapter_version").asText());
        assertFalse(evidence.actionExecutions().get(0).actionExecution().parameters().contains("playwright.origin"));
    }

    @Test
    void omitted_run_descriptions_stay_absent_and_adapter_identity_is_automatic() throws Exception {
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.observe();
        }
        var properties = EvidenceReader.read(directory).testRuns().get(0).testRun().properties();
        assertFalse(properties.contains("evidence.origin"));
        assertFalse(properties.contains("evidence.test_objective"));
        assertEquals(PlaywrightEvidence.ADAPTER_ID, properties.get("evidence.adapter").asText());
        assertEquals(PlaywrightEvidence.VERSION, properties.get("evidence.adapter_version").asText());
    }

    @Test
    void either_run_description_can_be_supplied_independently() throws Exception {
        Path sourceOnly = directory.resolve("source");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(sourceOnly, page, "Custom runner", null)) {
            evidence.observe();
        }
        var source = EvidenceReader.read(sourceOnly).testRuns().get(0).testRun().properties();
        assertEquals("Custom runner", source.get("evidence.origin").asText());
        assertFalse(source.contains("evidence.test_objective"));
        Path objectiveOnly = directory.resolve("objective");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(objectiveOnly, page, null, "Inspect the loan form")) {
            evidence.observe();
        }
        var objective = EvidenceReader.read(objectiveOnly).testRuns().get(0).testRun().properties();
        assertEquals("Inspect the loan form", objective.get("evidence.test_objective").asText());
        assertFalse(objective.contains("evidence.origin"));
    }

    @Test
    void blank_descriptions_are_rejected_before_creating_a_trace() {
        for (String blank : List.of("", " ", "\t\n")) {
            Path output = directory.resolve("invalid");
            assertThrows(IllegalArgumentException.class, () -> PlaywrightEvidence.start(output, page, blank, null));
            assertFalse(Files.exists(output));
            assertThrows(IllegalArgumentException.class, () -> PlaywrightEvidence.start(output, page, null, blank));
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void loan_denial_script_produces_a_connected_trace_with_three_precise_passed_checks() throws Exception {
        try (LoanFixture fixture = new LoanFixture();
             PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            ParabankDeniedLoan.run(evidence, page, fixture.url());
            evidence.finish("completed");
            evidence.finish("completed");
        }
        ReconstructedEvidence evidence = EvidenceReader.read(directory);
        assertEquals(8, evidence.actionExecutions().size());
        assertEquals(8, evidence.recordedTransitions().size());
        assertEquals(9, evidence.observations().size());
        assertEquals(3, evidence.oracleResults().size());
        assertEquals("completed", evidence.testRuns().get(0).reason());
        assertEquals(PlaywrightEvidence.VERSION, evidence.testRuns().get(0).testRun().properties().get("evidence.adapter_version").asText());
        for (var check : evidence.oracleResults()) {
            assertEquals(OracleStatus.PASSED, check.oracleStatus());
            assertTrue(check.oracleTarget() instanceof OracleTarget.WidgetProperty);
        }
        assertEquals("Denied", ((ObservedValue.Value) evidence.oracleResults().get(2).observedValue()).value().asText());
        for (Observation observation : evidence.observations()) {
            assertEquals(AvailableActionScope.UNKNOWN, observation.availableActionScope());
            assertEquals(1, observation.artifacts().size());
            assertTrue(Files.size(directory.resolve(observation.artifacts().get(0).path())) > 0);
            assertEquals(observation.recordedState().properties().get("playwright.document.title"),
                observation.recordedState().properties().get("ui.title"));
            assertTrue(observation.recordedState().properties().contains("evidence.capture"));
            for (Widget widget : observation.recordedState().widgets()) {
                if (widget.properties().contains("playwright.attribute.type")
                    && widget.properties().get("playwright.attribute.type").asText().equals("password")) {
                    assertFalse(widget.properties().contains("playwright.dom.value"));
                    assertFalse(widget.properties().contains("playwright.attribute.value"));
                }
            }
        }
        assertEquals(1, evidence.actionExecutions().stream().filter(execution ->
            execution.actionExecution().parameters().contains("playwright.redacted_parameters")).count());
        assertTrue(evidence.actionExecutions().stream().allMatch(execution -> execution.status() == ActionExecutionStatus.COMPLETED));
    }

    @Test
    void preserves_native_property_meanings_and_records_known_capture_omissions() throws Exception {
        page.setContent("""
            <title>Actual page title</title>
            <input id="name" value="original" title="Tooltip" aria-label="Accessible name"
                data-private="excluded" data-long="This value deliberately exceeds the configured whole-value limit">
            <img id="help" alt="" title="Image tooltip">
            <div id="plain" style="color: rgb(12, 34, 56)">  Original text  </div>
            """);
        page.locator("#name").fill("live");
        CaptureOptions options = new CaptureOptions(100, 32, 100_000, 100, List.of("display"),
            List.of("data-private"), true);
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page, options)) {
            evidence.observe();
        }
        Observation observation = EvidenceReader.read(directory).observations().get(0);
        Widget input = widget(observation, "name");
        assertEquals("original", input.properties().get("playwright.attribute.value").asText());
        assertEquals("live", input.properties().get("playwright.dom.value").asText());
        assertEquals("Tooltip", input.properties().get("playwright.attribute.title").asText());
        assertEquals("Accessible name", input.properties().get("playwright.attribute.aria-label").asText());
        assertFalse(input.properties().contains("ui.title"));
        assertFalse(input.properties().contains("ui.name"));
        assertFalse(input.properties().contains("ui.role"));
        assertFalse(input.properties().contains("playwright.attribute.data-private"));
        assertFalse(input.properties().contains("playwright.attribute.data-long"));
        assertTrue(input.properties().get("evidence.omitted_properties").toString().contains("excluded"));
        assertTrue(input.properties().get("evidence.omitted_properties").toString().contains("limit"));
        assertEquals("", widget(observation, "help").properties().get("ui.alt").asText());
        assertEquals("  Original text  ", widget(observation, "plain").properties().get("ui.text").asText());
        assertTrue(input.properties().contains("playwright.style.display"));
        assertFalse(input.properties().contains("playwright.style.color"));
        assertTrue(observation.recordedState().properties().get("evidence.capture").get("truncated").asBoolean());
    }

    @Test
    void failed_assertions_keep_their_actual_reading_and_original_failure() throws Exception {
        page.setContent("<p id='status'>Actual</p>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().timeoutMillis(100);
            assertThrows(AssertionError.class, () -> evidence.checks().hasText("example:text", "Expected text",
                page.locator("#status"), "Expected"));
        }
        ReconstructedEvidence evidence = EvidenceReader.read(directory);
        assertEquals(OracleStatus.FAILED, evidence.oracleResults().get(0).oracleStatus());
        assertEquals("Actual", ((ObservedValue.Value) evidence.oracleResults().get(0).observedValue()).value().asText());
        assertEquals("check_failed", evidence.testRuns().get(0).reason());
        assertTrue(evidence.oracleResults().get(0).oracleDetails().contains("playwright.assertion_error"));
    }

    @Test
    void failed_browser_actions_preserve_rejection_without_creating_a_result_link() throws Exception {
        page.setContent("<p>Ready</p>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            assertThrows(PlaywrightException.class, () -> evidence.click(page.locator("#missing")));
        }
        ReconstructedEvidence evidence = EvidenceReader.read(directory);
        assertEquals(ActionExecutionStatus.FAILED, evidence.actionExecutions().get(0).status());
        assertTrue(evidence.actionExecutions().get(0).executionResult().error().contains("missing"));
        assertTrue(evidence.recordedTransitions().isEmpty());
        assertEquals("action_failed", evidence.testRuns().get(0).reason());
    }

    @Test
    void custom_oracles_keep_errors_unknown_readings_and_explicit_null_expectations() throws Exception {
        page.setContent("<p id='status'>Ready</p>");
        IllegalStateException original = new IllegalStateException("Oracle API unavailable");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            RuntimeException reported = assertThrows(RuntimeException.class, () -> evidence.checks().evaluate(
                "Custom oracle", new Oracle("example:custom", "7", null), page.locator("#status"), "custom.value",
                ExpectedValue.of(null), () -> { throw original; }, () -> { throw new IllegalArgumentException("No reading"); }));
            assertSame(original, reported);
        }
        var check = EvidenceReader.read(directory).oracleResults().get(0);
        assertEquals(OracleStatus.ERROR, check.oracleStatus());
        assertEquals(new ObservedValue.Unknown(), check.observedValue());
        assertTrue(check.expectedValue().supplied());
        assertTrue(check.expectedValue().value().isNull());
        assertEquals("7", check.oracle().version());
    }

    @Test
    void excluded_widgets_keep_evaluations_without_claiming_property_coverage() throws Exception {
        page.setContent("<p id='status'>Ready</p>");
        CaptureOptions options = new CaptureOptions(1, 100, 1_000, 10, List.of(), List.of(), false);
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page, options)) {
            evidence.checks().hasText("example:ready", "Ready", page.locator("#status"), "Ready");
        }
        ReconstructedEvidence evidence = EvidenceReader.read(directory);
        assertTrue(evidence.oracleResults().get(0).oracleTarget() instanceof OracleTarget.Observation);
        assertTrue(evidence.oracleResults().get(0).oracleDetails().contains("playwright.requested_target"));
        assertEquals(1, evidence.observations().get(0).recordedState().widgets().size());
        assertTrue(evidence.observations().get(0).artifacts().isEmpty());
    }

    @Test
    void assertion_retries_produce_one_final_evaluation() throws Exception {
        page.setContent("<p id='status'>Loading</p><script>setTimeout(() => document.getElementById('status').textContent='Ready', 150)</script>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().hasText("example:ready", "Ready", page.locator("#status"), "Ready");
        }
        ReconstructedEvidence evidence = EvidenceReader.read(directory);
        assertEquals(1, evidence.oracleResults().size());
        assertEquals(OracleStatus.PASSED, evidence.oracleResults().get(0).oracleStatus());
        assertEquals("Ready", ((ObservedValue.Value) evidence.oracleResults().get(0).observedValue()).value().asText());
    }

    @Test
    void consecutive_checks_preserve_distinct_visits_without_inventing_actions() throws Exception {
        page.setContent("<p id='status' title='Tooltip'>Ready</p>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().hasText("example:text", "Text", page.locator("#status"), "Ready");
            evidence.checks().hasAttribute("example:title", "Title", page.locator("#status"), "title", "Tooltip");
        }
        ReconstructedEvidence evidence = EvidenceReader.read(directory);
        assertEquals(2, evidence.observations().size());
        assertEquals(2, evidence.oracleResults().size());
        assertNotEquals(evidence.oracleResults().get(0).oracleTarget(), evidence.oracleResults().get(1).oracleTarget());
        assertTrue(evidence.actionExecutions().isEmpty());
        assertTrue(evidence.recordedTransitions().isEmpty());
    }

    @Test
    void custom_action_parameters_and_nulls_survive_reopening() throws Exception {
        page.setContent("<button id='button'>Continue</button>");
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("nested", Map.of("number", 9, "values", List.of("a", "b")));
        parameters.put("optional", null);
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.perform("custom", "Custom action", page.locator("#button"), parameters,
                () -> page.locator("#button").click());
        }
        var execution = EvidenceReader.read(directory).actionExecutions().get(0);
        assertEquals(ActionExecutionStatus.COMPLETED, execution.status());
        assertEquals(9, execution.actionExecution().parameters().get("nested").get("number").asInt());
        assertTrue(execution.actionExecution().parameters().get("optional").isNull());
    }

    @Test
    void existing_evidence_is_preserved_and_finished_sessions_reject_more_actions() throws Exception {
        page.setContent("<p>Ready</p>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.finish("completed");
            assertThrows(IllegalStateException.class, () -> evidence.navigate("about:blank"));
        }
        byte[] original = Files.readAllBytes(directory.resolve("evidence.jsonl"));
        assertThrows(java.io.IOException.class, () -> PlaywrightEvidence.start(directory, page));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(directory.resolve("evidence.jsonl"))));
    }

    @Test
    void absent_attribute_readings_remain_different_from_capture_omissions() throws Exception {
        page.setContent("<img id='help'>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().timeoutMillis(100);
            assertThrows(AssertionError.class, () -> evidence.checks().hasAttribute("example:alt", "Image description",
                page.locator("#help"), "alt", "Help"));
        }
        var check = EvidenceReader.read(directory).oracleResults().get(0);
        assertEquals(OracleStatus.FAILED, check.oracleStatus());
        assertEquals(new ObservedValue.Absent(), check.observedValue());
        assertEquals("playwright.attribute.alt", ((OracleTarget.WidgetProperty) check.oracleTarget()).property());
    }

    @Test
    void open_shadow_widgets_bind_while_frame_targets_keep_an_explicit_resolution_limit() throws Exception {
        page.setContent("<div id='host'></div><iframe id='frame' srcdoc=\"<p id='inside'>Ready</p>\"></iframe>");
        page.locator("#host").evaluate("host => host.attachShadow({mode:'open'}).innerHTML='<button id=shadow-button>Shadow</button>'");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().hasText("example:shadow", "Shadow button", page.locator("#shadow-button"), "Shadow");
            evidence.checks().hasText("example:frame", "Frame text", page.frameLocator("#frame").locator("#inside"), "Ready");
        }
        ReconstructedEvidence evidence = EvidenceReader.read(directory);
        assertTrue(evidence.oracleResults().get(0).oracleTarget() instanceof OracleTarget.WidgetProperty);
        assertTrue(evidence.oracleResults().get(1).oracleTarget() instanceof OracleTarget.Observation);
        assertTrue(evidence.oracleResults().get(1).oracleDetails().contains("playwright.target_resolution"));
        assertEquals(1, evidence.observations().get(0).recordedState().properties()
            .get("playwright.capture.omitted_frame_documents").asInt());
    }

    @Test
    void reported_available_actions_bind_native_widgets_and_existing_oracle_results() throws Exception {
        page.setContent("<input id='name'><button id='other'>Other</button>");
        AvailableAction fill = new AvailableAction("fill", "Enter name", page.locator("#name"), Map.of("strategy", "example"));
        AvailableAction other = new AvailableAction("click", "Other choice", page.locator("#other"));
        String source;
        try (PlaywrightEvidence recorder = PlaywrightEvidence.start(directory, page)) {
            source = recorder.recordAvailableActions(List.of(fill, other), AvailableActionScope.ELIGIBLE);
            recorder.perform(fill.actionType(), fill.name(), fill.locator(), Map.of("value", "john"),
                () -> fill.locator().fill("john"), fill);
            recorder.capture();
            recorder.checks().recordResult(new Oracle("example:name", "1", org.testar.evidence.Properties.empty()),
                "Name was checked", OracleStatus.PASSED, page.locator("#name"), "playwright.dom.value",
                ExpectedValue.of("john"), new ObservedValue.Value(org.testar.evidence.EvidenceJson.tree(page.locator("#name").inputValue())),
                null, org.testar.evidence.Properties.empty(), new OracleSeverity("informational", null));
        }
        var evidence = EvidenceReader.read(directory);
        assertEquals(2, evidence.availableActions(source).size());
        assertEquals(1, evidence.actionExecutions().size());
        assertEquals(1, evidence.recordedTransitions().size());
        assertEquals("john", evidence.actionExecutions().get(0).actionExecution().parameters().get("value").asText());
        assertTrue(evidence.oracleResults().get(0).oracleTarget() instanceof OracleTarget.WidgetProperty);
        assertEquals("informational", evidence.oracleResults().get(0).oracleSeverity().label());
    }

    private static Widget widget(Observation observation, String id) {
        return observation.recordedState().widgets().stream()
            .filter(widget -> widget.properties().contains("playwright.attribute.id")
                && id.equals(widget.properties().get("playwright.attribute.id").asText()))
            .findFirst().orElseThrow();
    }
}
