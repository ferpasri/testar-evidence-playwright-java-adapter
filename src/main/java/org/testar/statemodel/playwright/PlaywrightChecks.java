package org.testar.statemodel.playwright;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.testar.statemodel.evidence.CheckReading;
import org.testar.statemodel.evidence.CheckRule;
import org.testar.statemodel.evidence.CheckStatus;
import org.testar.statemodel.evidence.EvidenceJson;
import org.testar.statemodel.evidence.ExpectedValue;
import org.testar.statemodel.evidence.Properties;

public final class PlaywrightChecks {
    private final PlaywrightEvidence evidence;
    private double timeoutMillis = 5_000;

    PlaywrightChecks(PlaywrightEvidence evidence) {
        this.evidence = evidence;
    }

    public PlaywrightChecks timeoutMillis(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("Assertion timeout must be positive and finite");
        }
        timeoutMillis = value;
        return this;
    }

    public void hasText(String ruleId, String name, Locator locator, String expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        evaluate(name, new CheckRule(ruleId, "1", Properties.of(Map.of(
            "comparison", "playwright.hasText", "timeout_millis", timeoutMillis))),
            locator, "playwright.dom.textContent", ExpectedValue.of(expected),
            () -> assertThat(locator).hasText(expected, new LocatorAssertions.HasTextOptions().setTimeout(timeoutMillis)),
            () -> value(locator.textContent()));
    }

    public void hasValue(String ruleId, String name, Locator locator, String expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        evaluate(name, new CheckRule(ruleId, "1", Properties.of(Map.of(
            "comparison", "playwright.hasValue", "timeout_millis", timeoutMillis))),
            locator, "playwright.dom.value", ExpectedValue.of(expected),
            () -> assertThat(locator).hasValue(expected, new LocatorAssertions.HasValueOptions().setTimeout(timeoutMillis)),
            () -> value(locator.inputValue()));
    }

    public void hasAttribute(String ruleId, String name, Locator locator, String attribute, String expected) throws IOException {
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(expected, "expected");
        evaluate(name, new CheckRule(ruleId, "1", Properties.of(Map.of(
            "comparison", "playwright.hasAttribute", "attribute", attribute, "timeout_millis", timeoutMillis))),
            locator, "playwright.attribute." + attribute, ExpectedValue.of(expected),
            () -> assertThat(locator).hasAttribute(attribute, expected,
                new LocatorAssertions.HasAttributeOptions().setTimeout(timeoutMillis)),
            () -> {
                String actual = locator.getAttribute(attribute);
                return actual == null ? new CheckReading.Absent() : value(actual);
            });
    }

    /** Wraps one existing assertion; its retry behavior and thrown failure remain with that assertion. */
    public void evaluate(String name, CheckRule rule, Locator locator, String property, ExpectedValue expected,
                         Runnable assertion, Supplier<CheckReading> reading) throws IOException {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(assertion, "assertion");
        Objects.requireNonNull(reading, "reading");
        CheckStatus status = CheckStatus.PASSED;
        Throwable failure = null;
        try {
            assertion.run();
        } catch (AssertionError error) {
            status = CheckStatus.FAILED;
            failure = error;
        } catch (RuntimeException error) {
            status = CheckStatus.ERROR;
            failure = error;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (failure != null) {
            details.put("playwright.assertion_error", failure.toString());
        }
        CheckReading actual;
        try {
            actual = Objects.requireNonNull(reading.get(), "actual reading");
        } catch (RuntimeException error) {
            actual = new CheckReading.Unknown();
            details.put("playwright.reading_error", error.toString());
        }
        try {
            evidence.recordCheck(name, rule, locator, property, expected, status, actual, details);
        } catch (IOException | RuntimeException recording) {
            if (failure == null) {
                throw recording;
            }
            failure.addSuppressed(recording);
        }
        if (failure instanceof AssertionError assertionFailure) {
            throw assertionFailure;
        }
        if (failure instanceof RuntimeException executionFailure) {
            throw executionFailure;
        }
    }

    private static CheckReading value(Object value) {
        return new CheckReading.Value(EvidenceJson.tree(value));
    }
}
