package dev.winlandcraft;

import java.util.List;

public final class WebAppChecks {
    public static void main(String[] args) {
        check("https://example.com/app".equals(WebApps.normalizeUrl(" example.com/app ")), "bare domain");
        check("http://localhost:8080".equals(WebApps.normalizeUrl("http://localhost:8080")), "local app");
        check(WebApps.normalizeUrl("hello world") == null, "search text is not an app URL");
        check(WebApps.normalizeUrl("file:///C:/test.html") == null, "unsupported scheme");
        check(WebApps.normalizeUrl("https://user:password@example.com") == null, "embedded credentials");
        var a = new WebApps.App("dfe2a3a0-88f3-4501-80bb-b4a803e73eb6", "Same name", "https://example.com", "c2F2ZWQtaWNvbg==");
        var b = new WebApps.App("ca2a4081-9b65-4f64-9c98-635fe38fdd58", "Same name", "http://localhost:8080", "");
        var expected = List.of(a, b);
        check(WebApps.decode(WebApps.encode(expected)).equals(expected), "names, URLs, icon data, distinct IDs survive roundtrip");
        check(WebApps.decode(WebApps.encode(List.of(a, a))).size() == 1, "duplicate IDs filtered");
        var invalid = new WebApps.App("../bad", "Broken", "https://example.com", "");
        check(WebApps.decode(WebApps.encode(List.of(invalid, a))).equals(List.of(a)), "invalid entry does not hide valid entry");
        check(WebApps.decode("null").isEmpty(), "empty catalog");
        System.out.println("Webapp definitions: 9 URL validation and persistence checks passed.");
    }
    private static void check(boolean pass, String name) { if (!pass) throw new AssertionError(name); }
}
