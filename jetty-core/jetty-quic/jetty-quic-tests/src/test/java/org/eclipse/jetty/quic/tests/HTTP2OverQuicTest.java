//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.tests;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.client.QuicTransport;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HTTP2OverQuicTest extends AbstractTest
{
    private HttpClient httpClient;
    private Transport transport;

    private void start(Handler handler) throws Exception
    {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        httpConfig.addCustomizer(new HostHeaderCustomizer());
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig);
        prepareServer(h2);
        server.setHandler(handler);
        server.start();

        prepareClient();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        http2Client.setUseALPN(true);
        http2Client.setApplicationProtocols(List.of("h2"));
        httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        httpClient.start();
        transport = new QuicTransport(quicClient);
        quicClient.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(httpClient);
    }

    @Test
    public void testHTTP2() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
                return true;
            }
        });

        String content = "Hello QUIC";
        ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .transport(transport)
            .body(new StringRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(content, response.getContentAsString());
    }
}
