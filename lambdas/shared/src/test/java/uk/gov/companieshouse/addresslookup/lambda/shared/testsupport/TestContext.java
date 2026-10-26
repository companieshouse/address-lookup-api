package uk.gov.companieshouse.addresslookup.lambda.shared.testsupport;

import com.amazonaws.services.lambda.runtime.Context;

import java.lang.reflect.Proxy;

/** Builds a minimal AWS Lambda {@link Context} for unit tests. */
public final class TestContext {
    private TestContext() {
    }

    public static Context withRemainingMillis(long remainingMillis) {
        return (Context)
                Proxy.newProxyInstance(
                        Context.class.getClassLoader(),
                        new Class<?>[]{Context.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getRemainingTimeInMillis" -> (int) remainingMillis;
                                    case "getMemoryLimitInMB" -> 1024;
                                    default -> null;
                                });
    }
}

