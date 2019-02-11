package org.eclipse.jetty.tests.distribution;

import java.util.function.Supplier;

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;

public class AbstractDistributionTest
{
    protected HttpClient client;

    protected void startHttpClient() throws Exception
    {
        startHttpClient(HttpClient::new);
    }

    protected void startHttpClient(Supplier<HttpClient> supplier) throws Exception
    {
        client = supplier.get();
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
    }
}
