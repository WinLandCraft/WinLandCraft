package dev.winlandcraft;

public final class BrowserAddressChecks {
    public static void main(String[] args) {
        expect("example.com", "https://example.com");
        expect("  https://example.com/watch?v=one&list=two  ", "https://example.com/watch?v=one&list=two");
        expect("http://localhost:8080/test", "http://localhost:8080/test");
        expect("localhost:3000", "https://localhost:3000");
        expect("http://intranet/page", "http://intranet/page");
        expect("127.0.0.1:8080", "https://127.0.0.1:8080");
        expect("cats & dogs", "https://www.google.com/search?q=cats+%26+dogs");
        expect("youtube", "https://www.google.com/search?q=youtube");
        expect("example.com tutorials", "https://www.google.com/search?q=example.com+tutorials");
        expect("javascript:alert(1)", "https://www.google.com/search?q=javascript%3Aalert%281%29");
        expect("about:blank", "about:blank");
        expect("  ", null);
        System.out.println("Browser address interpretation: 12 checks passed.");
    }
    private static void expect(String input, String expected) {
        String actual = BrowserAddress.destination(input);
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(input + " -> " + actual);
    }
}
