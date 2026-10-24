# Playwright Java evidence adapter

A Java library that records Playwright actions and assertion results in the shared [TESTAR evidence format](https://github.com/ferpasri/testar-evidence-specs).

```text
Java test script -> Playwright Java adapter -> testar-evidence-java -> portable evidence
```

The adapter imports `org.testar:testar-evidence:0.3.0` through Maven. Its public API is in `org.testar.evidence.playwright`. Release `0.3.0` belongs to the shared `0.3` release series.

## Build and test

Use Java 17 or later and Maven 3.6.3 or later. First install the Java evidence library in your local Maven repository by running `mvn install` from its repository. Then run these commands from this adapter repository:

```powershell
mvn verify
```

To install Playwright's Chromium browser and run the local browser integration tests:

```powershell
mvn exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
mvn verify -Pbrowser-tests
```

Alternatively, use an installed Google Chrome:

```powershell
mvn verify -Pbrowser-tests "-Dplaywright.channel=chrome"
```

The browser tests use a controlled local website. They verify the scripted loan scenario, properties, screenshots, assertion outcomes, execution failures, capture limits, and reopening the saved evidence.

## Automatic recording facade

Replace `Playwright.create()` with `EvidencePlaywright.create()` and use the adapter's `assertThat` import. Keep the usual Playwright types and operations:

```java
import static org.testar.evidence.playwright.EvidenceAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.testar.evidence.playwright.EvidencePlaywright;

try (Playwright playwright = EvidencePlaywright.create();
     Browser browser = playwright.chromium().launch()) {
    Page page = browser.newPage();
    page.navigate("https://para.testar.org/parabank/index.htm");
    page.locator("input[name='username']").fill("john");
    page.locator("input[name='password']").fill("demo");
    page.locator("input[value='Log In']").click();
    assertThat(page.locator("#leftPanel h2")).hasText("Account Services");
}
```

Each used page gets its own run, sequence, and timestamped trace under `target/traces`. Closing the page, context, browser, or Playwright finishes its trace. Native Playwright performs the operations and assertion retries; the facade records their final results. Password input arguments and automatic password-check values are redacted.

To choose a location, SUT label, and optional run descriptions:

```java
EvidenceOptions options = new EvidenceOptions(Path.of("target", "traces"), "parabank",
    "Loan scenario script", "Verify an oversized loan is denied", CaptureOptions.defaults());
Playwright playwright = EvidencePlaywright.create(options);
```

These types belong to `org.testar.evidence.playwright`; `Path` is `java.nio.file.Path`. Origin and test objective accept descriptive text or `null`. The SUT label helps name directories. The adapter supplies its own name and version automatically. `create(options, nativeCreateOptions)` also accepts Playwright's creation settings. See [run metadata](docs/SPEC.md#run-metadata).

Use `EvidencePlaywright.evidenceFor(page)` for custom action boundaries or checks. Ordinary Java assertions and external oracle callbacks can be recorded through `checks().evaluate(...)`.

## Loan-denial example

[ParabankFacade.java](src/test/java/org/testar/evidence/playwright/examples/ParabankFacade.java) uses automatic recording and contains the example's `main` method. [ParabankDeniedLoan.java](src/test/java/org/testar/evidence/playwright/examples/ParabankDeniedLoan.java) shows explicit recording boundaries. It logs in as `john` / `demo`, checks Account Services, opens Request Loan, checks Apply for a Loan, enters amount `999999` and down payment `1`, submits, and checks Denied.

```powershell
mvn test-compile exec:java "-Dexec.mainClass=org.testar.evidence.playwright.examples.ParabankFacade" "-Dexec.classpathScope=test"
```

With installed Google Chrome:

```powershell
mvn test-compile exec:java "-Dexec.mainClass=org.testar.evidence.playwright.examples.ParabankFacade" "-Dexec.classpathScope=test" "-Dplaywright.channel=chrome"
```

The facade example runs headlessly. Add `"-Dplaywright.headed=true"` to show the browser. Its optional arguments are the starting URL and parent output directory:

```powershell
mvn test-compile exec:java "-Dexec.mainClass=org.testar.evidence.playwright.examples.ParabankFacade" "-Dexec.classpathScope=test" "-Dexec.args=https://para.testar.org/parabank/index.htm target/my-loan-evidence"
```

The facade creates a new child directory such as `target/traces/parabank_denied-loan_trace-2026-10-22_15-30-12-123/`. The timestamp uses local time and includes milliseconds; a collision adds a numeric suffix. The printed parent directory contains these traces. Load an individual trace directory, including its `manifest.json`, `evidence.jsonl`, and PNG files in `artifacts/`, in a consumer supporting schema 3.

A successful loan scenario records navigation, seven form/link actions, and three final text evaluations. The expected loan denial produces a passed evaluation. The local browser tests provide repeatable application data for the same script.

## Use the library

Install this adapter with `mvn install`, then declare:

```xml
<dependency>
    <groupId>org.testar</groupId>
    <artifactId>testar-evidence-playwright-java-adapter</artifactId>
    <version>0.3.0</version>
</dependency>
```

For explicit integration with an existing native page, the test can supply each recording boundary:

```java
try (PlaywrightEvidence evidence = PlaywrightEvidence.start(output, page)) {
    evidence.navigate(url);
    evidence.fill(page.locator("#username"), "john");
    evidence.fillSecret(page.locator("#password"), "demo");
    evidence.click(page.locator("#login"));
    evidence.checks().hasText("example:welcome", "Welcome heading",
        page.locator("h1"), "Welcome");
    evidence.finish("completed");
}
```

With this explicit API, actions and assertions are recorded through the helpers. `perform` wraps custom actions and their parameters; `checks().evaluate` wraps custom assertions, readings, and targets. Password input values are omitted during capture, and `fillSecret` omits the supplied value from execution parameters.

See [How it works](docs/how-it-works.md) for the components, recording flow, and script integration; [SPEC](docs/SPEC.md) for recording behavior; and [TODO](TODO.md) for planned work.

## Report additional evidence

`EvidencePlaywright.evidenceFor(page)` exposes the same recorder used by automatic recording. Report choices with `AvailableAction` and `recordAvailableActions`, then pass the chosen action to `perform` or `selectAction`. Its unexecuted alternatives remain in the observation.

`selectAction` and `reportActionResult` support native execution managed by the caller. `checks().recordResult` records an oracle result the caller already obtained, including optional precise targets, observed values, oracle details, and severity. See [SPEC](docs/SPEC.md#java-api) and [How it works](docs/how-it-works.md).
