package org.testar.evidence.playwright;

import com.microsoft.playwright.Locator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.testar.evidence.ActionDescription;
import org.testar.evidence.EvidenceJson;
import org.testar.evidence.Properties;

/** An available action reported by a caller, including its planning parameters. */
public record AvailableAction(String actionType, String name, Locator locator, Properties parameters) {
    public AvailableAction {
        if (actionType == null || actionType.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Action type and name must be nonblank");
        }
        Objects.requireNonNull(parameters, "parameters");
    }

    public AvailableAction(String actionType, String name, Locator locator) {
        this(actionType, name, locator, Properties.empty());
    }

    public AvailableAction(String actionType, String name, Locator locator, Map<String, ?> parameters) {
        this(actionType, name, locator, Properties.of(parameters));
    }

    public ActionDescription description() {
        String targetDescription = locator == null ? name : locator.toString();
        String identity = EvidenceJson.write(EvidenceJson.tree(List.of(actionType, name, targetDescription, parameters.asJson())));
        return new ActionDescription("playwright:available:" + DomCapture.identity(identity), actionType,
            Properties.of(Map.of("playwright.description", name, "playwright.locator", targetDescription,
                "playwright.available_parameters", parameters.asJson())));
    }
}
