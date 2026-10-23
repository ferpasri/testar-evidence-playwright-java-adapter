# How the Playwright Java adapter works

The adapter sits between a Java script and Playwright. Playwright controls the browser; the adapter records the actions, captured pages, and final check results in the shared evidence format.

```text
Java script using normal Page and Locator calls
    -> EvidencePlaywright and EvidenceAssertions
    -> PlaywrightEvidence and PlaywrightChecks
    -> DomCapture and browser capture scripts
    -> testar-evidence-java: validation and portable files
```

## Starting with an existing script

Change `Playwright.create()` to `EvidencePlaywright.create()`. Change the static `assertThat` import to `org.testar.evidence.playwright.EvidenceAssertions.assertThat`. Keep the normal `com.microsoft.playwright` types and operation calls.

```java
page.locator("input[name='password']").fill("demo");
page.locator("input[value='Log In']").click();
assertThat(page.locator("#leftPanel h2")).hasText("Account Services");
```

The facade delegates the fill and click once, records their results, and lets Playwright retry the text assertion normally. It then records one final assertion outcome. Native Playwright objects created outside the facade keep their ordinary behavior.

The [complete facade example](../src/test/java/org/testar/evidence/playwright/examples/ParabankFacade.java) includes browser setup and the loan-denial script. The [explicit example](../src/test/java/org/testar/evidence/playwright/examples/ParabankDeniedLoan.java) shows caller-defined recording boundaries.

## The classes

| Class or file | What it does |
| --- | --- |
| `EvidencePlaywright` | Creates the recording Playwright session and gives access to a page's recorder through `evidenceFor(page)`. |
| `EvidenceOptions` | Chooses the parent output directory, SUT label, optional origin and test objective, and capture settings. |
| `EvidenceAssertions` | Replaces the native assertion factory import so assertions can be recorded. |
| `FacadeRuntime` | Wraps objects returned by Playwright, forwards native calls and callbacks, and manages a separate trace for each used page. |
| `FacadeActions` | Identifies interactions, resolves their locator when available, and selects arguments to record or redact. |
| `FacadeAssertions` | Runs native assertions and connects the final status and available reading to an observation, widget, or property. |
| `FacadeValues` | Converts supported argument values and options into descriptive JSON data. |
| `PlaywrightEvidence` | Writes action selection, submission, observations, execution links, and the run ending. It also provides explicit action boundaries. |
| `PlaywrightChecks` | Records the outcome, expectation, reading, and target of an assertion or custom oracle. |
| `CaptureOptions` | Selects capture limits, computed styles, excluded attributes, and screenshots. |
| `DomCapture` / `BrowserSnapshot` | Read browser data and hold one captured page, its widgets, timestamp, and screenshot. |
| `capture.js` | Extracts the document, elements, properties, hierarchy, and capture descriptions. |
| `target.js` | Finds a locator's structural DOM path so its element can be connected to a captured widget. |

The facade classes are internal helpers; scripts normally use `EvidencePlaywright`, `EvidenceAssertions`, and optionally `EvidenceOptions`. The imported Java evidence library validates the generic records and saves the manifest, JSONL log, and screenshot artifacts.

## Following an action and check

For **Apply Now**, followed by checking **Denied**:

1. The recorder uses its latest pending capture or captures the source page. The button's DOM path identifies its captured widget when available.
2. It records the selected click and delegates to native Playwright. A normal return records an accepted submission; an exception records a rejected submission and is rethrown.
3. After a successful action, the facade captures the resulting page. This completes the action's state-action-state path when the capture is written.
4. The native text assertion retries until it succeeds or fails. The adapter reads the final text, captures the page, and records the assertion's result and target.
5. The next action or page/session closure writes the pending records. A result capture can also be the next action's source.

The business response **Denied** passes this check because denial is the expected result. An accepted click alone does not establish that the response is correct.

A pending capture is a short-lived in-memory snapshot, distinct from a check whose status is `pending`. Separate captures remain separate visits, even if their contents match. Reads, waits, and script evaluation remain native operations without automatic action records. The script controls application readiness.

## Adding deeper integration

Obtain the existing recorder with `EvidencePlaywright.evidenceFor(page)`. Use `perform(...)` to name a custom action or group several native operations into one action. Use `checks().evaluate(...)` for an ordinary Java assertion, an external oracle, or a custom reading. The adapter suppresses automatic records inside these explicit boundaries to avoid duplicates.

Custom check callers provide a rule, expected value, target property, assertion callback, and reading supplier. A failed assertion is recorded and rethrown. An unavailable reading remains unknown; an explicitly absent DOM attribute is recorded as absent. Custom callers choose and redact their own recorded data.

An exploratory integration can supply the alternative actions it discovered through a deeper integration. The facade's next selected action is evidence of that action, not a complete inventory of possible interactions.

## Captures and trace files

Each used page gets a fresh timestamped child directory under the configured parent. The SUT and optional test objective labels help name the directory. Optional origin and test objective descriptions are stored on the run; the adapter's own identifier and version are automatic.

Closing the page, context, browser, or Playwright finishes its recorder. If the page closes externally, the recorder saves the cached result without trying to capture the closed page. Trace files contain `manifest.json`, `evidence.jsonl`, and optional screenshot artifacts. Load the whole individual trace directory into a consumer such as the dashboard.

Captures include the document, DOM elements, and open shadow roots. Page URL/title use `ui.url` and `ui.title`; widget attributes and live values keep their concrete source meanings and declared aliases. Capture descriptions identify selection limits, omissions, and redactions. Frame documents and closed shadow roots have a recorded capture limitation.

A missing captured property does not establish that the application lacks that property. Collecting a property does not establish that an oracle checked it. Structural widget paths identify widgets within their observation; consumers decide how to match visits or derive an abstract model.

DOM extraction, screenshots, locator resolution, and assertion readings are successive browser operations. A changing application can differ between them.

See [SPEC](SPEC.md) for recording rules and [README](../README.md) for commands and configuration.
