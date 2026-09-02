package dev.winlandcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cef.network.CefRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Immutable, indexed subset of Chromium DNR used by uBlock Origin Lite's default rulesets. */
final class UBlockRules {
    private static final Set<String> SUPPORTED_CONDITIONS = Set.of(
            "domainType", "excludedInitiatorDomains", "excludedRequestDomains",
            "excludedResourceTypes", "excludedTopDomains", "initiatorDomains",
            "isUrlFilterCaseSensitive", "regexFilter", "requestDomains", "requestMethods",
            "resourceTypes", "topDomains", "urlFilter");
    private static final Set<String> SECOND_LEVEL_SUFFIXES = Set.of(
            "ac.uk", "co.jp", "co.nz", "co.uk", "com.au", "com.br", "com.cn", "com.mx",
            "com.tr", "edu.au", "gov.uk", "net.au", "org.au", "org.uk");

    private final Map<String, List<Rule>> domainRules;
    private final TokenIndex tokenRules;
    private final List<Rule> fallbackRules;
    private final int ruleCount;

    private UBlockRules(Map<String, List<Rule>> domainRules, TokenIndex tokenRules,
                        List<Rule> fallbackRules, int ruleCount) {
        this.domainRules = domainRules;
        this.tokenRules = tokenRules;
        this.fallbackRules = fallbackRules;
        this.ruleCount = ruleCount;
    }

    static UBlockRules load(List<InputStream> rulesets) throws IOException {
        var builder = new Builder();
        for (InputStream input : rulesets) try (input;
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) throw new IOException("uBlock ruleset root is not an array");
            for (JsonElement element : root.getAsJsonArray()) builder.add(element.getAsJsonObject());
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed uBlock ruleset", malformed);
        }
        return builder.build();
    }

    static UBlockRules fromJson(String... rulesets) throws IOException {
        var streams = new ArrayList<InputStream>(rulesets.length);
        for (String ruleset : rulesets)
            streams.add(new java.io.ByteArrayInputStream(ruleset.getBytes(StandardCharsets.UTF_8)));
        return load(streams);
    }

    int size() {
        return ruleCount;
    }

    boolean blocks(String url, String initiatorUrl, String topUrl,
                   CefRequest.ResourceType resourceType, String method) {
        String requestHost = host(url);
        if (requestHost.isEmpty()) return false;
        var request = new Request(url, requestHost, host(initiatorUrl), host(topUrl),
                resourceName(resourceType), method == null ? "GET" : method.toUpperCase(Locale.ROOT));
        Rule winner = null;
        if (isIp(requestHost)) {
            winner = choose(winner, domainRules.get(requestHost), request);
        } else {
            for (int offset = 0; offset < requestHost.length();) {
                winner = choose(winner, domainRules.get(requestHost.substring(offset)), request);
                int dot = requestHost.indexOf('.', offset);
                if (dot < 0) break;
                offset = dot + 1;
            }
        }
        winner = tokenRules.choose(url.toLowerCase(Locale.ROOT), request, winner);
        winner = choose(winner, fallbackRules, request);
        return winner != null && !winner.allow;
    }

    private static Rule choose(Rule winner, List<Rule> candidates, Request request) {
        if (candidates == null) return winner;
        for (Rule candidate : candidates) if (candidate.matches(request)
                && (winner == null || candidate.priority > winner.priority
                || candidate.priority == winner.priority && candidate.allow && !winner.allow)) {
            winner = candidate;
        }
        return winner;
    }

    private static String host(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            String host = URI.create(value).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean isIp(String host) {
        if (host.indexOf(':') >= 0) return true;
        for (int i = 0; i < host.length(); i++) {
            char character = host.charAt(i);
            if (character != '.' && (character < '0' || character > '9')) return false;
        }
        return true;
    }

    private static String resourceName(CefRequest.ResourceType type) {
        if (type == null) return "other";
        return switch (type) {
            case RT_MAIN_FRAME, RT_NAVIGATION_PRELOAD_MAIN_FRAME -> "main_frame";
            case RT_SUB_FRAME, RT_NAVIGATION_PRELOAD_SUB_FRAME -> "sub_frame";
            case RT_STYLESHEET -> "stylesheet";
            case RT_SCRIPT -> "script";
            case RT_IMAGE -> "image";
            case RT_FONT_RESOURCE -> "font";
            case RT_OBJECT, RT_PLUGIN_RESOURCE -> "object";
            case RT_MEDIA -> "media";
            case RT_WORKER, RT_SHARED_WORKER, RT_SERVICE_WORKER -> "script";
            case RT_XHR -> "xmlhttprequest";
            case RT_PING -> "ping";
            default -> "other";
        };
    }

    private record Request(String url, String host, String initiatorHost, String topHost,
                           String resourceType, String method) {
        boolean thirdParty() {
            return initiatorHost.isEmpty() || !site(host).equals(site(initiatorHost));
        }
    }

    private static String site(String host) {
        if (host.isEmpty() || isIp(host)) return host;
        String[] labels = host.split("\\.");
        if (labels.length < 2) return host;
        String lastTwo = labels[labels.length - 2] + '.' + labels[labels.length - 1];
        if (labels.length > 2 && SECOND_LEVEL_SUFFIXES.contains(lastTwo))
            return labels[labels.length - 3] + '.' + lastTwo;
        return lastTwo;
    }

    private record Rule(boolean allow, int priority, Pattern urlPattern, String[] initiatorDomains,
                        String[] excludedInitiatorDomains, String[] topDomains,
                        String[] excludedTopDomains, String[] excludedRequestDomains,
                        Set<String> resourceTypes, Set<String> excludedResourceTypes,
                        Set<String> requestMethods, Boolean thirdParty) {
        boolean matches(Request request) {
            if (urlPattern != null && !urlPattern.matcher(request.url).find()) return false;
            if (!matchesRequired(request.initiatorHost, initiatorDomains)
                    || matchesAny(request.initiatorHost, excludedInitiatorDomains)) return false;
            if (!matchesRequired(request.topHost, topDomains)
                    || matchesAny(request.topHost, excludedTopDomains)) return false;
            if (matchesAny(request.host, excludedRequestDomains)) return false;
            if ((!resourceTypes.isEmpty() && !resourceTypes.contains(request.resourceType))
                    || excludedResourceTypes.contains(request.resourceType)) return false;
            if (!requestMethods.isEmpty() && !requestMethods.contains(request.method)) return false;
            return thirdParty == null || thirdParty == request.thirdParty();
        }
    }

    private static boolean matchesRequired(String host, String[] domains) {
        return domains.length == 0 || matchesAny(host, domains);
    }

    private static boolean matchesAny(String host, String[] domains) {
        if (host.isEmpty()) return false;
        for (String domain : domains)
            if (host.equals(domain) || host.endsWith('.' + domain)) return true;
        return false;
    }

    private static final class Builder {
        private final Map<String, List<Rule>> domainRules = new HashMap<>();
        private final TokenIndex.Builder tokenRules = new TokenIndex.Builder();
        private final List<Rule> fallbackRules = new ArrayList<>();
        private int count;

        void add(JsonObject source) {
            JsonObject action = object(source, "action");
            JsonObject condition = object(source, "condition");
            if (action == null || condition == null || condition.has("responseHeaders")) return;
            for (String key : condition.keySet()) if (!SUPPORTED_CONDITIONS.contains(key)) return;
            String actionType = string(action, "type", "");
            if (!actionType.equals("block") && !actionType.equals("allow")
                    && !actionType.equals("allowAllRequests")) return;

            Pattern urlPattern = null;
            String urlFilter = string(condition, "urlFilter", null);
            String regexFilter = string(condition, "regexFilter", null);
            try {
                if (urlFilter != null) urlPattern = compileUrlFilter(urlFilter,
                        bool(condition, "isUrlFilterCaseSensitive", false));
                else if (regexFilter != null) urlPattern = Pattern.compile(regexFilter,
                        bool(condition, "isUrlFilterCaseSensitive", false) ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException invalid) {
                return;
            }

            Boolean thirdParty = switch (string(condition, "domainType", "")) {
                case "firstParty" -> false;
                case "thirdParty" -> true;
                default -> null;
            };
            var rule = new Rule(!actionType.equals("block"), integer(source, "priority", 1), urlPattern,
                    strings(condition, "initiatorDomains"), strings(condition, "excludedInitiatorDomains"),
                    strings(condition, "topDomains"), strings(condition, "excludedTopDomains"),
                    strings(condition, "excludedRequestDomains"), stringSet(condition, "resourceTypes", false),
                    stringSet(condition, "excludedResourceTypes", false),
                    stringSet(condition, "requestMethods", true), thirdParty);
            String[] requestDomains = strings(condition, "requestDomains");
            if (requestDomains.length > 0) {
                for (String domain : requestDomains)
                    domainRules.computeIfAbsent(domain, ignored -> new ArrayList<>()).add(rule);
            } else {
                String token = urlFilter == null ? "" : longestToken(urlFilter);
                if (token.length() >= 3) tokenRules.add(token, rule);
                else fallbackRules.add(rule);
            }
            count++;
        }

        UBlockRules build() {
            domainRules.replaceAll((ignored, rules) -> List.copyOf(rules));
            return new UBlockRules(Map.copyOf(domainRules), tokenRules.build(),
                    List.copyOf(fallbackRules), count);
        }
    }

    private static JsonObject object(JsonObject source, String key) {
        JsonElement element = source.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject source, String key, String fallback) {
        JsonElement element = source.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static int integer(JsonObject source, String key, int fallback) {
        JsonElement element = source.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }

    private static boolean bool(JsonObject source, String key, boolean fallback) {
        JsonElement element = source.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
    }

    private static String[] strings(JsonObject source, String key) {
        JsonElement element = source.get(key);
        if (element == null || !element.isJsonArray()) return new String[0];
        JsonArray array = element.getAsJsonArray();
        String[] values = new String[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsString().toLowerCase(Locale.ROOT);
        return values;
    }

    private static Set<String> stringSet(JsonObject source, String key, boolean uppercase) {
        String[] values = strings(source, key);
        if (values.length == 0) return Set.of();
        var result = new HashSet<String>(values.length);
        for (String value : values) result.add(uppercase ? value.toUpperCase(Locale.ROOT) : value);
        return Set.copyOf(result);
    }

    private static Pattern compileUrlFilter(String filter, boolean caseSensitive) {
        boolean domainAnchor = filter.startsWith("||");
        boolean startAnchor = !domainAnchor && filter.startsWith("|");
        boolean endAnchor = filter.endsWith("|") && !filter.endsWith("\\|");
        int start = domainAnchor ? 2 : startAnchor ? 1 : 0;
        int end = endAnchor ? filter.length() - 1 : filter.length();
        var expression = new StringBuilder();
        if (domainAnchor) expression.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?");
        else if (startAnchor) expression.append('^');
        var literal = new StringBuilder();
        for (int index = start; index < end; index++) {
            char character = filter.charAt(index);
            if (character == '*') {
                appendQuoted(expression, literal);
                expression.append(".*");
            } else if (character == '^') {
                appendQuoted(expression, literal);
                expression.append("(?:[^A-Za-z0-9_.%-]|$)");
            } else if (character == '\\' && index + 1 < end) {
                literal.append(filter.charAt(++index));
            } else literal.append(character);
        }
        appendQuoted(expression, literal);
        if (endAnchor) expression.append('$');
        return Pattern.compile(expression.toString(), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
    }

    private static void appendQuoted(StringBuilder expression, StringBuilder literal) {
        if (literal.isEmpty()) return;
        expression.append(Pattern.quote(literal.toString()));
        literal.setLength(0);
    }

    private static String longestToken(String filter) {
        String longest = "";
        for (String token : filter.toLowerCase(Locale.ROOT).split("[|*^]+")) {
            token = token.replace("\\", "");
            if (token.length() > longest.length()) longest = token;
        }
        return longest;
    }

    /** Aho-Corasick index keeps non-domain URL filters O(URL length + actual token hits). */
    private static final class TokenIndex {
        private final List<Node> nodes;

        private TokenIndex(List<Node> nodes) {
            this.nodes = nodes;
        }

        Rule choose(String text, Request request, Rule winner) {
            int state = 0;
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                while (state != 0 && !nodes.get(state).next.containsKey(character)) state = nodes.get(state).failure;
                state = nodes.get(state).next.getOrDefault(character, 0);
                winner = UBlockRules.choose(winner, nodes.get(state).rules, request);
            }
            return winner;
        }

        private static final class Builder {
            private final List<Node> nodes = new ArrayList<>(List.of(new Node()));

            void add(String token, Rule rule) {
                int state = 0;
                for (int index = 0; index < token.length(); index++) {
                    char character = token.charAt(index);
                    Integer next = nodes.get(state).next.get(character);
                    if (next == null) {
                        next = nodes.size();
                        nodes.get(state).next.put(character, next);
                        nodes.add(new Node());
                    }
                    state = next;
                }
                nodes.get(state).rules.add(rule);
            }

            TokenIndex build() {
                var queue = new ArrayDeque<Integer>();
                for (int child : nodes.getFirst().next.values()) queue.add(child);
                while (!queue.isEmpty()) {
                    int state = queue.remove();
                    for (var edge : nodes.get(state).next.entrySet()) {
                        char character = edge.getKey();
                        int child = edge.getValue(), failure = nodes.get(state).failure;
                        while (failure != 0 && !nodes.get(failure).next.containsKey(character))
                            failure = nodes.get(failure).failure;
                        failure = nodes.get(failure).next.getOrDefault(character, 0);
                        nodes.get(child).failure = failure;
                        nodes.get(child).rules.addAll(nodes.get(failure).rules);
                        queue.add(child);
                    }
                }
                for (Node node : nodes) node.freeze();
                return new TokenIndex(List.copyOf(nodes));
            }
        }

        private static final class Node {
            private Map<Character, Integer> next = new HashMap<>();
            private List<Rule> rules = new ArrayList<>();
            private int failure;

            void freeze() {
                next = Map.copyOf(next);
                rules = List.copyOf(rules);
            }
        }
    }
}
