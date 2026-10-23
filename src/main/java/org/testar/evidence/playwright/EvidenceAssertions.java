package org.testar.evidence.playwright;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.APIResponseAssertions;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.assertions.PageAssertions;
import com.microsoft.playwright.assertions.PlaywrightAssertions;

/** Use this assertThat import to record native Playwright GUI assertion outcomes. */
public final class EvidenceAssertions {
    private EvidenceAssertions() {
    }

    public static LocatorAssertions assertThat(Locator locator) {
        Locator raw = (Locator) FacadeRuntime.unwrap(locator);
        return FacadeAssertions.wrap(PlaywrightAssertions.assertThat(raw), locator, raw.page(), raw);
    }

    public static PageAssertions assertThat(Page page) {
        Page raw = (Page) FacadeRuntime.unwrap(page);
        return FacadeAssertions.wrap(PlaywrightAssertions.assertThat(raw), page, raw, null);
    }

    public static APIResponseAssertions assertThat(APIResponse response) {
        return PlaywrightAssertions.assertThat((APIResponse) FacadeRuntime.unwrap(response));
    }

    public static void setDefaultAssertionTimeout(double timeout) {
        PlaywrightAssertions.setDefaultAssertionTimeout(timeout);
    }
}
