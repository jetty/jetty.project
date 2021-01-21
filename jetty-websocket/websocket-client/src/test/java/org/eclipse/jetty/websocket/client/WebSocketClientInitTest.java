//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.client;

import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test various init techniques for WebSocketClient
 */
public class WebSocketClientInitTest
{
    /**
     * This is the new Jetty 9.4 advanced usage mode of WebSocketClient,
     * that allows for more robust HTTP configurations (such as authentication,
     * cookies, and proxies)
     *
     * @throws Exception on test failure
     */
    @Test
    public void testInitHttpClientStartedOutside() throws Exception
    {
        HttpClient http = new HttpClient();
        http.start();

        try
        {
            WebSocketClient ws = new WebSocketClient(http);
            ws.start();
            try
            {
                assertThat("HttpClient", ws.getHttpClient(), is(http));

                assertThat("WebSocketClient started", ws.isStarted(), is(true));
                assertThat("HttpClient started", http.isStarted(), is(true));

                HttpClient httpBean = ws.getBean(HttpClient.class);
                assertThat("HttpClient bean is managed", ws.isManaged(httpBean), is(false));
                assertThat("WebSocketClient should not be found in HttpClient", http.getBean(WebSocketClient.class), nullValue());
            }
            finally
            {
                ws.stop();
            }
            assertThat("WebSocketClient stopped", ws.isStopped(), is(true));
            assertThat("HttpClient stopped", http.isStopped(), is(false));
        }
        finally
        {
            http.stop();
        }

        assertThat("HttpClient stopped", http.isStopped(), is(true));
    }

    /**
     * This is the backward compatibility mode of WebSocketClient.
     * This is also the primary mode that JSR356 Standalone WebSocket Client is initialized.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testInitHttpClientSyntheticStart() throws Exception
    {
        HttpClient http = null;
        WebSocketClient ws = new WebSocketClient();
        ws.start();
        try
        {
            http = ws.getHttpClient();

            assertThat("WebSocketClient started", ws.isStarted(), is(true));
            assertThat("HttpClient started", http.isStarted(), is(true));

            HttpClient httpBean = ws.getBean(HttpClient.class);
            assertThat("HttpClient bean found in WebSocketClient", httpBean, is(http));
            assertThat("HttpClient bean is managed", ws.isManaged(httpBean), is(true));
            assertThat("WebSocketClient should not be found in HttpClient", http.getBean(WebSocketClient.class), nullValue());
        }
        finally
        {
            ws.stop();
        }

        assertThat("WebSocketClient stopped", ws.isStopped(), is(true));
        assertThat("HttpClient stopped", http.isStopped(), is(true));
    }
}
