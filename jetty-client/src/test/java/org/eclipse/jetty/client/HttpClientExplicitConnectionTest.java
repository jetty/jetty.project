//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BlockingResponseListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientExplicitConnectionTest extends AbstractHttpClientServerTest
{
    public HttpClientExplicitConnectionTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testExplicitConnection() throws Exception
    {
        start(new EmptyServerHandler());

        Destination destination = client.getDestination(scheme, "localhost", connector.getLocalPort());
        try (Connection connection = destination.newConnection().get(5, TimeUnit.SECONDS))
        {
            Request request = client.newRequest(destination.host(), destination.port()).scheme(scheme);
            BlockingResponseListener listener = new BlockingResponseListener();
            connection.send(request, listener);
            ContentResponse response = listener.get(5, TimeUnit.SECONDS);

            Assert.assertNotNull(response);
            Assert.assertEquals(200, response.status());

            HttpDestination httpDestination = (HttpDestination)destination;
            Assert.assertTrue(httpDestination.getActiveConnections().isEmpty());
            Assert.assertTrue(httpDestination.getIdleConnections().isEmpty());
        }
    }
}
