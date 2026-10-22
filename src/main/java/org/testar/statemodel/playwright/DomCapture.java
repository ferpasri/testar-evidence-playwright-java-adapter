package org.testar.statemodel.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ScreenshotType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.testar.statemodel.evidence.EvidenceJson;
import org.testar.statemodel.evidence.Properties;
import org.testar.statemodel.evidence.Widget;

final class DomCapture {
    private static final String SCRIPT = resource("capture.js");
    private static final String TARGET = resource("target.js");

    private DomCapture() {
    }

    static BrowserSnapshot capture(Page page, CaptureOptions options) {
        JsonNode result = EvidenceJson.tree(page.evaluate(SCRIPT, Map.of(
            "maxWidgets", options.maxWidgets(),
            "maxValueCharacters", options.maxValueCharacters(),
            "maxTotalCharacters", options.maxTotalCharacters(),
            "maxAttributesPerWidget", options.maxAttributesPerWidget(),
            "computedStyles", options.computedStyles(),
            "excludedAttributes", options.excludedAttributes())));
        Instant capturedAt = Instant.now();
        List<Widget> widgets = new ArrayList<>();
        for (JsonNode node : result.path("nodes")) {
            String path = node.path("path").asText();
            String parent = node.path("parent").isNull() ? null : widgetId(node.path("parent").asText());
            ObjectNode properties = (ObjectNode) node.path("properties").deepCopy();
            properties.put("playwright.dom.path", path);
            widgets.add(new Widget(widgetId(path), parent, new Properties(properties)));
        }
        byte[] screenshot = options.screenshots()
            ? page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG)) : null;
        return new BrowserSnapshot(capturedAt.getEpochSecond() * 1_000_000 + capturedAt.getNano() / 1_000,
            new Properties(result.path("properties")), List.copyOf(widgets), screenshot);
    }

    static String targetPath(Locator locator) {
        if (locator == null) {
            return null;
        }
        try {
            if (locator.count() != 1) {
                return null;
            }
            return (String) locator.evaluate(TARGET);
        } catch (PlaywrightException error) {
            return null;
        }
    }

    static String identity(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String widgetId(String path) {
        return "playwright:widget:" + identity(path);
    }

    private static String resource(String name) {
        try (InputStream input = DomCapture.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing capture resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read capture resource: " + name, error);
        }
    }
}
