# How the Playwright Java adapter works

The adapter records what a script does, what the browser contains, and what its test oracles report. The script controls the browser and test flow; the adapter translates those operations into generic state-model evidence.

```text
Your Java script
    -> PlaywrightEvidence: action and recording boundaries
    -> PlaywrightChecks: assertions and their results
    -> DomCapture + JavaScript: browser readings and screenshots
    -> testar-state-model-java: validation and portable files
```

## The components

| Component | What it does |
| --- | --- |
| [PlaywrightEvidence.java](../src/main/java/org/testar/statemodel/playwright/PlaywrightEvidence.java) | Starts a recorded run, executes wrapped actions, connects observations to executions, and finishes the run. This is the main entry point for scripts. |
| [PlaywrightChecks.java](../src/main/java/org/testar/statemodel/playwright/PlaywrightChecks.java) | Runs wrapped assertions and reports their expected value, final reading, status, and target. |
| [CaptureOptions.java](../src/main/java/org/testar/statemodel/playwright/CaptureOptions.java) | Configures capture limits, selected computed styles, excluded attributes, and screenshots. |
| [DomCapture.java](../src/main/java/org/testar/statemodel/playwright/DomCapture.java) | Executes the browser scripts, converts their readings into generic widgets, and takes the screenshot. |
| [BrowserSnapshot.java](../src/main/java/org/testar/statemodel/playwright/BrowserSnapshot.java) | Holds one capture in memory: timestamp, page properties, widgets, and screenshot bytes. It also finds captured widgets by DOM path. |
| [capture.js](../src/main/resources/org/testar/statemodel/playwright/capture.js) | Reads the document and elements in the browser, including selected properties, hierarchy, capture descriptions, and known omissions. |
| [target.js](../src/main/resources/org/testar/statemodel/playwright/target.js) | Calculates the structural path of a locator's element so it can be connected to a captured widget. |

`DomCapture` and `BrowserSnapshot` are internal helpers. Most scripts use `PlaywrightEvidence`, `PlaywrightChecks`, and optionally `CaptureOptions`.

The imported `testar-state-model-java` library supplies the generic records and `JsonlStore`. It validates events and manages the manifest, JSONL log, and screenshot artifacts. Browser-specific interpretation belongs to this adapter.

## Integrating an existing script

Keep the existing Playwright browser, page, and locators. Start an evidence session and route the actions and assertions you want recorded through its helpers:

```java
try (PlaywrightEvidence evidence = PlaywrightEvidence.start(output, page)) {
    evidence.navigate(url);
    evidence.fill(page.locator("input[name='username']"), "john");
    evidence.fillSecret(page.locator("input[name='password']"), "demo");
    evidence.click(page.locator("input[value='Log In']"));
    evidence.checks().hasText("example:account-services", "Account Services is displayed",
        page.locator("#leftPanel h2"), "Account Services");
    evidence.finish("completed");
}
```

Here `output` is a fresh model directory, `page` is the caller's Playwright page, and `url` is the starting address. Imports and the complete browser setup are shown in [ParabankDeniedLoan.java](../src/test/java/org/testar/statemodel/playwright/examples/ParabankDeniedLoan.java).

Direct calls such as `locator.click()` and ordinary assertions still work, but the adapter does not automatically intercept them. Use the recording helpers for operations that should appear in the evidence. The caller also controls waits and application readiness.

## Following one action and assertion

Consider clicking **Apply Now**, then checking that the loan status is **Denied**:

```java
evidence.click(page.locator("input[value='Apply Now']"));
evidence.checks().hasText("example:loan-denied", "The loan request is denied",
    page.locator("#loanStatus"), "Denied");
```

1. Before the click, `PlaywrightEvidence` uses an existing pending capture or asks `DomCapture` to take one. `capture.js` reads the page; `DomCapture` converts its elements into widgets and optionally takes a PNG screenshot.
2. `target.js` identifies the button's structural DOM path. The adapter looks for the corresponding widget in the capture.
3. The adapter writes the source observation with the next scripted action as a candidate, records action selection, and calls Playwright's click operation. A normal return records an accepted submission; an exception records a rejected submission with its error and is rethrown.
4. `PlaywrightChecks` runs Playwright's retrying text assertion. Once it finishes, the helper reads the actual text, captures the page, and prepares the evaluation targeting that observation and widget property where identifiable.
5. At the next action or session ending, the adapter writes the pending observation, links the accepted click to this resulting observation, and writes the evaluation.

The expected business result is **Denied**, so this assertion passes when that text appears. An accepted click alone does not establish that the application's response was correct.

## Why an observation can remain pending

The adapter briefly keeps the latest captured observation in memory until the next action is known. It can then attach that action as a candidate to the same observation. An action's result observation can therefore also be the next action's source.

This pending capture is unrelated to an evaluation with status `pending`.

`observe()` records an additional visit without executing an action. Consecutive assertions get separate captured visits; the adapter does not invent actions between them. Identical captured content can appear in several visits.

`finish(reason)` writes outstanding records and the run ending. A try-with-resources block closes the writer even when a test fails; closing an unfinished session attempts to finish it with the known ending reason.

## What capture tells you

The capture includes the document, DOM elements, and open shadow roots. Page URL and title have shared aliases `ui.url` and `ui.title`. Widgets retain source properties such as `playwright.dom.textContent` and `playwright.attribute.alt`; declared aliases identify equal readings under shared names such as `ui.text` and `ui.alt`.

`CaptureOptions` controls collection limits and selected computed styles. `evidence.capture` describes the collection policy and truncation, while `evidence.omitted_properties` records known exclusions, unavailable readings, redactions, and limits. Password values are redacted from capture; `fillSecret` also leaves the supplied value out of action parameters.

Frame contents and closed shadow roots are outside the current capture scope. A missing captured property does not establish that the property is absent from the application. Collecting a property also does not mean a test oracle evaluated it.

Widget identifiers derive from structural DOM paths and must be interpreted within their observation. The same path across visits does not establish the same logical widget. Cross-source matching and abstraction belong to consumers of the recorded evidence.

DOM extraction, screenshot capture, assertion readings, and locator resolution are successive browser operations. The application can change between them; they are not one atomic browser snapshot.

## Extending actions and test oracles

Use `perform(...)` for an action without a convenience helper. Supply its kind, description, locator, parameters, and the operation to execute:

```java
Locator account = page.locator("#fromAccountId");
evidence.perform("select", "Select source account", account,
    Map.of("value", "12345"), () -> account.selectOption("12345"));
```

This needs `com.microsoft.playwright.Locator` and `java.util.Map`. Choose an account value available in the application. A multi-step routine wrapped in one `perform` call produces one outer action. Wrap its individual operations separately when the trace needs those individual steps.

The built-in check helpers are `hasText`, `hasValue`, and `hasAttribute`. They retain Playwright's assertion retry behavior and record one final outcome. `checks().timeoutMillis(...)` configures their assertion timeout.

For another oracle, use `checks().evaluate(...)`. Supply the rule identity and parameters, expected value, locator and property key, assertion callback, and a supplier for the actual reading. The assertion decides success or failure and owns its retry behavior; the reading supplier reports the final value, explicit absence, or unknown reading.

A failed assertion is recorded and rethrown; other runtime exceptions are reported as errors and rethrown. If the widget cannot be matched to the capture, the adapter retains an observation-level evaluation with the requested target and an explanation. It does not invent a widget or assign its result to every property.

Custom action parameters, assertion expectations, and readings are supplied by the script author. Choose what to record deliberately, including any sensitive values.

## Where to change behavior

Change capture fields in `capture.js`, their Java conversion in `DomCapture`, and configuration in `CaptureOptions`. Keep the structural-path logic consistent between `capture.js` and `target.js`.

Add action lifecycle behavior in `PlaywrightEvidence` and assertion helpers in `PlaywrightChecks`. Keep generic serialization and validation in the state-model library. Browser integration tests exercise the resulting evidence, while the shared contract remains defined by the specs repository.

See [SPEC.md](SPEC.md) for recording rules and limits, and [the README](../README.md) for setup and execution commands.
