package org.testar.evidence.playwright;

import java.util.List;
import org.testar.evidence.Properties;
import org.testar.evidence.Widget;

record BrowserSnapshot(long timestampMicros, Properties properties, List<Widget> widgets, byte[] screenshot) {
    Widget widgetAt(String path) {
        if (path == null) {
            return null;
        }
        return widgets.stream()
            .filter(widget -> widget.properties().contains("playwright.dom.path")
                && path.equals(widget.properties().get("playwright.dom.path").asText()))
            .findFirst().orElse(null);
    }
}
