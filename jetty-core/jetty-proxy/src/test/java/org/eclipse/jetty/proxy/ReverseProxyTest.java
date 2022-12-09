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

package org.eclipse.jetty.proxy;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReverseProxyTest extends AbstractProxyTest
{
    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testSimple(HttpVersion httpVersion) throws Exception
    {
        String clientContent = "hello";
        String serverContent = "world";
        startServer(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                String requestContent = Content.Source.asString(request);
                assertEquals(clientContent, requestContent);
                Content.Sink.write(response, true, serverContent, callback);
            }
        });

        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                ClientConnector proxyClientConnector = new ClientConnector();
                QueuedThreadPool proxyClientThreads = new QueuedThreadPool();
                proxyClientThreads.setName("proxy-client");
                proxyClientConnector.setExecutor(proxyClientThreads);
                HTTP2Client proxyHTTP2Client = new HTTP2Client(proxyClientConnector);
                return new HttpClient(new HttpClientTransportDynamic(proxyClientConnector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(proxyHTTP2Client)));
            }

            @Override
            protected org.eclipse.jetty.client.api.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                // Use the client to proxy protocol also from the proxy to server.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }
        });

        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .body(new StringRequestContent(clientContent))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(serverContent, response.getContentAsString());
    }

    @ParameterizedTest
    @MethodSource("httpVersions")
    public void testEmptyHeaderValue(HttpVersion httpVersion) throws Exception
    {
        String emptyHeaderName = "X-Empty";
        startServer(new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                assertEquals("", request.getHeaders().get(emptyHeaderName));
                response.getHeaders().put(emptyHeaderName, "");
                callback.succeeded();
            }
        });
        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort()))
        {
            @Override
            protected HttpClient newHttpClient()
            {
                ClientConnector proxyClientConnector = new ClientConnector();
                QueuedThreadPool proxyClientThreads = new QueuedThreadPool();
                proxyClientThreads.setName("proxy-client");
                proxyClientConnector.setExecutor(proxyClientThreads);
                HTTP2Client proxyHTTP2Client = new HTTP2Client(proxyClientConnector);
                return new HttpClient(new HttpClientTransportDynamic(proxyClientConnector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(proxyHTTP2Client)));
            }

            @Override
            protected org.eclipse.jetty.client.api.Request newProxyToServerRequest(Request clientToProxyRequest, HttpURI newHttpURI)
            {
                // Use the client to proxy protocol also from the proxy to server.
                return super.newProxyToServerRequest(clientToProxyRequest, newHttpURI)
                    .version(httpVersion);
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .version(httpVersion)
            .headers(headers -> headers.put(emptyHeaderName, ""))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals("", response.getHeaders().get(emptyHeaderName));
    }
}
