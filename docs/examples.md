# Loan-denial example

Run these commands from the adapter repository root.

[ParabankFacade.java](../src/test/java/org/testar/evidence/playwright/examples/ParabankFacade.java) uses automatic recording and contains the example's `main` method. [ParabankDeniedLoan.java](../src/test/java/org/testar/evidence/playwright/examples/ParabankDeniedLoan.java) shows explicit recording boundaries. It logs in as `john` / `demo`, checks Account Services, opens Request Loan, checks Apply for a Loan, enters amount `999999` and down payment `1`, submits, and checks Denied.

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

The facade creates a new child directory such as `target/traces/parabank_denied-loan_trace-2026-10-22_15-30-12-123/`. The timestamp uses local time and includes milliseconds; a collision adds a numeric suffix. The printed parent directory contains these traces. Load an individual trace directory, including its `manifest.json`, `evidence.jsonl`, and PNG files in `artifacts/`, in a compatible evidence consumer.

A successful loan scenario records navigation, seven form/link actions, and three final text evaluations. The expected loan denial produces a passed evaluation. The local browser tests provide repeatable application data for the same script.
