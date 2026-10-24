# How the Playwright Java adapter works

Playwright controls the browser. The adapter records supported operations and final oracle results. The independent evidence library validates and saves the generic records.

```text
Java script -> EvidencePlaywright and EvidenceAssertions -> page recorder and checks -> DOM capture -> evidence library
```

## Components

| Component | What it does |
| --- | --- |
| `EvidencePlaywright` / `EvidenceOptions` | Start the facade and configure trace locations, descriptions, and capture settings. |
| `EvidenceAssertions` | Supply the static `assertThat` import for automatic final oracle results. |
| `FacadeRuntime` | Delegate native interfaces and callbacks and own each page's recorder. |
| `FacadeActions` / `FacadeValues` | Describe operations and the arguments to record or redact. |
| `FacadeAssertions` | Delegate retrying assertions and identify expected/observed values and targets. |
| `PlaywrightEvidence` | Start run and sequence, record action lifecycles and transitions, and finish the histories. |
| `AvailableAction` | Describe a caller-reported choice and its planning parameters. |
| `PlaywrightChecks` | Run a test oracle or record an existing result with optional details and severity. |
| `CaptureOptions`, `DomCapture`, and `BrowserSnapshot` | Configure capture and hold one captured page, hierarchy, timestamp, and screenshot. |
| `capture.js` / `target.js` | Read DOM properties and find a locator's structural path. |

## Minimal integration

Change `Playwright.create()` to `EvidencePlaywright.create()` and import the adapter's static `assertThat`. Keep the usual Playwright interfaces and operations. The facade records supported actions and final Playwright assertions. Ordinary Java assertions and custom tool callbacks use explicit reporting.

The [facade example](../src/test/java/org/testar/evidence/playwright/examples/ParabankFacade.java) shows the loan-denial scenario. The [explicit example](../src/test/java/org/testar/evidence/playwright/examples/ParabankDeniedLoan.java) uses caller-defined boundaries.

## Additional information from a caller

Use `EvidencePlaywright.evidenceFor(page)` to access the recorder already used by the facade:

```java
PlaywrightEvidence recorder = EvidencePlaywright.evidenceFor(page);
AvailableAction action = new AvailableAction("click", "Apply for loan", page.locator("#apply"));
recorder.recordAvailableActions(List.of(action), AvailableActionScope.ELIGIBLE);
recorder.perform(action.actionType(), action.name(), action.locator(), Map.of(),
    action.locator()::click, action);
recorder.capture();

// The caller already obtained this oracle result; the adapter records it once.
recorder.checks().recordResult(new Oracle("example:loan-status", "1", Properties.empty()),
    "Loan was denied", OracleStatus.PASSED, null, Properties.empty(),
    new OracleSeverity("informational", null));
```

`AvailableAction` and `PlaywrightEvidence` are adapter types. Scope and oracle types come from `org.testar.evidence`; `List` and `Map` are from `java.util`. The short `recordResult` overload targets the observation. A longer overload accepts a locator, literal property, expected value, and observed value for a precise report.

The caller supplies the discovered list and the outcomes it obtained. Planning parameters describe choices; generated input belongs to an execution. Passing the available action connects selection to that reported description. Several native operations inside one `perform` callback form one compound action, with nested facade records suppressed.

`checks().evaluate` runs a custom oracle callback and obtains its observed value afterward. `checks().recordResult` records an existing outcome without rerunning the oracle. Both support optional caller-supplied severity. Custom callers choose their recorded data and redaction.

Without the facade, start a recorder and call `capture`, `selectAction`, `reportActionResult`, and `capture` around existing native execution. The outcome is `ActionOutcome.RETURNED` or `ActionOutcome.ERROR`; its business correctness is evaluated by a separate oracle.

## Following one action and oracle

For Apply Now followed by checking Denied:

1. The recorder captures the source observation or uses the latest buffered capture.
2. It identifies the selected available action and its widget when the locator can be matched.
3. Playwright executes the click once. The recorder saves `returned` or `error` as its action execution result.
4. A later capture supplies the resulting observation and creates the recorded transition.
5. Playwright evaluates the text assertion with its normal retries. The adapter records one final oracle result, its observed value, and its precise target when identifiable.
6. The next action or trace ending flushes buffered records. The sequence finishes before the run.

Denied passes this oracle because denial is its expected business response. A returned click and a passed oracle describe different facts.

The latest observation can stay buffered so the next action can be included in its available-action list. That capture can then be both the previous action's result and the next action's source. Consecutive oracle evaluations can produce observations with no action between them. The recorder preserves those observations without inventing a transition.

## Captures and trace files

Each used page has an independent trace containing one run and one sequence. The facade reserves a fresh timestamped child directory using the SUT and optional test objective labels. Optional origin and test objective descriptions retain their original text in run properties; adapter identity and version are supplied automatically.

DOM extraction, locator resolution, screenshots, and oracle readings are separate browser operations. The script controls application readiness. Captures retain the document, elements, open shadow roots, selected properties, and known limits. State-level `ui.url` and `ui.title` identify the captured address and document title. Widget attributes and live values retain their concrete source meanings.

A missing captured property does not establish application-level absence. Capturing a property does not establish that an oracle evaluated it. Structural widget paths identify elements within an observation; consumers use abstraction rules to match observations and build abstract models.

The evidence library validates the generic records and writes `manifest.json`, `evidence.jsonl`, and referenced screenshot artifacts. A consumer supporting schema 3 loads the complete directory. Closing the page, browser, or facade finishes the recorder; an already closed page is finished from cached captures.

See [SPEC](SPEC.md) for recording rules and [README](../README.md) for commands.
