package org.testar.evidence.playwright;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.ExpectedValue;
import org.testar.evidence.ObservedValue;
import org.testar.evidence.Oracle;
import org.testar.evidence.OracleSeverity;
import org.testar.evidence.OracleStatus;
import org.testar.evidence.Properties;

public final class PlaywrightChecks {
    private final PlaywrightEvidence evidence;
    private double timeoutMillis = 5_000;
    private Throwable recordingError;

    PlaywrightChecks(PlaywrightEvidence evidence) {
        this.evidence = evidence;
    }

    public Throwable recordingError() {
        return recordingError;
    }

    public PlaywrightChecks timeoutMillis(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("Assertion timeout must be positive and finite");
        }
        timeoutMillis = value;
        return this;
    }

    public void hasText(String oracleId, String name, Locator locator, String expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        evaluate(name, new Oracle(oracleId, "1", Properties.of(Map.of(
            "comparison", "playwright.hasText", "timeout_millis", timeoutMillis))),
            locator, "playwright.dom.textContent", ExpectedValue.of(expected),
            () -> assertThat((Locator) FacadeRuntime.unwrap(locator)).hasText(expected,
                new LocatorAssertions.HasTextOptions().setTimeout(timeoutMillis)),
            () -> value(locator.textContent()));
    }

    public void hasValue(String oracleId, String name, Locator locator, String expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        evaluate(name, new Oracle(oracleId, "1", Properties.of(Map.of(
            "comparison", "playwright.hasValue", "timeout_millis", timeoutMillis))),
            locator, "playwright.dom.value", ExpectedValue.of(expected),
            () -> assertThat((Locator) FacadeRuntime.unwrap(locator)).hasValue(expected,
                new LocatorAssertions.HasValueOptions().setTimeout(timeoutMillis)),
            () -> value(locator.inputValue()));
    }

    public void hasAttribute(String oracleId, String name, Locator locator, String attribute, String expected) throws IOException {
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(expected, "expected");
        evaluate(name, new Oracle(oracleId, "1", Properties.of(Map.of(
            "comparison", "playwright.hasAttribute", "attribute", attribute, "timeout_millis", timeoutMillis))),
            locator, "playwright.attribute." + attribute, ExpectedValue.of(expected),
            () -> assertThat((Locator) FacadeRuntime.unwrap(locator)).hasAttribute(attribute, expected,
                new LocatorAssertions.HasAttributeOptions().setTimeout(timeoutMillis)),
            () -> {
                String actual = locator.getAttribute(attribute);
                return actual == null ? new ObservedValue.Absent() : value(actual);
            });
    }

    public void evaluate(String name, Oracle oracle, Locator locator, String property, ExpectedValue expected,
                         Runnable assertion, Supplier<ObservedValue> observedValue) throws IOException {
        evaluate(name, oracle, locator, property, expected, null, assertion, observedValue);
    }

    public void evaluate(String name, Oracle oracle, Locator locator, String property, ExpectedValue expected,
                         OracleSeverity severity, Runnable assertion, Supplier<ObservedValue> observedValue) throws IOException {
        evaluateScoped(name, oracle, (Locator) FacadeRuntime.unwrap(locator), property, expected,
            severity, assertion, observedValue, false);
    }

    void evaluateAutomatic(String name, Oracle oracle, Locator locator, String property, ExpectedValue expected,
                           Runnable assertion, Supplier<ObservedValue> observedValue, boolean redactError) throws IOException {
        evaluateScoped(name, oracle, locator, property, expected, null, assertion, observedValue, redactError);
    }

    private void evaluateScoped(String name, Oracle oracle, Locator locator, String property, ExpectedValue expected,
                                OracleSeverity severity, Runnable assertion, Supplier<ObservedValue> observedValue,
                                boolean redactError) throws IOException {
        recordingError = null;
        validate(name, oracle, expected, severity);
        Objects.requireNonNull(assertion, "assertion");
        Objects.requireNonNull(observedValue, "observedValue");
        evidence.inScope(() -> evaluateInternal(name, oracle, locator, property, expected,
            severity, assertion, observedValue, redactError));
    }

    /** Record a result already obtained by the caller, without executing its test oracle again. */
    public void recordResult(Oracle oracle, String name, OracleStatus status) throws IOException {
        recordResult(oracle, name, status, null, Properties.empty(), null);
    }

    public void recordResult(Oracle oracle, String name, OracleStatus status, String error,
                             Properties oracleDetails, OracleSeverity severity) throws IOException {
        recordResult(oracle, name, status, null, null, ExpectedValue.unspecified(),
            new ObservedValue.Unknown(), error, oracleDetails, severity);
    }

    public void recordResult(Oracle oracle, String name, OracleStatus status, Locator locator, String property,
                             ExpectedValue expected, ObservedValue observedValue, String error,
                             Properties oracleDetails, OracleSeverity severity) throws IOException {
        recordingError = null;
        validate(name, oracle, expected, severity);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedValue, "observedValue");
        Map<String, Object> details = new LinkedHashMap<>();
        Objects.requireNonNull(oracleDetails, "oracleDetails").asJson().properties()
            .forEach(entry -> details.put(entry.getKey(), entry.getValue()));
        if (error != null) {
            details.put("playwright.assertion_error", error);
        }
        try {
            evidence.inScope(() -> evidence.recordOracleResult(name, oracle, locator, property,
                expected, status, observedValue, details, severity, true));
        } catch (IOException | RuntimeException recording) {
            recordingError = recording;
            throw recording;
        }
    }

    private void validate(String name, Oracle oracle, ExpectedValue expected, OracleSeverity severity) {
        evidence.requireActive();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Oracle result name must be nonblank");
        }
        Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(expected, "expected");
        if (severity != null && (severity.label() == null || severity.label().isBlank()
            || severity.label().codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Oracle severity label must be nonblank text without control characters");
        }
    }

    private void evaluateInternal(String name, Oracle oracle, Locator locator, String property, ExpectedValue expected,
                                  OracleSeverity severity, Runnable assertion, Supplier<ObservedValue> reading,
                                  boolean redactError) throws IOException {
        OracleStatus status = OracleStatus.PASSED;
        Throwable failure = null;
        try {
            assertion.run();
        } catch (AssertionError error) {
            status = OracleStatus.FAILED;
            failure = error;
        } catch (RuntimeException error) {
            status = OracleStatus.ERROR;
            failure = error;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (failure != null) {
            details.put("playwright.assertion_error", redactError ? "Assertion failed; sensitive details withheld" : failure.toString());
        }
        ObservedValue actual;
        try {
            actual = Objects.requireNonNull(reading.get(), "observed value");
        } catch (RuntimeException error) {
            actual = new ObservedValue.Unknown();
            details.put("playwright.reading_error", redactError ? "Sensitive reading unavailable" : error.toString());
        }
        try {
            evidence.recordOracleResult(name, oracle, locator, property, expected, status, actual, details, severity, false);
        } catch (IOException | RuntimeException recording) {
            recordingError = recording;
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

    private static ObservedValue value(Object value) {
        return new ObservedValue.Value(EvidenceJson.tree(value));
    }
}
