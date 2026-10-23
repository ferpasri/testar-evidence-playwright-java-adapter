package org.testar.evidence.playwright;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.Objects;

/** Creates native Playwright interfaces with automatic evidence recording. */
public final class EvidencePlaywright {
    private EvidencePlaywright() {
    }

    public static Playwright create() {
        return create(EvidenceOptions.defaults());
    }

    public static Playwright create(EvidenceOptions evidence) {
        return create(evidence, null);
    }

    public static Playwright create(EvidenceOptions evidence, Playwright.CreateOptions playwright) {
        Objects.requireNonNull(evidence, "evidence");
        return new FacadeRuntime(evidence).wrap(Playwright.create(playwright), null);
    }

    /** Gives integrations access to custom actions and checks for a facade page. */
    public static PlaywrightEvidence evidenceFor(Page page) {
        FacadeRuntime runtime = FacadeRuntime.runtimeOf(page);
        if (runtime == null) {
            throw new IllegalArgumentException("Page was not created through EvidencePlaywright");
        }
        return runtime.evidence((Page) FacadeRuntime.unwrap(page));
    }
}
