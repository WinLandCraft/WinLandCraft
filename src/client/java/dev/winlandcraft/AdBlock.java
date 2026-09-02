package dev.winlandcraft;

import com.cinemamod.mcef.MCEF;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class AdBlock {
    private static final Gson GSON = new Gson();
    private static final LongAdder BLOCKED = new LongAdder();
    private static final LongAdder REDIRECTED = new LongAdder();
    private static final LongAdder COSMETIC_QUERIES = new LongAdder();
    private static final LongAdder UNSUPPORTED_PROCEDURAL = new LongAdder();
    private static final LongAdder NATIVE_ERRORS = new LongAdder();
    private static final AtomicLong NEXT_HEALTH_LOG = new AtomicLong();
    private static final AtomicLong NEXT_ERROR_LOG = new AtomicLong();
    private static CefMessageRouter messageRouter;

    private static final CefResourceRequestHandler FILTER = new CefResourceRequestHandlerAdapter() {
        @Override public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
            if (request == null) return false;
            String url = request.getURL();
            if (url == null || url.isBlank()) return false;
            if (bypasses(url)) return false;
            String top = browser == null ? "" : browser.getURL();
            String source = frame == null ? top : frame.getURL();
            if (source == null || source.isBlank()) source = top;
            if (!AdBlockNative.isReady()) return false;
            String decision;
            try {
                decision = AdBlockNative.check(url, source == null ? "" : source,
                        resourceName(request.getResourceType()), request.getMethod() == null ? "GET" : request.getMethod());
            } catch (RuntimeException failure) {
                NATIVE_ERRORS.increment();
                logNativeError(url, failure);
                logHealth();
                return false;
            }
            if ("B".equals(decision)) {
                BLOCKED.increment();
                logHealth();
                return true;
            }
            if (decision != null && decision.length() > 1 && decision.charAt(0) == 'R') {
                String target = decision.substring(1);
                if (!target.equals(url)) {
                    request.setURL(target);
                    REDIRECTED.increment();
                    logHealth();
                }
            }
            return false;
        }
    };

    private AdBlock() {}

    static void install() {
        CompletableFuture.runAsync(AdBlock::initializeEngine);
        MCEF.scheduleForInit(success -> {
            if (!success) return;
            var client = MCEF.getClient();
            client.getHandle().addRequestHandler(new CefRequestHandlerAdapter() {
                @Override public CefResourceRequestHandler getResourceRequestHandler(
                        CefBrowser browser, CefFrame frame, CefRequest request, boolean navigation,
                        boolean download, String initiator, BoolRef disableDefaultHandling) {
                    return FILTER;
                }
            });
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override public void onLoadStart(CefBrowser browser, CefFrame frame,
                        CefRequest.TransitionType transitionType) {
                    inject(frame);
                }

                @Override public void onLoadEnd(CefBrowser browser, CefFrame frame, int status) {
                    inject(frame);
                }
            });
            messageRouter = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig(
                    "winlandcraftAdblockQuery", "winlandcraftAdblockCancel"), new CosmeticQueryHandler());
            client.getHandle().addMessageRouter(messageRouter);
            WinLandCraftClient.LOGGER.info("Brave-compatible adblock handlers attached to Chromium");
        });
    }

    private static void initializeEngine() {
        try {
            int ruleCount = AdBlockNative.initialize(AdBlockAssets.load());
            WinLandCraftClient.LOGGER.info("Brave adblock engine loaded: {} network/cosmetic rules", ruleCount);
        } catch (Exception | LinkageError failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            WinLandCraftClient.LOGGER.error("Native Brave adblock could not start; browsing will continue unfiltered", failure);
        }
    }

    private static void inject(CefFrame frame) {
        if (!AdBlockNative.isReady() || frame == null) return;
        String url = frame.getURL();
        if (url == null || bypasses(url) || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        try {
            String serialized = AdBlockNative.cosmetics(url);
            if (serialized == null) return;
            JsonObject resources = JsonParser.parseString(serialized).getAsJsonObject();
            String css = css(resources);
            String scriptlets = resources.get("injected_script").getAsString();
            frame.executeJavaScript(pageScript(GSON.toJson(css), scriptlets), url, 1);
        } catch (RuntimeException failure) {
            WinLandCraftClient.LOGGER.warn("Could not apply cosmetic adblock rules to {}", url, failure);
        }
    }

    private static String css(JsonObject resources) {
        var output = new StringBuilder();
        JsonElement selectors = resources.get("hide_selectors");
        if (selectors != null && selectors.isJsonArray()) for (JsonElement selector : selectors.getAsJsonArray())
            appendRule(output, selector.getAsString(), "display:none!important");

        JsonElement procedural = resources.get("procedural_actions");
        if (procedural != null && procedural.isJsonArray()) for (JsonElement encoded : procedural.getAsJsonArray()) {
            try {
                JsonObject filter = JsonParser.parseString(encoded.getAsString()).getAsJsonObject();
                var operators = filter.getAsJsonArray("selector");
                if (operators.size() != 1 || !"css-selector".equals(
                        operators.get(0).getAsJsonObject().get("type").getAsString())) {
                    UNSUPPORTED_PROCEDURAL.increment();
                    continue;
                }
                String selector = operators.get(0).getAsJsonObject().get("arg").getAsString();
                JsonElement actionElement = filter.get("action");
                if (actionElement == null || actionElement.isJsonNull()) {
                    appendRule(output, selector, "display:none!important");
                } else {
                    JsonObject action = actionElement.getAsJsonObject();
                    if ("style".equals(action.get("type").getAsString()))
                        appendRule(output, selector, action.get("arg").getAsString());
                    else UNSUPPORTED_PROCEDURAL.increment();
                }
            } catch (RuntimeException malformed) {
                UNSUPPORTED_PROCEDURAL.increment();
            }
        }
        return output.toString();
    }

    private static void appendRule(StringBuilder output, String selector, String declaration) {
        if (!selector.isBlank() && !declaration.isBlank())
            output.append(selector).append('{').append(declaration).append("}\n");
    }

    private static String pageScript(String cssJson, String scriptlets) {
        return """
                (() => {
                  if (window.__winlandcraftAdblock) return;
                  window.__winlandcraftAdblock = true;
                  const scriptletGlobals = (() => {
                    const methods = ['has', 'get', 'set'];
                    return new Proxy(new Map(), {
                      get(target, property) {
                        return methods.includes(property) ? Map.prototype[property].bind(target) : target.get(property);
                      },
                      set(target, property, value) {
                        if (!methods.includes(property)) target.set(property, value);
                        return true;
                      }
                    });
                  })();
                  let deAmpEnabled = false;
                  try {
                %1$s
                  } catch (error) {
                    console.debug('WinLandCraft adblock scriptlet failed', error);
                  }
                  const state = {
                    css: %2$s,
                    classes: new Set(), ids: new Set(), seenClasses: new Set(), seenIds: new Set(),
                    selectors: new Set(), timer: 0, inFlight: false, retries: 0, style: null
                  };
                  const addCss = css => {
                    if (!css) return;
                    if (!state.style) {
                      state.style = document.createElement('style');
                      state.style.dataset.winlandcraftAdblock = '';
                      document.documentElement.appendChild(state.style);
                    }
                    state.style.appendChild(document.createTextNode(css));
                  };
                  const remember = (value, seen, pending) => {
                    if (!value || value.length > 512 || seen.size >= 32768 || seen.has(value)) return;
                    seen.add(value); pending.add(value);
                  };
                  const scan = root => {
                    if (!(root instanceof Element)) return;
                    for (const value of root.classList) remember(value, state.seenClasses, state.classes);
                    remember(root.id, state.seenIds, state.ids);
                    for (const element of root.querySelectorAll('[class],[id]')) {
                      for (const value of element.classList) remember(value, state.seenClasses, state.classes);
                      remember(element.id, state.seenIds, state.ids);
                    }
                  };
                  const take = set => {
                    const values = Array.from(set).slice(0, 512);
                    for (const value of values) set.delete(value);
                    return values;
                  };
                  const schedule = () => {
                    if (!state.timer && !state.inFlight) state.timer = setTimeout(flush, 100);
                  };
                  const flush = () => {
                    state.timer = 0;
                    if (state.inFlight || (!state.classes.size && !state.ids.size)) return;
                    if (typeof winlandcraftAdblockQuery !== 'function') {
                      if (state.retries++ < 20) schedule();
                      return;
                    }
                    state.retries = 0;
                    state.inFlight = true;
                    const query = JSON.stringify([take(state.classes), take(state.ids)]);
                    winlandcraftAdblockQuery({
                      request: location.href.slice(0, 4096) + '\\n' + query,
                      onSuccess(response) {
                        try {
                          const selectors = JSON.parse(response).filter(value => typeof value === 'string'
                              && value.length <= 8192 && !state.selectors.has(value));
                          selectors.forEach(value => state.selectors.add(value));
                          addCss(selectors.map(value => value + '{display:none!important}').join('\\n'));
                        } finally {
                          state.inFlight = false;
                          if (state.classes.size || state.ids.size) schedule();
                        }
                      },
                      onFailure() { state.inFlight = false; schedule(); }
                    });
                  };
                  const attach = () => {
                    if (!document.documentElement) {
                      document.addEventListener('readystatechange', attach, { once: true });
                      return;
                    }
                    addCss(state.css);
                    scan(document.documentElement);
                    new MutationObserver(records => {
                      for (const record of records) {
                        if (record.type === 'attributes') scan(record.target);
                        else for (const node of record.addedNodes) scan(node);
                      }
                      schedule();
                    }).observe(document.documentElement, {
                      subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'id']
                    });
                    schedule();
                  };
                  attach();
                })();
                """.formatted(scriptlets, cssJson);
    }

    private static String resourceName(CefRequest.ResourceType type) {
        if (type == null) return "other";
        return switch (type) {
            case RT_MAIN_FRAME, RT_NAVIGATION_PRELOAD_MAIN_FRAME -> "document";
            case RT_SUB_FRAME, RT_NAVIGATION_PRELOAD_SUB_FRAME -> "subdocument";
            case RT_STYLESHEET -> "stylesheet";
            case RT_SCRIPT, RT_WORKER, RT_SHARED_WORKER, RT_SERVICE_WORKER -> "script";
            case RT_IMAGE -> "image";
            case RT_FONT_RESOURCE -> "font";
            case RT_OBJECT, RT_PLUGIN_RESOURCE -> "object";
            case RT_MEDIA -> "media";
            case RT_XHR -> "xmlhttprequest";
            case RT_PING -> "ping";
            default -> "other";
        };
    }

    /** The private codec bridge must not inherit a transient about:blank initiator from JCEF. */
    static boolean bypasses(String url) {
        try {
            URI parsed = URI.create(url);
            return "http".equalsIgnoreCase(parsed.getScheme()) && "127.0.0.1".equals(parsed.getHost());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static void logHealth() {
        long now = System.currentTimeMillis(), next = NEXT_HEALTH_LOG.get();
        if (now >= next && NEXT_HEALTH_LOG.compareAndSet(next, now + 30_000))
            WinLandCraftClient.LOGGER.info("Brave adblock health: blocked={}, redirected={}, cosmeticQueries={}, unsupportedProcedural={}, nativeErrors={}",
                    BLOCKED.sum(), REDIRECTED.sum(), COSMETIC_QUERIES.sum(), UNSUPPORTED_PROCEDURAL.sum(),
                    NATIVE_ERRORS.sum());
    }

    private static void logNativeError(String url, RuntimeException failure) {
        long now = System.currentTimeMillis(), next = NEXT_ERROR_LOG.get();
        if (now >= next && NEXT_ERROR_LOG.compareAndSet(next, now + 30_000))
            WinLandCraftClient.LOGGER.warn("Native adblock failed open for {}", url, failure);
    }

    private static final class CosmeticQueryHandler extends CefMessageRouterHandlerAdapter {
        @Override public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                boolean persistent, CefQueryCallback callback) {
            if (request == null || request.length() > 131_072) return false;
            int split = request.indexOf('\n');
            if (split < 1 || split > 4096) return false;
            String url = request.substring(0, split);
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
            try {
                callback.success(AdBlockNative.hiddenSelectors(url, request.substring(split + 1)));
                COSMETIC_QUERIES.increment();
                logHealth();
            } catch (RuntimeException failure) {
                NATIVE_ERRORS.increment();
                logNativeError(url, failure);
                callback.failure(1, "Adblock cosmetic query failed");
            }
            return true;
        }
    }
}
