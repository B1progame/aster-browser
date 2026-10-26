package app.aster.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fail-closed WebView proxy rule for the app-owned Tor HTTP bridge. */
public final class TorProxyRoutePlan {
    private TorProxyRoutePlan() {}

    public static List<Rule> forLocalHttpProxyPort(int localProxyPort) {
        requireValidPort(localProxyPort);
        // AndroidX WebKit expects host:port, not a URL with a scheme.
        List<Rule> rules = new ArrayList<>(1);
        rules.add(new Rule("127.0.0.1:" + localProxyPort, "*"));
        return Collections.unmodifiableList(rules);
    }

    private static void requireValidPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Tor proxy port is invalid");
        }
    }

    public static final class Rule {
        public final String proxyUrl;
        public final String schemeFilter;

        private Rule(String proxyUrl, String schemeFilter) {
            this.proxyUrl = proxyUrl;
            this.schemeFilter = schemeFilter;
        }
    }
}
