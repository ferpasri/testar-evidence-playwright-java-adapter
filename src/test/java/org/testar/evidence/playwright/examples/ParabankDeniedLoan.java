package org.testar.evidence.playwright.examples;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.Map;
import org.testar.evidence.playwright.PlaywrightEvidence;

public final class ParabankDeniedLoan {
    private ParabankDeniedLoan() {
    }

    public static void main(String[] arguments) throws Exception {
        String url = arguments.length > 0 ? arguments[0] : "https://para.testar.org/parabank/index.htm";
        Path output = outputDirectory(arguments);
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions()
            .setHeadless(!Boolean.getBoolean("playwright.headed"));
        String channel = System.getProperty("playwright.channel");
        if (channel != null && !channel.isBlank()) {
            launch.setChannel(channel);
        }
        // Browser installation is an explicit setup step; callers can also select an installed Chrome channel.
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
            .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(launch)) {
            Page page = browser.newPage();
            page.setDefaultTimeout(15_000);
            try (PlaywrightEvidence evidence = PlaywrightEvidence.start(output, page,
                    "Loan-denial example script", "Verify that an oversized loan request is denied")) {
                System.out.println("Evidence: " + output.toAbsolutePath());
                evidence.checks().timeoutMillis(15_000);
                run(evidence, page, url);
                evidence.finish("completed");
            }
        }
    }

    static Path outputDirectory(String[] arguments) throws Exception {
        if (arguments.length > 1) {
            return Path.of(arguments[1]);
        }
        return TraceDirectories.create(Path.of("target", "traces"), "parabank", "denied-loan");
    }

    /** Each helper records a deliberate script action or a specific test-oracle evaluation. */
    public static void run(PlaywrightEvidence evidence, Page page, String url) throws Exception {
        evidence.navigate(url);
        evidence.fill(page.locator("input[name='username']"), "john");
        evidence.fillSecret(page.locator("input[name='password']"), "demo");
        evidence.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log In").setExact(true)));
        evidence.checks().hasText("parabank:account-services", "Account Services is displayed",
            page.getByText("Account Services", new Page.GetByTextOptions().setExact(true)), "Account Services");

        evidence.click(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Request Loan").setExact(true)));
        evidence.checks().hasText("parabank:loan-form", "Apply for a Loan is displayed",
            page.getByText("Apply for a Loan", new Page.GetByTextOptions().setExact(true)), "Apply for a Loan");
        evidence.fill(page.locator("#amount"), "999999");
        evidence.fill(page.locator("#downPayment"), "1");
        evidence.click(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply Now").setExact(true)));

        // Denial is the expected business outcome, so this evaluation passes when Denied appears.
        evidence.checks().hasText("parabank:loan-denied", "The loan request is denied",
            page.locator("#loanStatus"), "Denied");
    }
}
