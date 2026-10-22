# Playwright Java state-model adapter

A Java library that records scripted Playwright executions in the shared [TESTAR state-model evidence format](https://github.com/ferpasri/testar-state-model-specs).

```text
Java test script -> Playwright Java adapter -> testar-state-model-java -> portable evidence
```

The adapter imports `org.testar:testar-state-model:0.2.1` through Maven. Its public API is in `org.testar.statemodel.playwright`. Release `0.2.1` belongs to the shared `0.2` release series.

## Build and test

Use Java 17 or later and Maven 3.6.3 or later. First install the Java state-model library in your local Maven repository by running `mvn install` from its repository. Then run these commands from this adapter repository:

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

## Loan-denial example

[ParabankDeniedLoan.java](src/test/java/org/testar/statemodel/playwright/examples/ParabankDeniedLoan.java) shows the integration calls and contains the example's `main` method. It logs in as `john` / `demo`, checks Account Services, opens Request Loan, checks Apply for a Loan, enters amount `999999` and down payment `1`, submits, and checks Denied.

```powershell
mvn test-compile exec:java "-Dexec.mainClass=org.testar.statemodel.playwright.examples.ParabankDeniedLoan" "-Dexec.classpathScope=test"
```

With installed Google Chrome:

```powershell
mvn test-compile exec:java "-Dexec.mainClass=org.testar.statemodel.playwright.examples.ParabankDeniedLoan" "-Dexec.classpathScope=test" "-Dplaywright.channel=chrome"
```

The example runs headlessly. Add `"-Dplaywright.headed=true"` to show the browser. Its optional arguments are the starting URL and output directory:

```powershell
mvn test-compile exec:java "-Dexec.mainClass=org.testar.statemodel.playwright.examples.ParabankDeniedLoan" "-Dexec.classpathScope=test" "-Dexec.args=https://para.testar.org/parabank/index.htm target/my-loan-evidence"
```

Each default run creates a fresh `target/parabank-denied-loan-<unique-id>/` directory. A supplied output directory must contain no existing model. The printed path contains `manifest.json`, `evidence.jsonl`, and PNG files in `artifacts/`; load that whole directory in the dashboard.

The scenario records navigation, seven form/link actions, and three explicit text evaluations. A successful scenario has eight execution links and nine observations. The expected loan denial produces a passed evaluation. The hosted application's account data and behavior determine whether the scenario succeeds; the local integration fixture provides repeatable test data.

## Use the library

Install this adapter with `mvn install`, then declare:

```xml
<dependency>
    <groupId>org.testar</groupId>
    <artifactId>testar-state-model-playwright-java-adapter</artifactId>
    <version>0.2.1</version>
</dependency>
```

The test owns its Playwright page and browser:

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

Actions and assertions are recorded through these helpers. `perform` wraps custom actions and their parameters; `checks().evaluate` wraps custom assertions, readings, and targets. Password input values are omitted during capture, and `fillSecret` omits the supplied value from execution parameters.

See [SPEC](docs/SPEC.md) for recording behavior and [TODO](TODO.md) for planned work.
