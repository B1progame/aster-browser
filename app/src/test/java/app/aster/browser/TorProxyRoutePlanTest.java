package app.aster.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;

public final class TorProxyRoutePlanTest {
    @Test
    public void routesEverySchemeThroughLocalTorHttpBridgeWithoutDirectFallback()
            throws Exception {
        Class<?> planClass;
        try {
            planClass = Class.forName("app.aster.browser.TorProxyRoutePlan");
        } catch (ClassNotFoundException missing) {
            fail("Chromium Tor needs an explicit fail-closed proxy plan");
            return;
        }

        Method create = planClass.getMethod("forLocalHttpProxyPort", int.class);
        List<?> rules = (List<?>) create.invoke(null, 49152);
        assertEquals(1, rules.size());
        assertRule(rules.get(0), "127.0.0.1:49152", "*");
    }

    private static void assertRule(Object rule, String expectedProxy, String expectedScheme)
            throws Exception {
        Field proxyUrl = rule.getClass().getField("proxyUrl");
        Field schemeFilter = rule.getClass().getField("schemeFilter");
        assertEquals(expectedProxy, proxyUrl.get(rule));
        assertEquals(expectedScheme, schemeFilter.get(rule));
    }
}
