//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpScheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ExternalSiteTest
{
    private HttpClient client;

    @BeforeEach
    public void prepare() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        client.stop();
    }

    @Tag("external")
    @Test
    public void testExternalSite() throws Exception
    {
        String host = "wikipedia.org";
        int port = 80;

        // Verify that we have connectivity
        assumeCanConnectTo(host, port);

        final CountDownLatch latch1 = new CountDownLatch(1);
        client.newRequest(host, port).send(result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(200, result.getResponse().getStatus());
            latch1.countDown();
        });
        assertTrue(latch1.await(15, TimeUnit.SECONDS));

        // Try again the same URI, but without specifying the port
        final CountDownLatch latch2 = new CountDownLatch(1);
        client.newRequest("http://" + host).send(result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(200, result.getResponse().getStatus());
            latch2.countDown();
        });
        assertTrue(latch2.await(15, TimeUnit.SECONDS));
    }

    @Tag("external")
    @Test
    public void testExternalSSLSite() throws Exception
    {
        String host = "api-3t.paypal.com";
        int port = 443;

        // Verify that we have connectivity
        assumeCanConnectTo(host, port);

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(host, port).scheme("https").path("/nvp").send(result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(200, result.getResponse().getStatus());
            latch.countDown();
        });
        assertTrue(latch.await(15, TimeUnit.SECONDS));
    }

    @Tag("external")
    @Test
    public void testExternalSiteWrongProtocol() throws Exception
    {
        String host = "github.com";
        int port = 22; // SSH port

        // Verify that we have connectivity
        assumeCanConnectTo(host, port);

        for (int i = 0; i < 2; ++i)
        {
            final CountDownLatch latch = new CountDownLatch(3);
            client.newRequest(host, port)
                .onResponseFailure((response, failure) -> latch.countDown())
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onFailure(Response response, Throwable failure)
                    {
                        latch.countDown();
                    }

                    @Override
                    public void onComplete(Result result)
                    {
                        assertTrue(result.isFailed());
                        latch.countDown();
                    }
                });
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        }
    }

    @Tag("external")
    @Test
    public void testExternalSiteRedirect() throws Exception
    {
        String host = "twitter.com";
        int port = 443;

        // Verify that we have connectivity
        assumeCanConnectTo(host, port);

        ContentResponse response = client.newRequest(host, port)
            .scheme(HttpScheme.HTTPS.asString())
            .path("/twitter")
            .timeout(15, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
    }

    protected void assumeCanConnectTo(String host, int port)
    {
        try
        {
            new Socket(host, port).close();
        }
        catch (Throwable x)
        {
            assumeTrue(false, "Unable to connect");
        }
    }
}
