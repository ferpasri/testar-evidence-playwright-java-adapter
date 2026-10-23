package org.testar.evidence.playwright.examples;

import static org.testar.evidence.playwright.EvidenceAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.Map;
import org.testar.evidence.playwright.CaptureOptions;
import org.testar.evidence.playwright.EvidenceOptions;
import org.testar.evidence.playwright.EvidencePlaywright;

/** The creation call and assertion import enable recording for ordinary Playwright code. */
public final class ParabankFacade {
    private ParabankFacade() {
    }

    public static void main(String[] arguments) {
        String url = arguments.length > 0 ? arguments[0] : "https://para.testar.org/parabank/index.htm";
        Path output = arguments.length > 1 ? Path.of(arguments[1]) : Path.of("target", "traces");
        EvidenceOptions evidence = new EvidenceOptions(output, "parabank", "Loan-denial example script",
            "denied-loan", CaptureOptions.defaults());
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions()
            .setHeadless(!Boolean.getBoolean("playwright.headed"));
        String channel = System.getProperty("playwright.channel");
        if (channel != null && !channel.isBlank()) {
            launch.setChannel(channel);
        }
        try (Playwright playwright = EvidencePlaywright.create(evidence, new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(launch)) {
            Page page = browser.newPage();
            page.setDefaultTimeout(15_000);
            run(page, url);
        }
        System.out.println("Evidence directories: " + output.toAbsolutePath());
    }

    public static void run(Page page, String url) {
        page.navigate(url);
        page.locator("input[name='username']").fill("john");
        page.locator("input[name='password']").fill("demo");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log In").setExact(true)).click();
        assertThat(page.getByText("Account Services", new Page.GetByTextOptions().setExact(true)))
            .hasText("Account Services");

        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Request Loan").setExact(true)).click();
        assertThat(page.getByText("Apply for a Loan", new Page.GetByTextOptions().setExact(true)))
            .hasText("Apply for a Loan");
        page.locator("#amount").fill("999999");
        page.locator("#downPayment").fill("1");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply Now").setExact(true)).click();
        assertThat(page.locator("#loanStatus")).hasText("Denied");
    }
}
