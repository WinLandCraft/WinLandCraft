package dev.winlandcraft;

import org.cef.network.CefRequest;

public final class UBlockChecks {
    public static void main(String[] args) throws Exception {
        var rules = UBlockRules.fromJson("""
                [
                  {"id":1,"priority":10,"action":{"type":"block"},"condition":{"requestDomains":["ads.example"]}},
                  {"id":2,"priority":30,"action":{"type":"allow"},"condition":{"requestDomains":["ads.example"],"initiatorDomains":["trusted.example"]}},
                  {"id":3,"priority":10,"action":{"type":"block"},"condition":{"urlFilter":"||tracker.test/pixel*","resourceTypes":["image"]}},
                  {"id":4,"priority":10,"action":{"type":"block"},"condition":{"regexFilter":"/ads/[0-9]+\\\\.js$","domainType":"thirdParty"}}
                ]
                """);
        check(rules.size() == 4, "all supported rules loaded");
        check(rules.blocks("https://sub.ads.example/banner.js", "https://site.example", "https://site.example",
                CefRequest.ResourceType.RT_SCRIPT, "GET"), "request-domain suffix blocked");
        check(!rules.blocks("https://ads.example/banner.js", "https://trusted.example", "https://trusted.example",
                CefRequest.ResourceType.RT_SCRIPT, "GET"), "higher-priority allow wins");
        check(rules.blocks("https://cdn.tracker.test/pixel.gif", "https://site.example", "https://site.example",
                CefRequest.ResourceType.RT_IMAGE, "GET"), "DNR domain anchor and wildcard matched");
        check(!rules.blocks("https://cdn.tracker.test/pixel.gif", "https://site.example", "https://site.example",
                CefRequest.ResourceType.RT_SCRIPT, "GET"), "resource-type condition honored");
        check(rules.blocks("https://cdn.other.test/ads/42.js", "https://site.example", "https://site.example",
                CefRequest.ResourceType.RT_SCRIPT, "GET"), "third-party regex rule matched");
        check(!rules.blocks("https://cdn.site.example/ads/42.js", "https://www.site.example", "https://www.site.example",
                CefRequest.ResourceType.RT_SCRIPT, "GET"), "same-site request is first-party");
        System.out.println("uBlock rule checks passed");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
