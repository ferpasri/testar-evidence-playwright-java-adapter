package org.testar.evidence.playwright;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class LoanFixture implements AutoCloseable {
    private final HttpServer server;

    LoanFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String content;
        if (path.equals("/login")) {
            Map<String, String> input = form(exchange);
            content = "john".equals(input.get("username")) && "demo".equals(input.get("password"))
                ? overview() : "<h1>Incorrect credentials</h1>";
        } else if (path.equals("/requestloan")) {
            content = """
                <h2>Account Services</h2><h1>Apply for a Loan</h1>
                <form action="/apply" method="post">
                    <label for="amount">Loan amount</label><input id="amount" name="amount">
                    <label for="downPayment">Down payment</label><input id="downPayment" name="downPayment">
                    <input type="submit" value="Apply Now">
                </form>
                """;
        } else if (path.equals("/apply")) {
            Map<String, String> input = form(exchange);
            String status = "999999".equals(input.get("amount")) && "1".equals(input.get("downPayment"))
                ? "Denied" : "Approved";
            content = "<h2>Account Services</h2><h1>Loan Request Processed</h1><p id='loanStatus'>" + status + "</p>";
        } else {
            content = """
                <h1>Customer Login</h1><form action="/login" method="post">
                    <label>Username<input name="username"></label>
                    <label>Password<input name="password" type="password" value="stored-secret"></label>
                    <input type="submit" value="Log In">
                </form>
                """;
        }
        byte[] bytes = ("<!doctype html><html><head><meta charset='utf-8'><title>Local loan fixture</title>"
            + "</head><body>" + content + "</body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String overview() {
        return "<h2>Account Services</h2><h1>Accounts Overview</h1><a href='/requestloan'>Request Loan</a>";
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
