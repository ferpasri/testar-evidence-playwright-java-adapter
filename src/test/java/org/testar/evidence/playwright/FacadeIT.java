package org.testar.evidence.playwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.testar.evidence.playwright.EvidenceAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testar.evidence.ObservedValue;
import org.testar.evidence.Oracle;
import org.testar.evidence.OracleStatus;
import org.testar.evidence.OracleTarget;
import org.testar.evidence.EvidenceReader;
import org.testar.evidence.ActionExecutionStatus;
import org.testar.evidence.ExpectedValue;
import org.testar.evidence.ReconstructedEvidence;

class FacadeIT {
    @TempDir
    Path directory;
    private Playwright playwright;
    private Browser browser;
    private Page page;

    @BeforeEach
    void launch() {
        CaptureOptions defaults = CaptureOptions.defaults();
        CaptureOptions capture = new CaptureOptions(defaults.maxWidgets(), defaults.maxValueCharacters(),
            defaults.maxTotalCharacters(), defaults.maxAttributesPerWidget(), defaults.computedStyles(),
            defaults.excludedAttributes(), false);
        playwright = EvidencePlaywright.create(new EvidenceOptions(directory, "local-app", "Custom Java runner",
            "Inspect a loan request", capture), new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("playwright.channel");
        if (channel != null) {
            options.setChannel(channel);
        }
        browser = playwright.chromium().launch(options);
        page = browser.newPage();
        page.setDefaultTimeout(5_000);
    }

    @AfterEach
    void close() {
        playwright.close();
    }

    @Test
    void ordinary_script_records_navigation_actions_and_final_assertion_outcomes() throws Exception {
        try (LoanFixture fixture = new LoanFixture()) {
            assertNotNull(page.navigate(fixture.url()));
            page.locator("input[name=username]").fill("john");
            page.locator("input[name=password]").fill("demo");
            page.locator("input[value='Log In']").click();
            assertThat(page.locator("h2")).hasText("Account Services");
            page.getByText("Request Loan").click();
            assertThat(page.locator("h1")).hasText("Apply for a Loan");
            page.locator("#amount").fill("999999");
            page.locator("#downPayment").fill("1");
            page.locator("input[value='Apply Now']").click();
            assertThat(page.locator("#loanStatus")).hasText("Denied");
            page.close();
        }
        ReconstructedEvidence evidence = evidence();
        assertEquals(8, evidence.actionExecutions().size());
        assertEquals(3, evidence.oracleResults().size());
        assertTrue(evidence.actionExecutions().stream().allMatch(value -> value.status() == ActionExecutionStatus.COMPLETED));
        assertTrue(evidence.oracleResults().stream().allMatch(value -> value.oracleStatus() == OracleStatus.PASSED));
        var properties = evidence.testRuns().get(0).testRun().properties();
        assertEquals("Custom Java runner", properties.get("evidence.origin").asText());
        assertEquals("Inspect a loan request", properties.get("evidence.test_objective").asText());
        assertEquals(PlaywrightEvidence.ADAPTER_ID, properties.get("evidence.adapter").asText());
        assertTrue(evidence.oracleResults().get(0).oracleTarget() instanceof OracleTarget.WidgetProperty);
    }

    @Test
    void native_retries_and_negative_assertions_produce_one_result_each() throws Exception {
        page.setContent("<p>Waiting</p>");
        page.evaluate("setTimeout(() => document.querySelector('p').textContent='Ready', 100)");
        assertThat(page.locator("p")).hasText("Ready");
        assertThat(page.locator("p")).not().hasText("Waiting");
        page.close();
        assertEquals(2, evidence().oracleResults().size());
        assertTrue(evidence().oracleResults().stream().allMatch(value -> value.oracleStatus() == OracleStatus.PASSED));
    }

    @Test
    void failed_assertion_is_rethrown_and_stored_with_final_reading() throws Exception {
        page.setContent("<p>Actual</p>");
        assertThrows(AssertionError.class, () -> assertThat(page.locator("p")).hasText("Expected",
            new LocatorAssertions.HasTextOptions().setTimeout(100)));
        page.close();
        var check = evidence().oracleResults().get(0);
        assertEquals(OracleStatus.FAILED, check.oracleStatus());
        assertEquals(ExpectedValue.of("Expected"), check.expectedValue());
        assertEquals(new ObservedValue.Value(org.testar.evidence.EvidenceJson.tree("Actual")), check.observedValue());
    }

    @Test
    void native_failure_keeps_its_exception_and_has_no_result_observation() throws Exception {
        page.setContent("<p>Ready</p>");
        assertThrows(PlaywrightException.class, () -> page.locator("#missing").click());
        page.close();
        assertEquals(ActionExecutionStatus.FAILED, evidence().actionExecutions().get(1).status());
    }

    @Test
    void locator_arguments_options_and_native_return_values_are_preserved() throws Exception {
        page.setContent("<section><button>Choose</button></section><select><option value='a'>A</option></select>");
        Locator button = page.locator("button");
        Locator section = page.locator("section").filter(new Locator.FilterOptions().setHas(button));
        section.locator(button).and(button).click();
        assertEquals(List.of("a"), page.locator("select").selectOption("a"));
        assertSame(page, button.page());
        assertSame(browser, page.context().browser());
        assertThat(section).hasCount(1);
        page.close();
        assertEquals(3, evidence().actionExecutions().size());
        assertTrue(evidence().oracleResults().get(0).oracleTarget() instanceof OracleTarget.Observation);
    }

    @Test
    void keyboard_mouse_and_element_handles_delegate_once() throws Exception {
        page.setContent("<input><button onclick='this.textContent=Number(this.textContent)+1'>0</button>");
        page.locator("input").focus();
        page.keyboard().insertText("typed");
        page.locator("button").elementHandle().click();
        var bounds = page.locator("button").boundingBox();
        page.mouse().click(bounds.x + 2, bounds.y + 2);
        assertEquals("2", page.locator("button").textContent());
        assertEquals("typed", page.locator("input").inputValue());
        page.close();
        assertEquals(5, evidence().actionExecutions().size());
    }

    @Test
    void trial_clicks_and_reads_do_not_add_executions() throws Exception {
        page.setContent("<button>Ready</button>");
        page.locator("button").click(new Locator.ClickOptions().setTrial(true));
        page.locator("button").textContent();
        page.evaluate("document.title='Read'");
        page.close();
        assertEquals(1, evidence().actionExecutions().size());
    }

    @Test
    void callbacks_receive_facade_objects_and_can_be_removed() throws Exception {
        page.setContent("<p>Ready</p>");
        AtomicInteger callbacks = new AtomicInteger();
        Consumer<Page> handler = popup -> {
            callbacks.incrementAndGet();
            popup.setContent("<p>Popup</p>");
        };
        page.onPopup(handler);
        Page popup = page.waitForPopup(() -> page.evaluate("window.open('about:blank')"));
        assertEquals("Popup", popup.locator("p").textContent());
        page.offPopup(handler);
        Page other = page.waitForPopup(() -> page.evaluate("window.open('about:blank')"));
        assertEquals(1, callbacks.get());
        popup.close();
        other.close();
        page.close();
        assertEquals(2, traceDirectories().size());
    }

    @Test
    void route_callbacks_and_frame_actions_keep_working() throws Exception {
        page.route("**/fixture", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
            .setContentType("text/html").setBody("<iframe srcdoc=\"<button>Inside</button>\"></iframe>")));
        page.navigate("http://local.test/fixture");
        page.frameLocator("iframe").locator("button").click();
        assertThat(page.frameLocator("iframe").locator("button")).hasText("Inside");
        page.close();
        assertEquals(2, evidence().actionExecutions().size());
        assertTrue(evidence().oracleResults().get(0).oracleTarget() instanceof OracleTarget.Observation);
    }

    @Test
    void custom_actions_and_assertions_suppress_automatic_duplicates() throws Exception {
        page.setContent("<button>Ready</button>");
        PlaywrightEvidence evidence = EvidencePlaywright.evidenceFor(page);
        evidence.perform("custom", "Choose Ready", page.locator("button"), Map.of(),
            () -> page.locator("button").click());
        evidence.checks().evaluate("Ready text", new Oracle("example:ready", "1", org.testar.evidence.Properties.of(Map.of())), page.locator("button"),
            "ui.text", ExpectedValue.of("Ready"), () -> assertThat(page.locator("button")).hasText("Ready"),
            () -> new ObservedValue.Value(org.testar.evidence.EvidenceJson.tree(page.locator("button").textContent())));
        page.close();
        assertEquals(2, evidence().actionExecutions().size());
        assertEquals(1, evidence().oracleResults().size());
        assertEquals("example:ready", evidence().oracleResults().get(0).oracle().id());
    }

    @Test
    void separate_pages_get_unique_completed_traces_on_browser_close() throws Exception {
        page.setContent("<p>First</p>");
        Page second = browser.newPage();
        second.setContent("<p>Second</p>");
        browser.close();
        assertEquals(2, traceDirectories().size());
        for (Path path : traceDirectories()) {
            assertTrue(path.getFileName().toString().startsWith("local-app_Inspect-a-loan-request_trace-"));
            assertEquals(ActionExecutionStatus.COMPLETED, EvidenceReader.read(path).actionExecutions().get(0).status());
        }
    }

    @Test
    void external_page_close_flushes_cached_result_without_capturing_closed_page() throws Exception {
        page.setContent("<p>Ready</p>");
        ((Page) FacadeRuntime.unwrap(page)).close();
        assertEquals(ActionExecutionStatus.COMPLETED, evidence().actionExecutions().get(0).status());
    }

    @Test
    void password_values_and_native_failure_details_are_redacted_from_evidence() throws Exception {
        String secret = "test-password-4939";
        page.setContent("<input type=password>");
        Locator password = page.locator("input");
        password.fill(secret);
        assertThat(password).hasValue(secret);
        assertThrows(AssertionError.class, () -> assertThat(password).hasValue("other-secret-3862",
            new LocatorAssertions.HasValueOptions().setTimeout(100)));
        password.evaluate("e => e.disabled=true");
        PlaywrightException failure = assertThrows(PlaywrightException.class, () -> password.fill("third-secret-7823"));
        assertTrue(failure.getMessage().contains("third-secret-7823"));
        page.close();
        String saved = Files.readString(traceDirectories().get(0).resolve("evidence.jsonl"));
        assertFalse(saved.contains(secret));
        assertFalse(saved.contains("other-secret-3862"));
        assertFalse(saved.contains("third-secret-7823"));
        assertTrue(evidence().oracleResults().get(0).observedValue() instanceof ObservedValue.Unknown);
    }

    @Test
    void page_property_assertions_and_missing_attributes_have_concrete_targets() throws Exception {
        page.setContent("<title>Title</title><img>");
        assertThat(page).hasTitle("Title");
        assertThat(page).hasURL("about:blank");
        assertThrows(AssertionError.class, () -> assertThat(page.locator("img")).hasAttribute("alt", "Help",
            new LocatorAssertions.HasAttributeOptions().setTimeout(100)));
        page.close();
        assertEquals("ui.title", ((OracleTarget.StateProperty) evidence().oracleResults().get(0).oracleTarget()).property());
        assertEquals("ui.url", ((OracleTarget.StateProperty) evidence().oracleResults().get(1).oracleTarget()).property());
        assertTrue(evidence().oracleResults().get(2).observedValue() instanceof ObservedValue.Absent);
    }

    @Test
    void native_objects_outside_the_facade_are_used_without_recording() {
        Page nativePage = ((Browser) FacadeRuntime.unwrap(browser)).newPage();
        try {
            nativePage.setContent("<p>Native</p>");
            assertThat(nativePage.locator("p")).hasText("Native");
            assertThrows(IllegalArgumentException.class, () -> EvidencePlaywright.evidenceFor(nativePage));
            assertEquals(0, traceDirectories().size());
        } finally {
            nativePage.context().close();
        }
    }

    @Test
    void explicit_check_helpers_accept_facade_locators() throws Exception {
        page.setContent("<input value='john'><p title='Greeting'>Welcome</p>");
        PlaywrightChecks checks = EvidencePlaywright.evidenceFor(page).checks();
        checks.hasText("example:text", "Welcome text", page.locator("p"), "Welcome");
        checks.hasValue("example:value", "Username", page.locator("input"), "john");
        checks.hasAttribute("example:attribute", "Greeting title", page.locator("p"), "title", "Greeting");
        page.close();
        assertEquals(3, evidence().oracleResults().size());
        assertTrue(evidence().oracleResults().stream().allMatch(value -> value.oracleStatus() == OracleStatus.PASSED));
    }

    @Test
    void page_selector_actions_and_common_property_assertions_use_native_results() throws Exception {
        page.setContent("<input id='name' value='john'><input type=checkbox checked>"
            + "<select multiple><option value=a selected>A</option><option value=b selected>B</option></select>");
        page.fill("#name", "Jane");
        page.click("#name");
        assertThat(page.locator("#name")).hasValue("Jane");
        assertThat(page.locator("#name")).hasId("name");
        assertThat(page.locator("#name")).isEnabled();
        assertThat(page.locator("#name")).isFocused();
        assertThat(page.locator("input[type=checkbox]")).isChecked();
        assertThat(page.locator("select")).hasValues(new String[] {"a", "b"});
        page.close();
        assertEquals(3, evidence().actionExecutions().size());
        assertEquals(6, evidence().oracleResults().size());
        assertTrue(evidence().oracleResults().stream().allMatch(value -> value.oracleStatus() == OracleStatus.PASSED));
        assertTrue(evidence().oracleResults().stream().noneMatch(value -> value.observedValue() instanceof ObservedValue.Unknown));
        assertEquals("playwright.dom.selectedValues",
            ((OracleTarget.WidgetProperty) evidence().oracleResults().get(5).oracleTarget()).property());
    }

    @Test
    void facade_example_uses_the_same_recording_path() throws Exception {
        try (LoanFixture fixture = new LoanFixture()) {
            org.testar.evidence.playwright.examples.ParabankFacade.run(page, fixture.url());
            page.close();
        }
        assertEquals(8, evidence().actionExecutions().size());
        assertEquals(3, evidence().oracleResults().size());
    }

    @Test
    void omitted_run_descriptions_remain_absent_with_the_facade() throws Exception {
        Path output = directory.resolve("omitted");
        EvidenceOptions options = new EvidenceOptions(output, "application", null, null, CaptureOptions.defaults());
        try (Playwright other = EvidencePlaywright.create(options, new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser second = other.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                 .setChannel(System.getProperty("playwright.channel")))) {
            second.newPage().setContent("<p>Ready</p>");
        }
        try (var files = Files.list(output)) {
            var properties = EvidenceReader.read(files.findFirst().orElseThrow()).testRuns().get(0).testRun().properties();
            assertFalse(properties.contains("evidence.origin"));
            assertFalse(properties.contains("evidence.test_objective"));
            assertEquals("0.3.0", properties.get("evidence.adapter_version").asText());
        }
    }

    @Test
    void collection_counts_text_lists_and_absence_checks_retain_their_readings() throws Exception {
        page.setContent("<p>One</p><p>Two</p>");
        assertThat(page.locator("p")).hasCount(2);
        assertThat(page.locator("p")).hasText(new String[] {"One", "Two"});
        assertThat(page.locator("#missing")).hasCount(0);
        assertThat(page.locator("#missing")).not().isAttached();
        page.close();
        assertEquals(4, evidence().oracleResults().size());
        assertTrue(evidence().oracleResults().stream().noneMatch(value -> value.observedValue() instanceof ObservedValue.Unknown));
        assertTrue(evidence().oracleResults().get(0).oracleTarget() instanceof OracleTarget.Observation);
        assertTrue(evidence().oracleResults().get(1).oracleTarget() instanceof OracleTarget.Observation);
        assertEquals(new ObservedValue.Value(org.testar.evidence.EvidenceJson.tree(false)), evidence().oracleResults().get(3).observedValue());
    }

    @Test
    void recording_io_failures_are_clear_and_do_not_prevent_native_browser_cleanup() throws Exception {
        Path output = directory.resolve("write-failure");
        try (Playwright other = EvidencePlaywright.create(new EvidenceOptions(output, "application", null, null,
                CaptureOptions.defaults()), new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser second = other.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                .setChannel(System.getProperty("playwright.channel")));
            Page recorded = second.newPage();
            recorded.setContent("<button onclick='this.textContent=1'>0</button>");
            Path trace;
            try (var files = Files.list(output)) {
                trace = files.findFirst().orElseThrow();
            }
            Path artifacts = trace.resolve("artifacts");
            Path saved = trace.resolve("saved-artifacts");
            Files.move(artifacts, saved);
            Files.writeString(artifacts, "Simulate an unavailable artifact directory");
            try {
                assertThrows(java.io.UncheckedIOException.class, () -> recorded.locator("button").click());
                assertEquals("0", recorded.locator("button").textContent());
                assertThrows(java.io.UncheckedIOException.class, second::close);
                assertFalse(second.isConnected());
                assertTrue(recorded.isClosed());
            } finally {
                Files.delete(artifacts);
                Files.move(saved, artifacts);
                second.close();
            }
            assertFalse(EvidenceReader.read(trace).observations().isEmpty());
        }
    }

    private List<Path> traceDirectories() {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isDirectory).toList();
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private ReconstructedEvidence evidence() throws Exception {
        return EvidenceReader.read(traceDirectories().get(0));
    }
}
