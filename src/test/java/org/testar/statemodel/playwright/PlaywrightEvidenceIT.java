package org.testar.statemodel.playwright;

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
import org.testar.statemodel.evidence.CandidateScope;
import org.testar.statemodel.evidence.CheckReading;
import org.testar.statemodel.evidence.CheckRule;
import org.testar.statemodel.evidence.CheckStatus;
import org.testar.statemodel.evidence.CheckTarget;
import org.testar.statemodel.evidence.EvidenceReader;
import org.testar.statemodel.evidence.ExecutionStatus;
import org.testar.statemodel.evidence.ExpectedValue;
import org.testar.statemodel.evidence.Observation;
import org.testar.statemodel.evidence.StateModel;
import org.testar.statemodel.evidence.Widget;
import org.testar.statemodel.playwright.examples.ParabankDeniedLoan;

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
        page.setDefaultTimeout(500);
    }

    @AfterEach
    void closePage() {
        page.context().close();
    }

    @Test
    void loan_denial_script_produces_a_connected_trace_with_three_precise_passed_checks() throws Exception {
        try (LoanFixture fixture = new LoanFixture();
             PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            ParabankDeniedLoan.run(evidence, page, fixture.url());
            evidence.finish("completed");
            evidence.finish("completed");
        }
        StateModel model = EvidenceReader.read(directory);
        assertEquals(8, model.executions().size());
        assertEquals(8, model.links().size());
        assertEquals(9, model.observations().size());
        assertEquals(3, model.checks().size());
        assertEquals("completed", model.runs().get(0).reason());
        assertEquals("0.2.1", model.runs().get(0).run().properties().get("playwright.adapter_version").asText());
        for (var check : model.checks()) {
            assertEquals(CheckStatus.PASSED, check.status());
            assertTrue(check.target() instanceof CheckTarget.WidgetProperty);
        }
        assertEquals("Denied", ((CheckReading.Value) model.checks().get(2).actual()).value().asText());
        for (Observation observation : model.observations()) {
            assertEquals(CandidateScope.UNKNOWN, observation.candidateScope());
            assertEquals(1, observation.artifacts().size());
            assertTrue(Files.size(directory.resolve(observation.artifacts().get(0).path())) > 0);
            assertEquals(observation.state().properties().get("playwright.document.title"),
                observation.state().properties().get("ui.title"));
            assertTrue(observation.state().properties().contains("evidence.capture"));
            for (Widget widget : observation.state().widgets()) {
                if (widget.properties().contains("playwright.attribute.type")
                    && widget.properties().get("playwright.attribute.type").asText().equals("password")) {
                    assertFalse(widget.properties().contains("playwright.dom.value"));
                    assertFalse(widget.properties().contains("playwright.attribute.value"));
                }
            }
        }
        assertEquals(1, model.executions().stream().filter(execution ->
            execution.execution().parameters().contains("playwright.redacted_parameters")).count());
        assertTrue(model.executions().stream().allMatch(execution -> execution.status() == ExecutionStatus.OBSERVED));
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
        assertTrue(observation.state().properties().get("evidence.capture").get("truncated").asBoolean());
    }

    @Test
    void failed_assertions_keep_their_actual_reading_and_original_failure() throws Exception {
        page.setContent("<p id='status'>Actual</p>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().timeoutMillis(100);
            assertThrows(AssertionError.class, () -> evidence.checks().hasText("example:text", "Expected text",
                page.locator("#status"), "Expected"));
        }
        StateModel model = EvidenceReader.read(directory);
        assertEquals(CheckStatus.FAILED, model.checks().get(0).status());
        assertEquals("Actual", ((CheckReading.Value) model.checks().get(0).actual()).value().asText());
        assertEquals("check_failed", model.runs().get(0).reason());
        assertTrue(model.checks().get(0).details().contains("playwright.assertion_error"));
    }

    @Test
    void failed_browser_actions_preserve_rejection_without_creating_a_result_link() throws Exception {
        page.setContent("<p>Ready</p>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            assertThrows(PlaywrightException.class, () -> evidence.click(page.locator("#missing")));
        }
        StateModel model = EvidenceReader.read(directory);
        assertEquals(ExecutionStatus.REJECTED, model.executions().get(0).status());
        assertTrue(model.executions().get(0).error().contains("missing"));
        assertTrue(model.links().isEmpty());
        assertEquals("action_failed", model.runs().get(0).reason());
    }

    @Test
    void custom_oracles_keep_errors_unknown_readings_and_explicit_null_expectations() throws Exception {
        page.setContent("<p id='status'>Ready</p>");
        IllegalStateException original = new IllegalStateException("Oracle API unavailable");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            RuntimeException reported = assertThrows(RuntimeException.class, () -> evidence.checks().evaluate(
                "Custom oracle", new CheckRule("example:custom", "7", null), page.locator("#status"), "custom.value",
                ExpectedValue.of(null), () -> { throw original; }, () -> { throw new IllegalArgumentException("No reading"); }));
            assertSame(original, reported);
        }
        var check = EvidenceReader.read(directory).checks().get(0);
        assertEquals(CheckStatus.ERROR, check.status());
        assertEquals(new CheckReading.Unknown(), check.actual());
        assertTrue(check.expected().supplied());
        assertTrue(check.expected().value().isNull());
        assertEquals("7", check.rule().version());
    }

    @Test
    void excluded_widgets_keep_evaluations_without_claiming_property_coverage() throws Exception {
        page.setContent("<p id='status'>Ready</p>");
        CaptureOptions options = new CaptureOptions(1, 100, 1_000, 10, List.of(), List.of(), false);
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page, options)) {
            evidence.checks().hasText("example:ready", "Ready", page.locator("#status"), "Ready");
        }
        StateModel model = EvidenceReader.read(directory);
        assertTrue(model.checks().get(0).target() instanceof CheckTarget.Observation);
        assertTrue(model.checks().get(0).details().contains("playwright.requested_target"));
        assertEquals(1, model.observations().get(0).state().widgets().size());
        assertTrue(model.observations().get(0).artifacts().isEmpty());
    }

    @Test
    void assertion_retries_produce_one_final_evaluation() throws Exception {
        page.setContent("<p id='status'>Loading</p><script>setTimeout(() => document.getElementById('status').textContent='Ready', 150)</script>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().hasText("example:ready", "Ready", page.locator("#status"), "Ready");
        }
        StateModel model = EvidenceReader.read(directory);
        assertEquals(1, model.checks().size());
        assertEquals(CheckStatus.PASSED, model.checks().get(0).status());
        assertEquals("Ready", ((CheckReading.Value) model.checks().get(0).actual()).value().asText());
    }

    @Test
    void consecutive_checks_preserve_distinct_visits_without_inventing_actions() throws Exception {
        page.setContent("<p id='status' title='Tooltip'>Ready</p>");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().hasText("example:text", "Text", page.locator("#status"), "Ready");
            evidence.checks().hasAttribute("example:title", "Title", page.locator("#status"), "title", "Tooltip");
        }
        StateModel model = EvidenceReader.read(directory);
        assertEquals(2, model.observations().size());
        assertEquals(2, model.checks().size());
        assertNotEquals(model.checks().get(0).target(), model.checks().get(1).target());
        assertTrue(model.executions().isEmpty());
        assertTrue(model.links().isEmpty());
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
        var execution = EvidenceReader.read(directory).executions().get(0);
        assertEquals(ExecutionStatus.OBSERVED, execution.status());
        assertEquals(9, execution.execution().parameters().get("nested").get("number").asInt());
        assertTrue(execution.execution().parameters().get("optional").isNull());
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
        var check = EvidenceReader.read(directory).checks().get(0);
        assertEquals(CheckStatus.FAILED, check.status());
        assertEquals(new CheckReading.Absent(), check.actual());
        assertEquals("playwright.attribute.alt", ((CheckTarget.WidgetProperty) check.target()).property());
    }

    @Test
    void open_shadow_widgets_bind_while_frame_targets_keep_an_explicit_resolution_limit() throws Exception {
        page.setContent("<div id='host'></div><iframe id='frame' srcdoc=\"<p id='inside'>Ready</p>\"></iframe>");
        page.locator("#host").evaluate("host => host.attachShadow({mode:'open'}).innerHTML='<button id=shadow-button>Shadow</button>'");
        try (PlaywrightEvidence evidence = PlaywrightEvidence.start(directory, page)) {
            evidence.checks().hasText("example:shadow", "Shadow button", page.locator("#shadow-button"), "Shadow");
            evidence.checks().hasText("example:frame", "Frame text", page.frameLocator("#frame").locator("#inside"), "Ready");
        }
        StateModel model = EvidenceReader.read(directory);
        assertTrue(model.checks().get(0).target() instanceof CheckTarget.WidgetProperty);
        assertTrue(model.checks().get(1).target() instanceof CheckTarget.Observation);
        assertTrue(model.checks().get(1).details().contains("playwright.target_resolution"));
        assertEquals(1, model.observations().get(0).state().properties()
            .get("playwright.capture.omitted_frame_documents").asInt());
    }

    private static Widget widget(Observation observation, String id) {
        return observation.state().widgets().stream()
            .filter(widget -> widget.properties().contains("playwright.attribute.id")
                && id.equals(widget.properties().get("playwright.attribute.id").asText()))
            .findFirst().orElseThrow();
    }
}
